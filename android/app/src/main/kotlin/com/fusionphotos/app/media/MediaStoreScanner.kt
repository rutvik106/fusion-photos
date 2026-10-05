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
    /** Changes whenever the file is written, which is what makes the backup ref sound. */
    val dateModifiedMs: Long,
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
        MediaStore.Images.Media.DATE_MODIFIED,
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
            val modifiedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
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
                    // MediaStore reports DATE_MODIFIED in seconds; keep it in ms for uniform
                    // reasoning with the other timestamps.
                    dateModifiedMs = if (modifiedCol >= 0) cursor.getLong(modifiedCol) * 1000 else 0L,
                    relativePath = path,
                )
            }
        }
        return out
    }

    /** The runtime permission this platform needs to read the photo library. */
    fun readImagesPermission(): String = readImagesPermission(Build.VERSION.SDK_INT)

    /**
     * The permission Android 10+ requires before MediaProvider will hand back a media file's
     * *location* metadata. Without it the provider zeroes GPS values on the way out, so a backup
     * taken without it stores photos with their location stripped — the tag structure survives,
     * which is exactly why this is easy to miss.
     */
    fun locationPermission(): String? = locationPermission(Build.VERSION.SDK_INT)

    /** Everything to ask for when the user taps "Scan camera roll". */
    fun requiredPermissions(): Array<String> = requiredPermissions(Build.VERSION.SDK_INT)

    // The SDK-level rules are pure functions of the API level so they can be unit-tested on the
    // JVM, where `Build.VERSION.SDK_INT` is not the device's.

    fun readImagesPermission(sdkInt: Int): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun locationPermission(sdkInt: Int): String? =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        } else {
            null
        }

    fun requiredPermissions(sdkInt: Int): Array<String> =
        listOfNotNull(readImagesPermission(sdkInt), locationPermission(sdkInt)).toTypedArray()
}
