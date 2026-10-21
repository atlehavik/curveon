package com.curveon.groups

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR codes for invites and for the link to a rider's other device: drawn for a friend's camera, and read from
 * the camera's picture inside the app. ZXing's core is plain Java, with no Play services and nothing to download.
 */
object QrCodec {
  /** A square of dark and light modules, [size] pixels a side, with a quiet border. */
  class Matrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int) = dark[y * size + x]
  }

  fun encode(text: String, size: Int = 640): Matrix? = runCatching {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    Matrix(size, BooleanArray(size * size) { i -> m[i % size, i / size] })
  }.getOrNull()

  /**
   * The text of a QR code in a greyscale picture ([luma]: one byte per pixel, row after row, 0 black to 255 white),
   * or null if there is none to read. The code can sit at any angle.
   */
  fun decode(luma: ByteArray, width: Int, height: Int): String? = try {
    val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
  } catch (_: ReaderException) {
    null
  }
}
