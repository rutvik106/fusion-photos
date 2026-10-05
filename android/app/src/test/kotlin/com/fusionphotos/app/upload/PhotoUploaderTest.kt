package com.fusionphotos.app.upload

import com.fusionphotos.app.media.Derivative
import com.fusionphotos.app.media.Derivatives
import com.fusionphotos.app.net.UploadDtos
import com.fusionphotos.app.net.UploadRemote
import com.fusionphotos.crypto.Crypto
import com.fusionphotos.crypto.EncryptedPayload
import com.fusionphotos.crypto.LibSodiumAdapter
import com.fusionphotos.crypto.Sodium
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Captures what the pipeline sends, and can pretend a ref is already committed. */
/**
 * The upload wire format is the one place a client can be silently wrong: the server validates
 * sizes and keys but can never look inside the ciphertext, because it does not hold the key.
 * So these tests **decrypt what the pipeline sent** and check the server's own rules against
 * the very bytes that were transferred.
 */
class PhotoUploaderTest {
    private val sodium: Sodium = LibSodiumAdapter(SodiumJava())
    private val vaultKey = ByteArray(32) { (it + 1).toByte() }

    // Deterministic but not trivial: every byte value appears, so a shifting bug shows up.
    private val plaintext = ByteArray(5000) { (it % 251).toByte() }

    private fun uploader(
        remote: FakeUploadRemote = FakeUploadRemote(),
        transfer: FakeObjectTransfer = FakeObjectTransfer(),
    ) = Triple(PhotoUploader(sodium, remote, transfer), remote, transfer)

    private fun source(ref: String = "android:42:deadbeef") = LocalSource(
        clientRef = ref,
        fileName = "IMG_0001.png",
        mimeType = "image/png",
        capturedAtMs = 1_700_000_000_000,
        readBytes = { plaintext },
    )

    @Test
    fun `the uploaded object decrypts back to the original bytes`() = runBlocking {
        val (uploader, remote, transfer) = uploader()
        val result = uploader.upload(source(), vaultKey)

        assertEquals(1, transfer.uploaded.size)
        val wire = transfer.uploaded.single().second
        // header(24) || one FINAL secretstream frame: ciphertext = plaintext + 17 (ABYTES).
        assertEquals(plaintext.size + Crypto.STREAM_HEADER_BYTES + 17, wire.size)

        // The key the server stores must be the key that opens the payload.
        val envelope = remote.completions.single().keyEnvelope
        val assetKey = Crypto.unwrapAssetKey(sodium, envelope, vaultKey)
        assertContentEquals(plaintext, Crypto.decryptPayloadBytes(sodium, wire, assetKey))
        assertEquals(Crypto.sha256Hex(plaintext), result.originalSha256)
        assertFalse(result.alreadyCommitted)
        assertEquals(FakeUploadRemote.ASSET_ID, result.assetId)
    }

    @Test
    fun `the manifest is readable by other clients but opaque to the server`() = runBlocking {
        val (uploader, remote, _) = uploader()
        uploader.upload(source(), vaultKey)

        val completion = remote.completions.single()
        val assetKey = Crypto.unwrapAssetKey(sodium, completion.keyEnvelope, vaultKey)
        val json = JSONObject(
            String(
                Crypto.decryptPayload(
                    sodium,
                    EncryptedPayload(completion.manifest.headerB64, completion.manifest.blobB64),
                    assetKey,
                ),
                Charsets.UTF_8,
            ),
        )

        assertEquals("IMG_0001.png", json.getString("fileName"))
        assertEquals("image/png", json.getString("mimeType"))
        assertEquals("original", json.getString("quality"))
        assertEquals("upload", json.getString("source"))
        assertEquals("android:42:deadbeef", json.getString("clientAssetRef"))
        assertEquals(plaintext.size.toLong(), json.getLong("originalSize"))
        assertEquals(Crypto.sha256Hex(plaintext), json.getString("originalSha256"))
        assertEquals(UploadManifest.PAYLOAD_NAME, json.getJSONObject("crypto").getString("payload"))
        assertEquals(1, json.getJSONObject("crypto").getInt("v"))
        assertEquals("2023-11-14T22:13:20Z", json.getString("capturedAt"))

        // ...and the metadata really is encrypted: neither the name nor the digest is visible
        // in what the server stores.
        val stored = completion.manifest.headerB64 + completion.manifest.blobB64
        assertFalse(stored.contains("IMG_0001"))
        assertFalse(stored.contains(Crypto.sha256Hex(plaintext).take(16)))
    }

