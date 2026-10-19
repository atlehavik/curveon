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
 * The rider's own picture for the group: whatever photo they pick, cut to a square, turned the right way up, and
 * made small (96 px, at most about 8 KB) because every rider in the group gets it with the profile.
 */
object ProfilePhoto {
    private const val SIZE = 96
    private const val MAX_BYTES = 8_000

    /** The picture as base64 JPEG for [com.curveon.protocol.Message.GroupProfile], or null if it cannot be read. */
    fun fromUri(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        // Read the size first, so a 12-megapixel photo is not loaded whole just to become 96 pixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE * 2 && bounds.outHeight / (sample * 2) >= SIZE * 2) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val rotation = resolver.openInputStream(uri)?.use { rotationOf(ExifInterface(it)) } ?: 0f
        encode(square(decoded, rotation))
    }.getOrNull()

    private fun rotationOf(exif: ExifInterface) = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    /** The middle square of [src], turned by [rotation] degrees, scaled to [SIZE]. */
    internal fun square(src: Bitmap, rotation: Float): Bitmap {
        val turned = if (rotation == 0f) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation) }, true)
        val side = minOf(turned.width, turned.height)
        val cut = Bitmap.createBitmap(turned, (turned.width - side) / 2, (turned.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(cut, SIZE, SIZE, true)
    }

    /** JPEG at the best quality that still fits the limit. */
    internal fun encode(bitmap: Bitmap): String? {
        for (quality in intArrayOf(85, 70, 55, 40, 30)) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_BYTES) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        return null
    }
}
