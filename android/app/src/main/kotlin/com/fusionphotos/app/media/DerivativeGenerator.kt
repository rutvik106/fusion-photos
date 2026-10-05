package com.fusionphotos.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One JPEG small enough to draw in a grid or viewer without fetching the original. */
class Derivative(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * The two display derivatives, plus the size of the decoded original.
 *
 * A null pair means the format could not be decoded on this device (HEIC on an old decoder, an
 * unusual RAW). That is not a failure: the original is still backed up and the manifest records
 * `previewAvailable: false`, which is how the UI can say so plainly instead of showing a blank tile.
 */
class Derivatives(
    val thumbnail: Derivative?,
    val preview: Derivative?,
    val width: Int?,
    val height: Int?,
) {
    val available: Boolean get() = thumbnail != null && preview != null

    companion object {
        val NONE = Derivatives(null, null, null, null)
    }
}

/**
 * Generates the local derivatives the gallery needs, from the original bytes, on the device.
 *
 * The constants mirror the web client (`web/src/lib/images.ts`) because both clients write the
 * same manifest and must agree on what a "thumbnail" is; a reader must not have to know which
 * client produced a library.
 *
 * Two Android details are load-bearing:
 *  - **Orientation.** The derivatives are what people actually look at, so a sideways thumbnail is
 *    worse than no thumbnail. `ImageDecoder` (API 28+) bakes the EXIF orientation in and reports
 *    the oriented size; below that the rotation comes from MediaStore's `ORIENTATION` column,
 *    because `BitmapFactory` ignores EXIF entirely.
 *  - **Memory.** A 12 MP photo is ~48 MB decoded. Decoding straight to preview size (via a sample
 *    size or a target size) keeps peak memory near the preview rather than the original, and the
 *    thumbnail is scaled from the already-decoded preview instead of decoding the file twice.
 */
object DerivativeGenerator {
    const val THUMBNAIL = "thumbnail"
    const val PREVIEW = "preview"

    const val THUMBNAIL_MAX_PX = 512
    const val PREVIEW_MAX_PX = 1600
    const val JPEG_QUALITY = 82

    /** Long edge after scaling to fit [maxPx] within a box; never upscales. Pure. */
    fun scaledSize(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxPx <= 0) return 0 to 0
        val longEdge = maxOf(width, height)
        if (longEdge <= maxPx) return width to height
        val scale = maxPx.toDouble() / longEdge
        return maxOf(1, (width * scale + 0.5).toInt()) to maxOf(1, (height * scale + 0.5).toInt())
    }

    /**
     * Largest power of two that keeps the decoded long edge at or above [maxPx]. Pure.
     *
     * Deliberately errs towards a *larger* decode: a smaller one would have to be upscaled to hit
     * the preview size, which is exactly the quality loss the preview exists to avoid.
     */
    fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        if (width <= 0 || height <= 0 || maxPx <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    /** MediaStore's rotation value (0/90/180/270) as degrees. Pure. */
    fun rotationForOrientation(orientation: Int): Int = when (orientation) {
        90, 180, 270 -> orientation
        else -> 0
    }

    /** Best effort by design: an undecodable file is backed up without derivatives. */
    suspend fun generate(context: Context, uri: Uri): Derivatives = withContext(Dispatchers.Default) {
        runCatching { generateOrThrow(context, uri) }.getOrDefault(Derivatives.NONE)
    }

    private fun generateOrThrow(context: Context, uri: Uri): Derivatives {
        val decoded = decode(context, uri) ?: return Derivatives.NONE
        try {
            val previewBitmap = fit(decoded, PREVIEW_MAX_PX)
            val thumbnailBitmap = fit(previewBitmap, THUMBNAIL_MAX_PX)
            val preview = encode(previewBitmap)
            val thumbnail = encode(thumbnailBitmap)
            if (thumbnailBitmap !== previewBitmap) thumbnailBitmap.recycle()
            if (previewBitmap !== decoded) previewBitmap.recycle()
            return Derivatives(
                thumbnail = thumbnail,
                preview = preview,
                width = decoded.width,
                height = decoded.height,
            )
        } finally {
            decoded.recycle()
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software allocation: a hardware bitmap cannot be scaled or JPEG-encoded.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = scaledSize(info.size.width, info.size.height, PREVIEW_MAX_PX)
                if (w > 0 && h > 0) decoder.setTargetSize(w, h)
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_MAX_PX)
        }
        val raw = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val rotation = rotationForOrientation(orientation(context, uri))
        if (rotation == 0) return raw

        val rotated = Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true,
        )
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /** MediaStore is the orientation source below API 28, where ImageDecoder does it for us. */
    private fun orientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.Images.Media.ORIENTATION), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 } ?: 0
    }.getOrDefault(0)

    private fun fit(bitmap: Bitmap, maxPx: Int): Bitmap {
        val (w, h) = scaledSize(bitmap.width, bitmap.height, maxPx)
        return if (w == bitmap.width && h == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
    }

    private fun encode(bitmap: Bitmap): Derivative {
        val out = ByteArrayOutputStream()
        // JPEG for both derivatives, matching the web client: re-encoding is acceptable *here*
        // (these are display copies), and never for the original.
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Derivative(out.toByteArray(), bitmap.width, bitmap.height)
    }
}
