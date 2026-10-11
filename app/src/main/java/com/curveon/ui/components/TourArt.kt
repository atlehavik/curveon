package com.curveon.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.curveon.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The little animated drawings in the welcome tour. They carry no text, so they read the same in every language. */
enum class TourArt(val seconds: Int) { PLAN(8), EDIT(9), LOOP(9) }

/** One looping animation of [art] on a dark scrap of map. */
@Composable
fun TourArtView(art: TourArt, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "tourArt").animateFloat(
        0f, 1f, infiniteRepeatable(tween(art.seconds * 1000, easing = LinearEasing), RepeatMode.Restart), label = "tourArtT"
    )
    Canvas(
        modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp))
            .border(1.dp, CockpitBorder, RoundedCornerShape(18.dp))
    ) {
        drawBackdrop()
        // Everything fades out at the end of a round, so the next one starts from a clean map.
        val fade = 1f - phase(t, 0.93f, 1f)
        val appear = phase(t, 0f, 0.04f)
        when (art) {
            TourArt.PLAN -> drawPlan(t, fade * appear)
            TourArt.EDIT -> drawEdit(t, fade * appear)
            TourArt.LOOP -> drawLoop(t, fade * appear)
        }
    }
}

// ---------------------------------------------------------------------------------- helpers

