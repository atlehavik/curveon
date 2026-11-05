package com.curveon.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.curveon.ui.theme.*
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.*

/** White chevron used (tinted per track) to show a track's direction. */
/** The give way and stop signs on a short pole, for standing on the map ([density]: px per dp). */
internal fun roadSignImages(density: Float): Map<String, android.graphics.Bitmap> {
    val w = (26 * density).toInt()
    val h = (40 * density).toInt()
    val red = android.graphics.Color.rgb(0xD5, 0x2B, 0x1E)
    fun sign(plate: android.graphics.Canvas.(android.graphics.Paint) -> Unit): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // The pole: grey, from under the plate to the ground (the bottom, where the sign stands).
        p.color = android.graphics.Color.rgb(0x8A, 0x93, 0x9E)
        c.drawRect(w / 2f - 1.5f * density, w * 0.6f, w / 2f + 1.5f * density, h.toFloat(), p)
        c.plate(p)
        return bmp
    }
    val giveWay = sign { p ->
        fun tri(inset: Float) = android.graphics.Path().apply {
            val top = w * 0.07f; val ht = w * 0.87f; val cy = top + ht / 3; val k = 1 - inset / (ht / 3)
            moveTo(w / 2f - w / 2f * k, cy + (top - cy) * k)
            lineTo(w / 2f + w / 2f * k, cy + (top - cy) * k)
            lineTo(w / 2f, cy + (top + ht - cy) * k)
            close()
        }
        p.color = android.graphics.Color.WHITE; drawPath(tri(0f), p)
        p.color = red; drawPath(tri(w * 0.05f), p)
        p.color = android.graphics.Color.WHITE; drawPath(tri(w * 0.17f), p)
    }
    val stop = sign { p ->
        fun oct(inset: Float) = android.graphics.Path().apply {
            val c = w / 2f
            val r = (c - inset) / kotlin.math.cos(Math.PI / 8).toFloat()
            for (i in 0 until 8) {
                val a = Math.PI / 8 + i * Math.PI / 4
                val x = c + r * kotlin.math.cos(a).toFloat(); val y = c + r * kotlin.math.sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        p.color = android.graphics.Color.WHITE; drawPath(oct(0f), p)
        p.color = red; drawPath(oct(w * 0.07f), p)
        p.color = android.graphics.Color.WHITE
        p.textAlign = android.graphics.Paint.Align.CENTER; p.textSize = w * 0.28f
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        drawText("STOP", w / 2f, w / 2f + p.textSize * 0.36f, p)
    }
    return mapOf(GIVE_WAY_IMAGE to giveWay, STOP_SIGN_IMAGE to stop)
}

internal fun weatherImageName(kind: com.curveon.data.weather.MapWeather.Kind) = "curveon-weather-${kind.name.lowercase()}"

/** A small weather symbol on a dark disc: a sun, a moon and stars, a cloud, a cloud with rain, or wind lines. */
internal fun weatherImage(kind: com.curveon.data.weather.MapWeather.Kind, density: Float): android.graphics.Bitmap {
    val d = (34 * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(d, d, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = d / 2f
    p.color = android.graphics.Color.argb(215, 0x12, 0x18, 0x20); c.drawCircle(r, r, r, p)
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.5f * density
    p.color = android.graphics.Color.argb(160, 0xFF, 0xFF, 0xFF); c.drawCircle(r, r, r - p.strokeWidth, p)
    p.style = android.graphics.Paint.Style.FILL
    fun cloud(color: Int, cy: Float) {
        p.color = color
        c.drawCircle(r - d * 0.12f, cy, d * 0.13f, p)
        c.drawCircle(r + d * 0.05f, cy - d * 0.06f, d * 0.16f, p)
        c.drawRoundRect(r - d * 0.27f, cy - d * 0.02f, r + d * 0.27f, cy + d * 0.12f, d * 0.07f, d * 0.07f, p)
    }
    when (kind) {
        com.curveon.data.weather.MapWeather.Kind.SUN -> {
            p.color = android.graphics.Color.rgb(0xFF, 0xC1, 0x07)
            c.drawCircle(r, r, d * 0.15f, p)
            p.strokeWidth = 2f * density; p.strokeCap = android.graphics.Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                c.drawLine(r + (d * 0.22f * kotlin.math.cos(a)).toFloat(), r + (d * 0.22f * kotlin.math.sin(a)).toFloat(),
                    r + (d * 0.31f * kotlin.math.cos(a)).toFloat(), r + (d * 0.31f * kotlin.math.sin(a)).toFloat(), p)
            }
        }
        com.curveon.data.weather.MapWeather.Kind.MOON -> {
            p.color = android.graphics.Color.rgb(0xFF, 0xE9, 0xA8)
            val moon = android.graphics.Path().apply { addCircle(r - d * 0.05f, r + d * 0.03f, d * 0.24f, android.graphics.Path.Direction.CW) }
            val bite = android.graphics.Path().apply { addCircle(r + d * 0.06f, r - d * 0.04f, d * 0.2f, android.graphics.Path.Direction.CW) }
            moon.op(bite, android.graphics.Path.Op.DIFFERENCE)
            c.drawPath(moon, p)
            c.drawCircle(r + d * 0.2f, r - d * 0.17f, d * 0.035f, p)
            c.drawCircle(r + d * 0.08f, r + d * 0.03f, d * 0.025f, p)
        }
        com.curveon.data.weather.MapWeather.Kind.CLOUD -> cloud(android.graphics.Color.rgb(0xB8, 0xC2, 0xCC), r + d * 0.02f)
        com.curveon.data.weather.MapWeather.Kind.RAIN -> {
            cloud(android.graphics.Color.rgb(0x9A, 0xA6, 0xB2), r - d * 0.08f)
            p.color = android.graphics.Color.rgb(0x40, 0xA9, 0xFF); p.strokeWidth = 2f * density; p.strokeCap = android.graphics.Paint.Cap.ROUND
            for (x in listOf(-0.14f, 0f, 0.14f)) c.drawLine(r + d * x, r + d * 0.12f, r + d * (x - 0.05f), r + d * 0.27f, p)
        }
        com.curveon.data.weather.MapWeather.Kind.WIND -> {
            p.color = android.graphics.Color.rgb(0xFF, 0x91, 0x00); p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = 2.2f * density; p.strokeCap = android.graphics.Paint.Cap.ROUND
            for ((y, len) in listOf(-0.12f to 0.5f, 0f to 0.36f, 0.12f to 0.44f)) c.drawLine(r - d * 0.25f, r + d * y, r - d * 0.25f + d * len, r + d * y, p)
        }
    }
    return bmp
}

internal fun speedSignImageName(limit: Int) = "curveon-speed-sign-$limit"

/** A speed limit sign on a short pole, as the road signs: red ring, white disc, [limit] in black. */
internal fun speedSignImage(limit: Int, density: Float): android.graphics.Bitmap {
    val w = (30 * density).toInt()
    val h = (46 * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.rgb(0x8A, 0x93, 0x9E)
    c.drawRect(w / 2f - 1.5f * density, w * 0.8f, w / 2f + 1.5f * density, h.toFloat(), p)
    val r = w / 2f
    p.color = android.graphics.Color.rgb(0xD5, 0x2B, 0x1E); c.drawCircle(r, r, r, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(r, r, r * 0.74f, p)
    p.color = android.graphics.Color.BLACK
    p.textAlign = android.graphics.Paint.Align.CENTER
    p.typeface = android.graphics.Typeface.DEFAULT_BOLD
    p.textSize = w * if (limit >= 100) 0.34f else 0.42f
    c.drawText(limit.toString(), r, r + p.textSize * 0.36f, p)
    return bmp
}

/** A rider's picture as a round marker with a white edge and a ring in their colour. */
internal fun ringedAvatar(photo: android.graphics.Bitmap, color: Int, density: Float): android.graphics.Bitmap {
    val d = (44 * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(d, d, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = d / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(r, r, r, paint)
    paint.color = color
    canvas.drawCircle(r, r, r - 3 * density, paint)
    val inner = r - 6 * density
    val size = (inner * 2).toInt().coerceAtLeast(1)
    val shader = android.graphics.BitmapShader(
        android.graphics.Bitmap.createScaledBitmap(photo, size, size, true),
        android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
    )
    shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(r - inner, r - inner) })
    paint.shader = shader
    canvas.drawCircle(r, r, inner, paint)
    return bmp
}

internal fun arrowBitmap(): android.graphics.Bitmap {
    val size = 48
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE
        strokeWidth = 11f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    // Points along +x, which is the line direction for line-placed symbols.
    val path = android.graphics.Path().apply { moveTo(12f, 8f); lineTo(36f, 24f); lineTo(12f, 40f) }
    c.drawPath(path, p)
    return bmp
}
