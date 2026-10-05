package com.fusionphotos.app.media

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a photo's bytes from MediaStore.
 *
 * The bytes are the file *as the platform will hand it over* — no re-encoding, no scaling, no
 * EXIF stripping by us. That last point is subtler than it looks: on Android 10+ MediaProvider
 * itself zeroes a media file's location metadata for apps that do not hold
 * `ACCESS_MEDIA_LOCATION`, so reading the plain URI loses GPS without any code here doing
 * anything. `setRequireOriginal` opts out of that redaction and needs the permission, hence
 * [useOriginalRequest]. (This was found by decrypting a real device upload and diffing EXIF
 * against the file on the phone; the difference was 34 bytes of GPS values.)
 */
object MediaBytes {
    /**
     * Whether to ask MediaStore for the unredacted original. Pure, so the rule is testable.
     */
    fun useOriginalRequest(sdkInt: Int, hasLocationPermission: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.Q && hasLocationPermission

    suspend fun read(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val target = originalUri(context, uri)
        context.contentResolver.openInputStream(target)?.use { it.readBytes() }
            ?: throw IllegalStateException("cannot open $uri (revoked permission or missing file)")
    }

    /**
     * [MediaStore.setRequireOriginal] throws when the permission is missing or the URI is not a
     * media file, and neither is worth failing an upload over: fall back to the redacted read so
     * the photo is still backed up, just without its location.
     */
    private fun originalUri(context: Context, uri: Uri): Uri {
        if (!useOriginalRequest(Build.VERSION.SDK_INT, hasLocationPermission(context))) return uri
        return runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}
