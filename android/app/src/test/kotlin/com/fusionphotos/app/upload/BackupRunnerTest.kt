package com.fusionphotos.app.upload

import com.fusionphotos.crypto.LibSodiumAdapter
import com.fusionphotos.crypto.Sodium
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queue is where a backup app earns trust: it must survive a kill, not re-upload what is
 * already stored, retry what is worth retrying, stop retrying what is not, and behave sanely
 * when the vault is locked (the key is only in the Keystore while enrolled).
 */
class BackupRunnerTest {
    private val sodium: Sodium = LibSodiumAdapter(SodiumJava())
    private val vaultKey = ByteArray(32) { (it + 3).toByte() }
    private var now = 1_000_000L

    private fun bytesFor(ref: String) = ByteArray(1200) { ((it + ref.hashCode()) % 251).toByte() }

    private fun runnerFor(
        store: InMemoryQueueStore,
        remote: FakeUploadRemote = FakeUploadRemote(),
        transfer: FakeObjectTransfer = FakeObjectTransfer(),
        key: () -> ByteArray? = { vaultKey },
        readBytes: suspend (QueueItem) -> ByteArray = { bytesFor(it.clientRef) },
    ) = BackupRunner(
        store = store,
        uploader = PhotoUploader(sodium, remote, transfer),
        vaultKey = key,
        readBytes = readBytes,
        clock = { now },
        random = Random(7),
    )

