package com.curveon.phone

import android.graphics.Bitmap
import android.graphics.Color
import com.curveon.groups.QrCodec

/** The invite as a QR code, black on white with a quiet border so a phone camera reads it from across the table. */
object QrCode {
    fun bitmap(text: String, size: Int = 640): Bitmap? {
        val m = QrCodec.encode(text, size) ?: return null
        val pixels = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** The text of a QR code in the camera's greyscale picture, or null. */
    fun read(luma: ByteArray, width: Int, height: Int): String? = QrCodec.decode(luma, width, height)
}
