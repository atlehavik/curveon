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
     * The photo at [uri], turned the right way up and not larger than [maxSide] pixels (so a 12-megapixel photo is not
     * held whole), ready to be cut. Null if it cannot be read.
     *
     * With [mirrorFrontCamera], a photo taken with the front camera is turned left to right. The camera app shows the
     * rider the mirror view while framing a selfie but saves the picture the other way round, and the rider then
     * sees a face that is not the one they just looked at: a selfie must feel like the selfie they took.
     */
    fun load(context: Context, uri: Uri, maxSide: Int = 1280, mirrorFrontCamera: Boolean = false): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) }
        val rotation = exif?.let { rotationOf(it) } ?: 0f
        val mirror = mirrorFrontCamera && exif != null && isFrontCamera(exif)
        if (rotation == 0f && !mirror) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply {
            postRotate(rotation)
            if (mirror) postScale(-1f, 1f) // after the turn: left to right in the picture the rider sees
        }, true)
    }.getOrNull()

    /** The camera apps name the lens in the file ("... front camera 2.2mm f/2.2"). */
    private fun isFrontCamera(exif: ExifInterface) =
        exif.getAttribute("LensModel")?.contains("front", ignoreCase = true) == true

    private fun rotationOf(exif: ExifInterface) = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
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
