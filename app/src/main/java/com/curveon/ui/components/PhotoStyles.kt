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
    POP(R.string.group_photo_style_pop),
    GLITCH(R.string.group_photo_style_glitch),
    THERMAL(R.string.group_photo_style_thermal),
    TRIPPY(R.string.group_photo_style_trippy),
}

/** Cutting the rider (or the dog) out of a photo, and drawing them on a new background in a [PhotoStyle]. */
object PhotoStyles {
    /**
     * The photo's subject on a see-through background, the same size as [photo]; null when there is none or this
     * phone can't (no Google Play services, or no network to fetch its model). On the phone itself: nothing is sent anywhere.
     * While Google Play services is still fetching the model (the first time) it is asked again for half a minute.
     */
    suspend fun findSubject(photo: Bitmap): Bitmap? = runCatching {
        // Never closed: closing it crashed the whole app from ML Kit's own thread (2026-10-22, "Tensor should have 2, 3, or 4
        // dims" while releasing the model), where nothing here can catch it. One for as long as the app runs is also quicker.
        val segmenter = segmenter
        // A phone that hangs on it (no GPU, Play services stuck) gets the plain photo instead of a spinner for ever.
        kotlinx.coroutines.withTimeoutOrNull(40_000) {
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
        }
    }.getOrNull()?.takeIf { covers(it) }

