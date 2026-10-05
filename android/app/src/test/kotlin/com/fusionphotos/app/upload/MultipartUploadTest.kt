package com.fusionphotos.app.upload

import com.fusionphotos.crypto.LibSodiumAdapter
import com.fusionphotos.crypto.Sodium
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Large originals go multipart, and the property that matters is not "it works" but "it can be
 * resumed without retransmitting everything". These tests drive real libsodium encryption and
 * assert exactly which parts were sent, against the slicing the server also computes.
 */
class MultipartUploadTest {
    private val sodium: Sodium = LibSodiumAdapter(SodiumJava())
    private val vaultKey = ByteArray(32) { (it + 5).toByte() }

    /** 40 MiB: above the 32 MiB multipart threshold, and five parts of exactly 8 MiB. */
    private val fortyMiB = 40 * 1024 * 1024

    private fun bigBytes(size: Int) = ByteArray(size) { ((it * 31) % 251).toByte() }

    private fun source(bytes: ByteArray, ref: String = "android:7:1:1") = LocalSource(
        clientRef = ref,
        fileName = "PANO_0001.jpg",
        mimeType = "image/jpeg",
        capturedAtMs = null,
        readBytes = { bytes },
    )

    private fun sliceLength(size: Long, partCount: Int, number: Int): Long =
        UploadPlan.partRange(size, partCount, number - 1).count().toLong()

    @Test
    fun `a large original is sent in parts that cover the ciphertext exactly`() = runBlocking {
        val remote = FakeUploadRemote()
        val transfer = FakeObjectTransfer()

        PhotoUploader(sodium, remote, transfer).upload(source(bigBytes(fortyMiB)), vaultKey)

        // The plan chose multipart, and got the part count the server's slicing implies.
        // (The payload is the ciphertext: plaintext + 41 bytes, so 40 MiB is six parts.)
        val partCount = UploadPlan.planFor(remote.reservedSize).partCount
        assertTrue(partCount > 2, "this size must go multipart, got $partCount part(s)")
        assertEquals(1, remote.multipartInits.size)
        assertEquals(partCount, remote.multipartInits.single().second)
        assertEquals((1..partCount).toList(), transfer.successfulParts())

        val lengths = (1..partCount).map { transfer.bytesForPart(it)!!.size.toLong() }
        assertEquals((1..partCount).map { sliceLength(remote.reservedSize, partCount, it) }, lengths)
        assertEquals(remote.reservedSize, lengths.sum(), "the parts must cover every ciphertext byte")

        // Completion names every part once, in order.
        assertEquals((1..partCount).toList(), remote.multipartCompletions.single().map { it.number })
    }

    @Test
    fun `a resumed upload only retransmits the missing parts`() = runBlocking {
        val remote = FakeUploadRemote()
        val crashing = FakeObjectTransfer(failAfter = 2)
        assertFailsWith<UploadException> {
            PhotoUploader(sodium, remote, crashing).upload(source(bigBytes(fortyMiB)), vaultKey)
        }
        assertEquals(listOf(1, 2), crashing.successfulParts(), "the first run got two parts in")

        // The server now holds those two, and a retry must not send them again.
        remote.assumePartsUploaded(2)
        val partCount = UploadPlan.planFor(remote.reservedSize).partCount
        val retry = FakeObjectTransfer()
        PhotoUploader(sodium, remote, retry).upload(source(bigBytes(fortyMiB)), vaultKey)

        assertEquals((3..partCount).toList(), retry.successfulParts())
        assertEquals(listOf((3..partCount).toList()), remote.multipartUrlRequests)
        assertEquals(1, remote.multipartInits.size, "an existing upload must not be re-initialised")
        // Completion still names every part: the server validates the whole set.
        assertEquals((1..partCount).toList(), remote.multipartCompletions.last().map { it.number })
    }

    @Test
    fun `a part the server reports with the wrong size is sent again`() = runBlocking {
        val remote = FakeUploadRemote()
        // The server thinks part 1 exists, but with a length that cannot be right.
        remote.uploadedParts[1] = 1234
        remote.multipartStarted = true

        val transfer = FakeObjectTransfer()
        PhotoUploader(sodium, remote, transfer).upload(source(bigBytes(fortyMiB)), vaultKey)

        val partCount = UploadPlan.planFor(remote.reservedSize).partCount
        assertEquals((1..partCount).toList(), transfer.successfulParts(), "part 1 must be re-sent")
        assertTrue(remote.multipartInits.isEmpty(), "an existing upload must not be re-initialised")
    }

    @Test
    fun `an expired session is a permanent failure, not an endless retry`() = runBlocking {
        val remote = FakeUploadRemote(multipartConflict = true)

        val failure = assertFailsWith<UploadException> {
            PhotoUploader(sodium, remote, FakeObjectTransfer())
                .upload(source(bigBytes(fortyMiB)), vaultKey)
        }

        assertTrue(failure.permanent, "409 means no amount of retrying this ref will help")
        assertTrue(failure.message!!.contains("expired"))
    }

    @Test
    fun `an assembled size mismatch is a permanent failure`() = runBlocking {
        val remote = FakeUploadRemote()
        // Reserve first, then have the server report a different assembled size.
        remote.completeSizeOverride = 1

        val failure = assertFailsWith<UploadException> {
            PhotoUploader(sodium, remote, FakeObjectTransfer())
                .upload(source(bigBytes(fortyMiB)), vaultKey)
        }

        assertTrue(failure.permanent)
        assertTrue(failure.message!!.contains("expected"))
    }

    @Test
    fun `part boundaries match the server's slicing when the size does not divide evenly`() = runBlocking {
        val remote = FakeUploadRemote()
        val transfer = FakeObjectTransfer()

        PhotoUploader(sodium, remote, transfer).upload(source(bigBytes(fortyMiB + 7)), vaultKey)

        val partCount = UploadPlan.planFor(remote.reservedSize).partCount
        assertTrue(partCount > 1, "this size must go multipart")
        val lengths = (1..partCount).map { transfer.bytesForPart(it)!!.size.toLong() }
        assertEquals((1..partCount).map { sliceLength(remote.reservedSize, partCount, it) }, lengths)
        assertEquals(remote.reservedSize, lengths.sum(), "no byte may be dropped or duplicated")
        // The last part is the short one, exactly as the server computes it.
        assertTrue(lengths.last() <= lengths.first())
    }
}