/** 0 before [from], 1 after [to], eased in between. */
private fun phase(t: Float, from: Float, to: Float): Float {
    val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x

private fun DrawScope.p(x: Float, y: Float) = Offset(size.width * x, size.height * y)

private val Road = Color(0xFF1E2A38)
private val Water = Color(0xFF0F2730)

private fun DrawScope.drawBackdrop() {
    drawRect(Color(0xFF0D131B))
    // Water along the bottom-left, and a few streets crossing.
    val lake = Path().apply {
        moveTo(0f, size.height * 0.62f)
        cubicTo(size.width * 0.14f, size.height * 0.5f, size.width * 0.3f, size.height * 0.95f, size.width * 0.42f, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(lake, Water)
    val w = 3.dp.toPx()
    listOf(
        Offset(0.0f, 0.22f) to Offset(1.0f, 0.12f), Offset(0.0f, 0.46f) to Offset(1.0f, 0.40f),
        Offset(0.58f, 0.0f) to Offset(0.70f, 1.0f), Offset(0.28f, 0.0f) to Offset(0.20f, 0.5f),
        Offset(0.86f, 0.0f) to Offset(0.94f, 1.0f), Offset(0.45f, 1.0f) to Offset(1.0f, 0.80f)
    ).forEach { (a, b) -> drawLine(Road, p(a.x, a.y), p(b.x, b.y), w, StrokeCap.Round) }
}

/** The part of [path] up to [x] (0..1) of its length. */
private fun partOf(path: Path, x: Float): Path {
    val m = PathMeasure().apply { setPath(path, false) }
    return Path().also { m.getSegment(0f, m.length * x, it, true) }
}

private fun DrawScope.route(path: Path, color: Color, alpha: Float, width: Float = 4.dp.toPx()) {
    if (alpha <= 0f) return
    drawPath(path, CockpitBackground.copy(alpha = 0.9f * alpha), style = Stroke(width * 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The start of a trip: a ring. */
private fun DrawScope.startDot(at: Offset, alpha: Float) {
    drawCircle(CockpitBackground.copy(alpha = alpha), 9.dp.toPx(), at)
    drawCircle(TextPrimary.copy(alpha = alpha), 7.dp.toPx(), at, style = Stroke(3.dp.toPx()))
}

/** The destination: a filled pin, [pop] 0..1 grows it in. */
private fun DrawScope.endDot(at: Offset, alpha: Float, pop: Float = 1f) {
    val r = 9.dp.toPx() * pop
    drawCircle(CockpitBackground.copy(alpha = alpha), r + 3.dp.toPx(), at)
    drawCircle(ElectricCyan.copy(alpha = alpha), r, at)
    drawCircle(CockpitBackground.copy(alpha = alpha), r * 0.38f, at)
}

/** A stop on the way: amber. */
private fun DrawScope.stopDot(at: Offset, alpha: Float, pop: Float) {
    val r = 8.dp.toPx() * pop
    if (r <= 0f) return
    drawCircle(CockpitBackground.copy(alpha = alpha), r + 3.dp.toPx(), at)
    drawCircle(NeonAmber.copy(alpha = alpha), r, at)
}

/** A fingertip: a soft disc, with a ripple while it presses ([press] 0..1 over time). */
private fun DrawScope.finger(at: Offset, alpha: Float, press: Float = 0f) {
    if (alpha <= 0f) return
    if (press > 0f && press < 1f) {
        drawCircle(Color.White.copy(alpha = 0.5f * (1f - press) * alpha), lerp(14f, 34f, press).dp.toPx(), at, style = Stroke(2.dp.toPx()))
    }
    drawCircle(Color.White.copy(alpha = 0.22f * alpha), 17.dp.toPx(), at)
    drawCircle(Color.White.copy(alpha = 0.75f * alpha), 17.dp.toPx(), at, style = Stroke(2.dp.toPx()))
}

// ---------------------------------------------------------------------------------- PLAN

/** A→B appears; a stop is added on the way; the route bends to go through it. */
private fun DrawScope.drawPlan(t: Float, alpha: Float) {
    val a = p(0.09f, 0.74f)
    val b = p(0.91f, 0.62f)
    val stop = p(0.50f, 0.24f)
    val direct = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(size.width * 0.28f, size.height * 0.98f, size.width * 0.34f, size.height * 0.42f, size.width * 0.52f, size.height * 0.58f)
        cubicTo(size.width * 0.66f, size.height * 0.72f, size.width * 0.78f, size.height * 0.5f, b.x, b.y)
    }
    val viaStop = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(size.width * 0.20f, size.height * 0.46f, size.width * 0.34f, size.height * 0.14f, stop.x, stop.y)
        cubicTo(size.width * 0.68f, size.height * 0.34f, size.width * 0.74f, size.height * 0.86f, b.x, b.y)
    }
    val draw = phase(t, 0.08f, 0.34f)
    val pop = phase(t, 0.44f, 0.52f)
    val morph = phase(t, 0.56f, 0.76f)
    route(partOf(direct, draw), ElectricCyan, alpha * (1f - morph))
    // The stop pulls the line towards it: the new one is drawn in from the start while the old one fades.
    if (morph > 0f) route(partOf(viaStop, morph), ElectricCyan, alpha)
    startDot(a, alpha * phase(t, 0.02f, 0.08f))
    endDot(b, alpha * phase(t, 0.02f, 0.1f), phase(t, 0.02f, 0.12f))
    stopDot(stop, alpha, pop)
    // A tap where the stop goes.
    val tap = phase(t, 0.38f, 0.5f)
    if (t in 0.38f..0.52f) finger(stop, alpha * (1f - phase(t, 0.48f, 0.52f)), press = tap)
}

// ---------------------------------------------------------------------------------- EDIT

/** A finger takes hold of the route and drags it: a dashed guess follows, then the route goes that way. */
private fun DrawScope.drawEdit(t: Float, alpha: Float) {
    val a = p(0.09f, 0.72f)
    val b = p(0.91f, 0.66f)
    val grab = p(0.5f, 0.64f)
    val to = p(0.5f, 0.2f)
    val before = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(size.width * 0.30f, size.height * 0.72f, size.width * 0.32f, size.height * 0.64f, grab.x, grab.y)
        cubicTo(size.width * 0.70f, size.height * 0.64f, size.width * 0.72f, size.height * 0.66f, b.x, b.y)
    }
    val after = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(size.width * 0.28f, size.height * 0.62f, size.width * 0.30f, to.y, to.x, to.y)
        cubicTo(size.width * 0.70f, to.y, size.width * 0.74f, size.height * 0.60f, b.x, b.y)
    }
    val press = phase(t, 0.14f, 0.26f)
    val drag = phase(t, 0.28f, 0.56f)
    val release = phase(t, 0.58f, 0.68f)
    val fingerAt = Offset(grab.x, lerp(grab.y, to.y, drag))
    val fingerOn = t in 0.14f..0.7f

    // The route as it was, until it is let go; then the new one.
    route(before, ElectricCyan, alpha * (1f - release) * (if (drag > 0f) 0.55f else 1f))
    route(after, ElectricCyan, alpha * release)
    // The dashed guess from the start through the finger to the end.
    if (drag > 0f && release < 1f) {
        val guess = Path().apply { moveTo(a.x, a.y); lineTo(fingerAt.x, fingerAt.y); lineTo(b.x, b.y) }
        drawPath(guess, NeonAmber.copy(alpha = alpha * (1f - release)), style = Stroke(
            3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 7.dp.toPx()))
        ))
    }
    startDot(a, alpha)
    endDot(b, alpha)
    // A handle on the line where it is held, and where it ended up.
    val handle = if (release > 0f) to else fingerAt
    if (t >= 0.14f) {
        drawCircle(CockpitBackground.copy(alpha = alpha), 9.dp.toPx(), handle)
        drawCircle(NeonAmber.copy(alpha = alpha), 6.dp.toPx(), handle, style = Stroke(3.dp.toPx()))
    }
    if (fingerOn) finger(Offset(handle.x + 2.dp.toPx(), handle.y + 6.dp.toPx()),
        alpha * (1f - phase(t, 0.66f, 0.72f)), press = if (press < 1f) press else 1f)
}

