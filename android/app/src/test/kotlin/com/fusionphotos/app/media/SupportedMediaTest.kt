package com.fusionphotos.app.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The backed-up format set is a promise from the plan: "JPEG, HEIC/HEIF, PNG, and supported
 * RAW/DNG files". These tests pin it, including the awkward case where Android reports
 * `application/octet-stream` for RAW files.
 */
class SupportedMediaTest {

    @Test
    fun `supported image mime types are accepted`() {
        listOf(
            "image/jpeg", "image/jpg", "image/heic", "image/heif",
            "image/png", "image/webp", "image/avif", "image/tiff", "image/x-adobe-dng",
        ).forEach { mime ->
            assertTrue(SupportedMedia.isSupported(mime, "IMG_1"), "expected $mime to be supported")
        }
    }

    @Test
    fun `octet-stream falls back to the file extension`() {
        // Some providers report RAW as application/octet-stream.
        assertTrue(SupportedMedia.isSupported("application/octet-stream", "IMG_1234.DNG"))
        assertTrue(SupportedMedia.isSupported("", "IMG_1234.heic"))
        assertFalse(SupportedMedia.isSupported("application/octet-stream", "notes.txt"))
        assertFalse(SupportedMedia.isSupported(null, "archive.zip"))
    }

    @Test
    fun `a non-image mime is trusted over a misleading extension`() {
        assertFalse(SupportedMedia.isSupported("text/plain", "photo.jpg"))
        assertFalse(SupportedMedia.isSupported("video/mp4", "clip.mp4"))
    }

    @Test
    fun `extension parsing is case-insensitive and tolerant`() {
        assertEquals("dng", SupportedMedia.extensionOf("IMG_9.DNG"))
        assertEquals("jpeg", SupportedMedia.extensionOf("a.b.jpeg"))
        assertEquals("", SupportedMedia.extensionOf("noextension"))
    }

    @Test
    fun `the default camera roll is DCIM except screenshots`() {
        assertTrue(SupportedMedia.isCameraRoll("DCIM/Camera/IMG_1.jpg"))
        assertTrue(SupportedMedia.isCameraRoll("dcim/100ANDRO/IMG_1.jpg"))
        assertFalse(SupportedMedia.isCameraRoll("DCIM/Screenshots/S1.png"))
        assertFalse(SupportedMedia.isCameraRoll("Pictures/IMG_1.jpg"))
        assertFalse(SupportedMedia.isCameraRoll("Download/IMG_1.jpg"))
        assertFalse(SupportedMedia.isCameraRoll(null))
    }

    @Test
    fun `screenshots are detected by path or by name`() {
        assertTrue(SupportedMedia.isScreenshot("Pictures/Screenshots/S1.png", "S1.png"))
        assertTrue(SupportedMedia.isScreenshot("Pictures/", "Screenshot_20240101.png"))
        assertFalse(SupportedMedia.isScreenshot("DCIM/Camera/IMG_1.jpg", "IMG_1.jpg"))
    }
}
