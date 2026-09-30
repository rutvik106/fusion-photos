package com.fusionphotos.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** One image discovered in the device's media library. */
data class LocalPhoto(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
    val relativePath: String,
)

/**
 * Enumerates the images this app may back up.
 *
 * Default scope is the **camera roll** (the plan: "Default to camera photos; users can
 * include additional albums or screenshots"). Formats outside the supported set are skipped
 * rather than re-encoded, so the app never silently changes a photo.
 */
object MediaStoreScanner {

    private val PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_TAKEN,
        // RELATIVE_PATH only exists from API 29; older devices fall back to no path filter.
        MediaStore.Images.Media.RELATIVE_PATH,
    )

    fun scan(context: Context, includeScreenshots: Boolean = false): List<LocalPhoto> {
        val out = mutableListOf<LocalPhoto>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        context.contentResolver.query(collection, PROJECTION, null, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            if (idCol < 0 || nameCol < 0) return@use

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                if (!SupportedMedia.isSupported(mime, name)) continue

                // On API < 29 there is no RELATIVE_PATH, so an unknown path must not cause a
                // silent skip — include it (the user can still deselect later).
                val knownPath = path.isNotEmpty()
                if (knownPath && !includeScreenshots && !SupportedMedia.isCameraRoll(path)) continue
                if (knownPath && includeScreenshots && !SupportedMedia.isCameraRoll(path) &&
                    !SupportedMedia.isScreenshot(path, name)
                ) {
                    continue
                }

                out += LocalPhoto(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = name,
                    mimeType = mime.orEmpty(),
                    sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    dateTakenMs = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                    relativePath = path,
                )
            }
        }
        return out
    }

    /** The runtime permission this platform needs to read the photo library. */
    fun readImagesPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
}