    @Test
    fun `declared sizes and keys are exactly what the server re-checks`() = runBlocking {
        val (uploader, remote, transfer) = uploader()
        uploader.upload(source(), vaultKey)

        val request = remote.requests.single()
        val completion = remote.completions.single()
        val wire = transfer.uploaded.single().second

        assertEquals(plaintext.size.toLong(), request.originalSize)
        // The server compares the reserved size against the object it finds in S3.
        assertEquals(wire.size.toLong(), request.encryptedSizes["original"])
        assertEquals(
            Crypto.b64Len(completion.manifest.headerB64) + Crypto.b64Len(completion.manifest.blobB64),
            request.encryptedSizes["manifest"],
        )

        val resource = completion.resources.single()
        assertEquals("original", resource.variant)
        assertEquals("${FakeUploadRemote.ASSET_ID}/original", resource.objectKey)
        assertEquals(wire.size.toLong(), resource.ciphertextSize)
        // The manifest is a database column, not an object: the server rejects it as a resource.
        assertTrue(completion.resources.none { it.variant == "manifest" })
        assertTrue(
            UploadDtos.OBJECT_VARIANTS.contains(resource.variant),
            "only a real object variant may be declared",
        )
    }

    @Test
    fun `an already committed ref uploads nothing`() = runBlocking {
        val (uploader, remote, transfer) = uploader(FakeUploadRemote(committed = true))
        val result = uploader.upload(source(), vaultKey)

        assertTrue(result.alreadyCommitted)
        assertEquals(FakeUploadRemote.ASSET_ID, result.assetId)
        assertTrue(transfer.uploaded.isEmpty(), "an idempotent replay must not re-upload")
        assertTrue(remote.completions.isEmpty(), "and must not re-commit")
    }

    @Test
    fun `a rejected transfer never commits`() = runBlocking {
        val (uploader, remote, _) = uploader(transfer = FakeObjectTransfer(status = 403))
        assertFailsWith<UploadException> { uploader.upload(source(), vaultKey) }
        assertTrue(remote.completions.isEmpty(), "a failed PUT must not produce a commit")
    }

    @Test
    fun `an empty file is refused before contacting the server`() = runBlocking {
        val (uploader, remote, transfer) = uploader()
        val empty = LocalSource("ref", "empty.png", "image/png", null) { ByteArray(0) }
        assertFailsWith<UploadException> { uploader.upload(empty, vaultKey) }
        assertTrue(remote.requests.isEmpty())
        assertTrue(transfer.uploaded.isEmpty())
    }

    @Test
    fun `the client ref is stable for an untouched file and changes once it is edited`() {
        val a = PhotoUploader.clientRefForMedia(mediaId = 42, sizeBytes = 237_842, dateModifiedMs = 1_700_000_000_000)
        val sameSecond = PhotoUploader.clientRefForMedia(42, 237_842, 1_700_000_000_999)
        val edited = PhotoUploader.clientRefForMedia(42, 237_842, 1_700_000_060_000)
        val resized = PhotoUploader.clientRefForMedia(42, 237_900, 1_700_000_000_000)
        val otherPhoto = PhotoUploader.clientRefForMedia(43, 237_842, 1_700_000_000_000)

        assertEquals(a, sameSecond, "a retry must reuse the ref, or it would duplicate the asset")
        assertTrue(a != edited, "an edited photo must be backed up again")
        assertTrue(a != resized, "a different size is a different file")
        assertTrue(a != otherPhoto, "a different photo is a different asset")
    }

    // ---- display derivatives (what the gallery draws before the original is fetched) ----

    private fun derivatives(): Derivatives = Derivatives(
        thumbnail = Derivative(ByteArray(300) { (it % 97).toByte() }, 512, 384),
        preview = Derivative(ByteArray(900) { (it % 89).toByte() }, 1600, 1200),
        width = 4000,
        height = 3000,
    )

    private fun sourceWith(ref: String, derive: suspend () -> Derivatives?) = LocalSource(
        clientRef = ref,
        fileName = "IMG_0002.jpg",
        mimeType = "image/jpeg",
        capturedAtMs = 1_700_000_000_000,
        readBytes = { plaintext },
        readDerivatives = derive,
    )