// ---------------------------------------------------------------------------------- LOOP

/** A finger draws a ring on the map; a loop through it appears. */
private fun DrawScope.drawLoop(t: Float, alpha: Float) {
    fun ring(scale: Float, wobble: Float, steps: Int) = List(steps + 1) { i ->
        val ang = (i.toFloat() / steps) * 2f * PI.toFloat() + PI.toFloat() * 0.75f
        val k = 1f + wobble * sin(3f * ang) + wobble * 0.6f * cos(2f * ang)
        p(0.5f + 0.31f * scale * k * cos(ang), 0.5f + 0.34f * scale * k * sin(ang))
    }
    val drawn = ring(1f, 0.09f, 72)
    val loop = ring(0.74f, 0.1f, 90).map { Offset(it.x + 0.02f * size.width * cos(it.y / size.height * 7f), it.y) }

    val sketch = phase(t, 0.06f, 0.44f)
    val closed = phase(t, 0.44f, 0.52f)
    val build = phase(t, 0.54f, 0.82f)

    val count = (drawn.size * sketch).toInt().coerceIn(0, drawn.size - 1)
    val outline = Path().apply {
        if (count > 0) { moveTo(drawn[0].x, drawn[0].y); for (i in 1..count) lineTo(drawn[i].x, drawn[i].y) }
    }
    if (closed > 0f) {
        val area = Path().apply { moveTo(drawn[0].x, drawn[0].y); drawn.forEach { lineTo(it.x, it.y) }; close() }
        drawPath(area, NeonAmber.copy(alpha = 0.13f * closed * alpha))
    }
    // The drawn outline fades once the road loop is built inside it.
    val outlineAlpha = alpha * (1f - 0.6f * build)
    drawPath(outline, NeonAmber.copy(alpha = outlineAlpha), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (closed > 0f) drawPath(Path().apply { moveTo(drawn.last().x, drawn.last().y); lineTo(drawn[0].x, drawn[0].y) },
        NeonAmber.copy(alpha = outlineAlpha * closed), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    if (t in 0.06f..0.5f) finger(drawn[count], alpha * (1f - phase(t, 0.44f, 0.5f)), press = phase(t, 0.06f, 0.14f))

    if (build > 0f) {
        val roads = Path().apply { moveTo(loop[0].x, loop[0].y); loop.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        route(partOf(roads, build), ElectricCyan, alpha)
        startDot(loop[0], alpha * phase(t, 0.54f, 0.6f))
    }
}
