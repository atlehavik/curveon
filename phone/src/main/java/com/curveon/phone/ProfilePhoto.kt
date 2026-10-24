package com.curveon.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * The rider's own picture for the group, in two steps: [load] a photo (taken with the camera or picked from the
 * rider's pictures) so the rider can cut it, and [encode] the cut square as a small JPEG, because every rider in
 * the group gets it with the profile.
 */
object ProfilePhoto {
    /** What every rider's picture is made as. */
    const val SIZE = 192
    private const val MAX_BYTES = 24_000

    /**
     * The photo at [uri], turned the right way up (and left to right where its EXIF orientation says so: the selfie
     * camera in Curveon saves the picture as the rider saw it, which for the front camera is mirrored) and not larger
     * than [maxSide] pixels (so a 12-megapixel photo is not held whole), ready to be cut. Null if it cannot be read.
     */
    fun load(context: Context, uri: Uri, maxSide: Int = 1280): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val orientation = resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
        val turn = matrixFor(orientation) ?: return decoded
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, turn, true)
    }.getOrNull()

    /** What turns a picture with this EXIF orientation upright; null if it already is. */
    private fun matrixFor(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
        else -> null
    }

    /** The cut square as base64 JPEG for [com.curveon.protocol.Message.GroupProfile]: [SIZE] pixels, at most about 24 KB. Null if it will not fit. */
    fun encode(square: Bitmap): String? {
        val small = if (square.width == SIZE && square.height == SIZE) square else Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        for (quality in intArrayOf(85, 70, 55, 40, 30)) {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_BYTES) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        return null
    }
}