    @Test
    fun `derivatives are reserved, uploaded and committed as their own resources`() = runBlocking {
        val (uploader, remote, transfer) = uploader()
        val derivs = derivatives()
        uploader.upload(sourceWith("android:42:derivs") { derivs }, vaultKey)

        // All three resources are declared in the same session, so the server knows every size up
        // front and can reject a completion that disagrees with it.
        val reserved = remote.requests.single().encryptedSizes
        assertEquals(setOf("original", "manifest", "preview", "thumbnail"), reserved.keys)
        assertEquals(derivs.preview!!.jpeg.size + 41L, reserved.getValue("preview"))
        assertEquals(derivs.thumbnail!!.jpeg.size + 41L, reserved.getValue("thumbnail"))

        // One object per variant, original first.
        val sent = transfer.uploaded.map { it.first.substringAfterLast('/') }
        assertEquals(listOf("original", "preview", "thumbnail"), sent)

        val completion = remote.completions.single()
        assertEquals(
            listOf("original", "preview", "thumbnail"),
            completion.resources.map { it.variant },
        )
    }

    @Test
    fun `the derivatives decrypt under the same asset key as the original`() = runBlocking {
        val (uploader, remote, transfer) = uploader()
        val derivs = derivatives()
        uploader.upload(sourceWith("android:42:derivs-key") { derivs }, vaultKey)

        val completion = remote.completions.single()
        val assetKey = Crypto.unwrapAssetKey(sodium, completion.keyEnvelope, vaultKey)
        val byVariant = transfer.uploaded.associate { it.first.substringAfterLast('/') to it.second }

        assertContentEquals(
            derivs.preview!!.jpeg,
            Crypto.decryptPayloadBytes(sodium, byVariant.getValue("preview"), assetKey),
        )
        assertContentEquals(
            derivs.thumbnail!!.jpeg,
            Crypto.decryptPayloadBytes(sodium, byVariant.getValue("thumbnail"), assetKey),
        )
        // The original still decrypts too: one key covers every resource of the asset.
        assertContentEquals(
            plaintext,
            Crypto.decryptPayloadBytes(sodium, byVariant.getValue("original"), assetKey),
        )
    }

    @Test
    fun `the manifest tells other clients what the derivatives are`() = runBlocking {
        val (uploader, remote, _) = uploader()
        uploader.upload(sourceWith("android:42:derivs-manifest") { derivatives() }, vaultKey)

        val completion = remote.completions.single()
        val assetKey = Crypto.unwrapAssetKey(sodium, completion.keyEnvelope, vaultKey)
        val json = JSONObject(
            String(
                Crypto.decryptPayload(
                    sodium,
                    EncryptedPayload(completion.manifest.headerB64, completion.manifest.blobB64),
                    assetKey,
                ),
                Charsets.UTF_8,
            ),
        )

        assertTrue(json.getBoolean("previewAvailable"))
        assertEquals(4000, json.getInt("width"))
        assertEquals(3000, json.getInt("height"))
    }

    @Test
    fun `a photo the device cannot decode is still backed up, without a preview`() = runBlocking {
        val (uploader, remote, transfer) = uploader()
        uploader.upload(sourceWith("android:42:undecodable") { Derivatives.NONE }, vaultKey)

        assertEquals(setOf("original", "manifest"), remote.requests.single().encryptedSizes.keys)
        assertEquals(listOf("original"), transfer.uploaded.map { it.first.substringAfterLast('/') })

        val completion = remote.completions.single()
        assertEquals(listOf("original"), completion.resources.map { it.variant })
        val assetKey = Crypto.unwrapAssetKey(sodium, completion.keyEnvelope, vaultKey)
        val json = JSONObject(
            String(
                Crypto.decryptPayload(
                    sodium,
                    EncryptedPayload(completion.manifest.headerB64, completion.manifest.blobB64),
                    assetKey,
                ),
                Charsets.UTF_8,
            ),
        )
        assertFalse(json.getBoolean("previewAvailable"))
        assertTrue(json.isNull("width"))
    }

    @Test
    fun `a decoder blowing up never costs the user the photo`() = runBlocking {
        val (uploader, remote, transfer) = uploader()
        val result = uploader.upload(
            sourceWith("android:42:decoder-throws") { throw IllegalStateException("decoder failed") },
            vaultKey,
        )

        assertEquals(1, transfer.uploaded.size)
        assertEquals(listOf("original"), remote.completions.single().resources.map { it.variant })
        assertEquals(Crypto.sha256Hex(plaintext), result.originalSha256)
    }
}