    @Test
    fun `drains the queue in order and records the asset`() = runBlocking {
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("a"), queueItem("b"), queueItem("c")))

        val summary = runnerFor(store).run()

        assertEquals(3, summary.uploaded)
        assertEquals(0, summary.failed)
        assertEquals(StopReason.DRAINED, summary.stopReason)
        assertEquals(3, store.counts().uploaded)
        assertEquals(0, store.counts().outstanding)
        // Every finished row remembers which asset it became.
        assertTrue(store.items(QueueState.UPLOADED).all { it.assetId == FakeUploadRemote.ASSET_ID })
    }

    @Test
    fun `a row left in flight by a killed process is picked up again`() = runBlocking {
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("a")))
        // Simulate the process dying after the claim: the row stays IN_FLIGHT.
        assertNotNull(store.claimNext(now))
        assertEquals(1, store.counts().inFlight)

        assertEquals(1, store.releaseInFlight(now), "recovery must return in-flight rows")
        val summary = runnerFor(store).run()

        assertEquals(1, summary.uploaded, "the interrupted upload must finish after a restart")
        assertEquals(0, store.counts().outstanding)
    }

    @Test
    fun `a failed transfer waits out its backoff and then succeeds`() = runBlocking {
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("a")))
        val transfer = FakeObjectTransfer(status = 503)

        val first = runnerFor(store, transfer = transfer).run()
        assertEquals(1, first.failed)
        val row = store.items(QueueState.FAILED).single()
        assertEquals(1, row.attempts)
        assertNotNull(row.lastError)

        // Not claimable immediately: the backoff must be respected.
        assertNull(store.claimNext(now), "a freshly failed row must not be retried at once")

        // Once the delay has certainly elapsed, a healthy network completes it.
        now += QueueBackoff.MAX_DELAY_MS
        val second = runnerFor(store, transfer = FakeObjectTransfer()).run()
        assertEquals(1, second.uploaded)
        assertEquals(0, store.counts().outstanding)
    }

    @Test
    fun `repeated failures end in the dead letter, not an endless retry`() = runBlocking {
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("a")))
        val runner = runnerFor(store, transfer = FakeObjectTransfer(status = 500))

        repeat(QueueBackoff.MAX_ATTEMPTS) {
            now += QueueBackoff.MAX_DELAY_MS
            runner.run()
        }

        val counts = store.counts()
        assertEquals(1, counts.dead, "it must dead-letter after ${QueueBackoff.MAX_ATTEMPTS} attempts")
        assertEquals(0, counts.outstanding)
        assertEquals(QueueBackoff.MAX_ATTEMPTS, store.items(QueueState.DEAD).single().attempts)

        // A dead row is not silently retried by the next run...
        val after = runnerFor(store).run()
        assertEquals(0, after.processed)
        // ...but a user-requested retry revives it.
        assertEquals(1, store.retryDead(now))
        assertEquals(QueueState.PENDING, store.items(QueueState.PENDING).single().state)
    }

    @Test
    fun `a missing vault key stops the run instead of failing every item`() = runBlocking {
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("a"), queueItem("b")))

        val summary = runnerFor(store, key = { null }).run()

        assertEquals(StopReason.KEY_MISSING, summary.stopReason)
        assertEquals(0, summary.processed)
        val counts = store.counts()
        assertEquals(2, counts.pending)
        assertEquals(0, counts.uploaded, "nothing may be claimed when it cannot be encrypted")
        assertEquals(0, counts.inFlight, "and nothing may be left claimed")
    }

    @Test
    fun `losing the key mid-run stops cleanly between items`() = runBlocking {
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("a"), queueItem("b")))
        var checks = 0
        val key: () -> ByteArray? = { if (checks++ < 2) vaultKey else null }

        val summary = runnerFor(store, key = key).run()

        assertEquals(StopReason.KEY_MISSING, summary.stopReason)
        assertEquals(1, summary.uploaded)
        assertEquals(1, store.counts().pending, "the untouched item stays queued for later")
        assertEquals(0, store.counts().inFlight)
    }

    @Test
    fun `an unreadable original is dead-lettered instead of retried`() = runBlocking {
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("a")))
        val runner = runnerFor(store) { throw IllegalStateException("the file is gone") }

        val summary = runner.run()

        assertEquals(1, summary.dead)
        assertEquals(0, summary.failed, "retrying cannot fix a missing local file")
        val row = store.items(QueueState.DEAD).single()
        assertEquals(1, row.attempts)
        assertTrue(row.lastError!!.contains("the file is gone"))
    }

    @Test
    fun `a commit that fails after the object is stored converges on the retry`() = runBlocking {
        // The plan calls this case out explicitly: "handle the case where the object upload
        // succeeds but the database commit fails".
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("a")))
        val remote = FakeUploadRemote(failCompletions = 1)
        val transfer = FakeObjectTransfer()

        val first = runnerFor(store, remote = remote, transfer = transfer).run()
        assertEquals(1, first.failed, "the commit failure must be recorded, not hidden")
        assertEquals(1, transfer.uploaded.size, "the ciphertext really did reach S3")
        assertEquals(1, store.counts().failed)

        // The server now holds that client ref, so the retry must not upload the object again.
        remote.committed = true
        now += QueueBackoff.MAX_DELAY_MS
        val second = runnerFor(store, remote = remote, transfer = transfer).run()

        assertEquals(1, second.alreadyPresent)
        assertEquals(0, second.uploaded)
        assertEquals(1, transfer.uploaded.size, "the object must not be uploaded twice")
        assertEquals(1, store.counts().uploaded)
        assertEquals(0, store.counts().outstanding)
    }

    @Test
    fun `re-queueing the camera roll never resurrects finished work`() = runBlocking {
        val store = InMemoryQueueStore()
        assertEquals(1, store.enqueue(listOf(queueItem("a"))))
        assertEquals(0, store.enqueue(listOf(queueItem("a"))), "a duplicate enqueue adds nothing")

        runnerFor(store).run()
        assertEquals(1, store.counts().uploaded)

        // "Queue all photos" pressed again, with one new photo in the library.
        assertEquals(1, store.enqueue(listOf(queueItem("a"), queueItem("new"))))
        val counts = store.counts()
        assertEquals(1, counts.uploaded, "an uploaded row must stay uploaded")
        assertEquals(1, counts.pending)
        assertEquals(0, counts.inFlight)
    }

    @Test
    fun `a permanent upload failure dead-letters at once instead of retrying`() = runBlocking {
        val store = InMemoryQueueStore()
        store.enqueue(listOf(queueItem("big")))
        // A >32 MiB original (so it goes multipart) whose session the server has expired: 409.
        val big = ByteArray(40 * 1024 * 1024)
        val remote = FakeUploadRemote(multipartConflict = true)

        val summary = runnerFor(store, remote = remote, readBytes = { big }).run()

        assertEquals(1, summary.dead)
        assertEquals(0, summary.failed, "a fixable-looking retry loop would only waste battery")
        val row = store.items(QueueState.DEAD).single()
        assertEquals(1, row.attempts, "it must not have been retried")
        assertTrue(row.lastError!!.contains("expired"))
    }

    @Test
    fun `backoff grows, stays bounded, and dead-letters at the threshold`() {
        val random = Random(11)
        var previous = 0L
        for (attempts in 1..QueueBackoff.MAX_ATTEMPTS) {
            val delay = QueueBackoff.delayMs(attempts, random)
            assertTrue(delay >= QueueBackoff.BASE_DELAY_MS, "delay $delay must not dip below the base")
            assertTrue(delay <= QueueBackoff.MAX_DELAY_MS, "delay $delay must stay bounded")
            assertTrue(delay >= previous, "delay must not shrink as attempts grow ($delay < $previous)")
            previous = delay
        }
        assertEquals(QueueBackoff.MAX_DELAY_MS, QueueBackoff.delayMs(30, random), "it must saturate")
        assertTrue(!QueueBackoff.isDead(QueueBackoff.MAX_ATTEMPTS - 1))
        assertTrue(QueueBackoff.isDead(QueueBackoff.MAX_ATTEMPTS))
    }
}
