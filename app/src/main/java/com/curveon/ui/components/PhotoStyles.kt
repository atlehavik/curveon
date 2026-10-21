package com.curveon.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.curveon.R
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What a profile picture can look like once the rider is cut out of it. */
enum class PhotoStyle(@StringRes val label: Int) {
    PLAIN(R.string.group_photo_style_plain),
    STICKER(R.string.group_photo_style_sticker),
    NEON(R.string.group_photo_style_neon),
    SPEED(R.string.group_photo_style_speed),
}

/** Cutting the rider (or the dog) out of a photo, and drawing them on a new background in a [PhotoStyle]. */
object PhotoStyles {
    /**
     * The photo's subject on a see-through background, the same size as [photo]; null when there is none or this
     * phone can't (no Google Play services, or no network to fetch its model). On the phone itself: nothing is sent anywhere.
     * While Google Play services is still fetching the model (the first time) it is asked again for half a minute.
     */
    suspend fun findSubject(photo: Bitmap): Bitmap? = runCatching {
        val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        try {
            var found: Result<Bitmap?> = Result.success(null)
            for (attempt in 0 until 10) {
                found = suspendCancellableCoroutine { cont ->
                    segmenter.process(InputImage.fromBitmap(photo, 0))
                        .addOnSuccessListener { cont.resume(Result.success(it.foregroundBitmap)) }
                        .addOnFailureListener { cont.resume(Result.failure(it)) }
                }
                val fetching = (found.exceptionOrNull() as? MlKitException)?.errorCode == MlKitException.UNAVAILABLE
                if (!fetching) break
                kotlinx.coroutines.delay(3_000)
            }
            found.getOrNull()
        } finally {
            segmenter.close()
        }
    }.getOrNull()?.takeIf { covers(it) }

    /** Something worth styling was found: at least a few percent of the photo. */
    private fun covers(subject: Bitmap): Boolean {
        val step = maxOf(1, minOf(subject.width, subject.height) / 64)
        var seen = 0; var all = 0
        for (y in 0 until subject.height step step) for (x in 0 until subject.width step step) {
            all++
            if ((subject.getPixel(x, y) ushr 24) > 128) seen++
        }
        return all > 0 && seen * 100 / all >= 3
    }

    /** [photo] redrawn in [style], [subject] (from [findSubject]) in front; [colour] is the rider's colour. */
    fun render(photo: Bitmap, subject: Bitmap, style: PhotoStyle, colour: Int): Bitmap {
        if (style == PhotoStyle.PLAIN) return photo
        val w = photo.width; val h = photo.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val unit = minOf(w, h).toFloat()
        val alpha = subject.extractAlpha()
        // Where the subject is: the background is drawn round it.
        val (cx, cy) = middleOf(alpha)
        val reach = hypot(maxOf(cx, w - cx), maxOf(cy, h - cy))
        val light = ColorUtils.blendARGB(colour, android.graphics.Color.WHITE, 0.45f)
        val dark = ColorUtils.blendARGB(colour, android.graphics.Color.BLACK, 0.55f)
        when (style) {
            PhotoStyle.STICKER -> {
                canvas.drawPaint(Paint().apply { shader = RadialGradient(cx, cy, reach, light, dark, Shader.TileMode.CLAMP) })
                edge(canvas, alpha, unit * 0.045f, android.graphics.Color.WHITE)
            }
            PhotoStyle.NEON -> {
                canvas.drawColor(0xFF0B0E14.toInt())
                canvas.drawPaint(Paint().apply { shader = RadialGradient(cx, cy, reach, ColorUtils.setAlphaComponent(colour, 90), 0, Shader.TileMode.CLAMP) })
                glow(canvas, subject, unit * 0.06f, colour)
                glow(canvas, subject, unit * 0.02f, light)
                edge(canvas, alpha, unit * 0.012f, light)
            }
            PhotoStyle.SPEED -> {
                canvas.drawColor(dark)
                speedLines(canvas, cx, cy, reach, unit, light)
                edge(canvas, alpha, unit * 0.05f, android.graphics.Color.BLACK)
                edge(canvas, alpha, unit * 0.03f, android.graphics.Color.WHITE)
            }
            PhotoStyle.PLAIN -> Unit
        }
        canvas.drawBitmap(subject, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        alpha.recycle()
        return out
    }

    /** The middle of what [alpha] covers, sampled. */
    private fun middleOf(alpha: Bitmap): Pair<Float, Float> {
        val step = maxOf(1, minOf(alpha.width, alpha.height) / 96)
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 0 until alpha.height step step) for (x in 0 until alpha.width step step) {
            if ((alpha.getPixel(x, y) ushr 24) > 128) { sx += x; sy += y; n++ }
        }
        return if (n == 0) alpha.width / 2f to alpha.height / 2f else (sx / n).toFloat() to (sy / n).toFloat()
    }

    /** A solid edge [width] wide round the subject: its outline drawn many times round a circle. */
    private fun edge(canvas: Canvas, alpha: Bitmap, width: Float, colour: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = colour }
        val steps = 32
        for (r in listOf(width, width * 0.6f, width * 0.3f)) for (i in 0 until steps) {
            val a = 2 * Math.PI * i / steps
            canvas.drawBitmap(alpha, (cos(a) * r).toFloat(), (sin(a) * r).toFloat(), paint)
        }
    }

    /** A soft light [radius] round the subject. */
    private fun glow(canvas: Canvas, subject: Bitmap, radius: Float, colour: Int) {
        val at = IntArray(2)
        val blurred = subject.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER) }, at)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = colour }
        repeat(2) { canvas.drawBitmap(blurred, at[0].toFloat(), at[1].toFloat(), paint) }
        blurred.recycle()
    }

    /** Streaks rushing out from behind the subject, like a comic drawing of speed. */
    private fun speedLines(canvas: Canvas, cx: Float, cy: Float, reach: Float, unit: Float, colour: Int) {
        val random = java.util.Random(7)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; style = Paint.Style.FILL }
        val path = android.graphics.Path()
        repeat(56) {
            val a = random.nextDouble() * 2 * Math.PI
            val spread = 0.012 + random.nextDouble() * 0.03
            val start = unit * (0.18f + random.nextFloat() * 0.2f)
            path.reset()
            path.moveTo(cx + (cos(a) * start).toFloat(), cy + (sin(a) * start).toFloat())
            path.lineTo(cx + (cos(a - spread) * reach * 1.1).toFloat(), cy + (sin(a - spread) * reach * 1.1).toFloat())
            path.lineTo(cx + (cos(a + spread) * reach * 1.1).toFloat(), cy + (sin(a + spread) * reach * 1.1).toFloat())
            path.close()
            paint.alpha = 120 + random.nextInt(135)
            canvas.drawPath(path, paint)
        }
    }
}