    private val segmenter by lazy { SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()) }

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
        // The crazy ones change the rider too, and draw their own version in front.
        var front: Bitmap? = subject
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
            PhotoStyle.POP -> {
                // Warhol: flat background with big halftone dots, the rider in three flat inks with a fat black edge.
                canvas.drawColor(light)
                halftone(canvas, w, h, unit * 0.035f, dark)
                edge(canvas, alpha, unit * 0.04f, android.graphics.Color.BLACK)
                front = recolour(subject) { r, g, b ->
                    val l = luma(r, g, b)
                    when {
                        l < 0.33f -> 0xFF111111.toInt()
                        l < 0.62f -> 0xFFFF2E88.toInt()
                        else -> 0xFFFFE135.toInt()
                    }
                }
            }
            PhotoStyle.GLITCH -> {
                // A broken screen: the rider split into red and cyan, slices torn sideways, scan lines over it all.
                canvas.drawColor(0xFF07070C.toInt())
                val shift = unit * 0.03f
                val red = recolour(subject) { r, _, _ -> android.graphics.Color.rgb(r, 0, 0) }
                val cyan = recolour(subject) { _, g, b -> android.graphics.Color.rgb(0, g, b) }
                val add = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
                canvas.drawBitmap(red, -shift, 0f, add)
                canvas.drawBitmap(cyan, shift, 0f, add)
                red.recycle(); cyan.recycle()
                tear(canvas, out, unit, colour)
                scanLines(canvas, w, h, unit)
                front = null
            }
            PhotoStyle.THERMAL -> {
                // A heat camera: the rider glows from black through purple and red to yellow, on a cold blue night.
                canvas.drawPaint(Paint().apply { shader = RadialGradient(cx, cy, reach, 0xFF0A1A6B.toInt(), 0xFF020414.toInt(), Shader.TileMode.CLAMP) })
                glow(canvas, subject, unit * 0.05f, 0xFF7B1FA2.toInt())
                front = recolour(subject) { r, g, b -> heat(luma(r, g, b)) }
            }
            PhotoStyle.TRIPPY -> {
                // Far out: a spinning rainbow behind, the rider in turned-inside-out colours with a rainbow halo.
                trippyBackground(canvas, cx, cy, reach, unit)
                glow(canvas, subject, unit * 0.07f, 0xFFFF00FF.toInt())
                glow(canvas, subject, unit * 0.035f, 0xFF00FFFF.toInt())
                glow(canvas, subject, unit * 0.015f, 0xFFFFFF00.toInt())
                front = recolour(subject) { r, g, b ->
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV(255 - r, 255 - g, 255 - b, hsv)
                    hsv[0] = (hsv[0] + 120f) % 360f
                    hsv[1] = minOf(1f, hsv[1] * 1.8f + 0.25f)
                    hsv[2] = minOf(1f, hsv[2] * 1.15f + 0.1f)
                    android.graphics.Color.HSVToColor(hsv)
                }
            }
            PhotoStyle.PLAIN -> Unit
        }
        front?.let {
            canvas.drawBitmap(it, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            if (it !== subject) it.recycle()
        }
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

    /** [subject] with each seen pixel's colour from [paint] (r, g, b in 0..255), its see-through edge kept. */
    private inline fun recolour(subject: Bitmap, paint: (Int, Int, Int) -> Int): Bitmap {
        val w = subject.width; val h = subject.height
        val px = IntArray(w * h)
        subject.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val painted = paint((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            px[i] = (a shl 24) or (painted and 0xFFFFFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun luma(r: Int, g: Int, b: Int) = (0.299f * r + 0.587f * g + 0.114f * b) / 255f

    /** A heat camera's colours: cold black, purple, red, orange, yellow, hot white. */
    private val heatStops = intArrayOf(0xFF000014.toInt(), 0xFF4A0A8C.toInt(), 0xFFD0105A.toInt(), 0xFFFF6A00.toInt(), 0xFFFFD400.toInt(), 0xFFFFFFE0.toInt())

    private fun heat(t: Float): Int {
        // Lifted a little, so even a dark jacket is warm: a person is never as cold as the night.
        val v = (0.18f + t * 0.95f).coerceIn(0f, 1f) * (heatStops.size - 1)
        val i = minOf(v.toInt(), heatStops.size - 2)
        return ColorUtils.blendARGB(heatStops[i], heatStops[i + 1], v - i)
    }

    /** Big dots in rows, like a cheap comic print. */
    private fun halftone(canvas: Canvas, w: Int, h: Int, step: Float, colour: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
        var row = 0
        var y = 0f
        while (y < h + step) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < w + step) { canvas.drawCircle(x, y, step * 0.32f, paint); x += step }
            y += step * 0.87f; row++
        }
    }

    /** Bands of the picture torn sideways, and a few bars of broken colour. */
    private fun tear(canvas: Canvas, picture: Bitmap, unit: Float, colour: Int) {
        val random = java.util.Random(13)
        val copy = picture.copy(Bitmap.Config.ARGB_8888, false)
        val paint = Paint()
        repeat(9) {
            val top = random.nextInt(picture.height)
            val tall = (unit * (0.01f + random.nextFloat() * 0.05f)).toInt().coerceAtLeast(2)
            val dx = (unit * (random.nextFloat() - 0.5f) * 0.16f).toInt()
            val from = android.graphics.Rect(0, top, picture.width, minOf(picture.height, top + tall))
            canvas.drawBitmap(copy, from, android.graphics.Rect(dx, from.top, dx + picture.width, from.bottom), paint)
        }
        copy.recycle()
        val bars = intArrayOf(0xFFFF00FF.toInt(), 0xFF00FF66.toInt(), colour)
        repeat(5) {
            paint.color = bars[random.nextInt(bars.size)]
            paint.alpha = 110 + random.nextInt(110)
            val top = random.nextFloat() * picture.height
            val left = random.nextFloat() * picture.width * 0.7f
            canvas.drawRect(left, top, left + unit * (0.1f + random.nextFloat() * 0.35f), top + unit * 0.012f, paint)
        }
    }

    /** Dark lines across, like an old screen. */
    private fun scanLines(canvas: Canvas, w: Int, h: Int, unit: Float) {
        val paint = Paint().apply { color = 0x55000000 }
        val step = (unit / 70f).coerceAtLeast(3f)
        var y = 0f
        while (y < h) { canvas.drawRect(0f, y, w.toFloat(), y + step / 2, paint); y += step }
    }

    /** A rainbow wheel with rings running out from behind the rider. */
    private fun trippyBackground(canvas: Canvas, cx: Float, cy: Float, reach: Float, unit: Float) {
        val rainbow = intArrayOf(0xFFFF0040.toInt(), 0xFFFFB000.toInt(), 0xFFB4FF00.toInt(), 0xFF00FFC8.toInt(), 0xFF0070FF.toInt(), 0xFFB000FF.toInt(), 0xFFFF0040.toInt())
        canvas.drawPaint(Paint().apply { shader = android.graphics.SweepGradient(cx, cy, rainbow, null) })
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.035f
            shader = android.graphics.SweepGradient(cx, cy, rainbow.reversedArray(), null)
        }
        var r = unit * 0.05f
        while (r < reach * 1.1f) { canvas.drawCircle(cx, cy, r, ring); r += unit * 0.075f }
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
