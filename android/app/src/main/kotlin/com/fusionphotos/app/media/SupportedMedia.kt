package com.fusionphotos.app.media

/**
 * Which local files this app will back up, and where "the camera roll" ends.
 *
 * Pure rules (no Android types) so they are unit-testable and so the *promise* is explicit:
 * the plan includes "JPEG, HEIC/HEIF, PNG, and supported RAW/DNG files" and deliberately
 * excludes arbitrary documents. Anything not recognised is skipped, never silently
 * re-encoded.
 */
object SupportedMedia {

    /** MIME types we back up byte-for-byte, original quality. */
    private val SUPPORTED_MIME = setOf(
        "image/jpeg",
        "image/jpg",
        "image/heic",
        "image/heif",
        "image/png",
        "image/webp",
        "image/avif",
        "image/tiff",
        "image/dng",
        "image/x-adobe-dng",
        "image/x-canon-cr2",
        "image/x-canon-cr3",
        "image/x-nikon-nef",
        "image/x-sony-arw",
        "image/x-fuji-raf",
    )

    /**
     * Some providers (and some Android versions) hand back `application/octet-stream` for RAW
     * files, so the extension is the fallback signal. Both must agree with the plan's list.
     */
    private val SUPPORTED_EXT = setOf(
        "jpg", "jpeg", "heic", "heif", "png", "webp", "avif", "tif", "tiff",
        "dng", "cr2", "cr3", "nef", "arw", "raf",
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isSupported(mimeType: String?, displayName: String?): Boolean {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        if (mime in SUPPORTED_MIME) return true
        // Only fall back to the name when the provider gave us nothing useful.
        if (mime.isNotEmpty() && mime != "application/octet-stream" && !mime.startsWith("image/")) {
            return false
        }
        return extensionOf(displayName.orEmpty()) in SUPPORTED_EXT
    }

    /**
     * Whether a relative path belongs to the camera roll by default. Android exposes the
     * camera roll as `DCIM/Camera` (and `DCIM/<App>` on some OEMs); Screenshots are a
     * separate bucket the user opts into, per the plan's "default to camera photos".
     */
    fun isCameraRoll(relativePath: String?): Boolean {
        val path = relativePath?.replace('\\', '/')?.lowercase().orEmpty()
        if (!path.startsWith("dcim/")) return false
        return "screenshot" !in path
    }

    fun isScreenshot(relativePath: String?, displayName: String?): Boolean {
        val path = relativePath?.lowercase().orEmpty()
        if ("screenshot" in path) return true
        return displayName?.lowercase()?.startsWith("screenshot") == true
    }
}
