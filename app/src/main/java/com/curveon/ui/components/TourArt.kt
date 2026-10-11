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
enum class TourArt(val seconds: Int) { PLAN(8), EDIT(9), MARK(11), LOOP(9), SETUP(10), SOLO(10) }

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
        if (art == TourArt.SETUP || art == TourArt.SOLO) drawRect(Color(0xFF0D131B)) else drawBackdrop()
        // Everything fades out at the end of a round, so the next one starts from a clean map.
        val fade = 1f - phase(t, 0.93f, 1f)
        val appear = phase(t, 0f, 0.04f)
        when (art) {
            TourArt.PLAN -> drawPlan(t, fade * appear)
            TourArt.EDIT -> drawEdit(t, fade * appear)
            TourArt.MARK -> drawMark(t, fade * appear)
            TourArt.LOOP -> drawLoop(t, fade * appear)
            TourArt.SETUP -> drawSetup(t, fade * appear)
            TourArt.SOLO -> drawSolo(t, fade * appear)
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

// ---------------------------------------------------------------------------------- MARK

private fun segmentOf(path: Path, from: Float, to: Float): Path {
    val m = PathMeasure().apply { setPath(path, false) }
    return Path().also { m.getSegment(m.length * from, m.length * to, it, true) }
}

private fun pointOn(path: Path, at: Float): Offset {
    val m = PathMeasure().apply { setPath(path, false) }
    return m.getPosition(m.length * at)
}

/** The bar at the bottom while editing on the map; returns where its three-dots button is. */
private fun DrawScope.drawEditBar(alpha: Float, marking: Boolean): Offset {
    val h = 32.dp.toPx()
    val left = size.width * 0.07f
    val right = size.width * 0.93f
    val top = size.height - h - 9.dp.toPx()
    val cy = top + h / 2
    drawRoundRect(CockpitSurface.copy(alpha = alpha), Offset(left, top), androidx.compose.ui.geometry.Size(right - left, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
    drawRoundRect(ElectricCyan.copy(alpha = 0.8f * alpha), Offset(left, top), androidx.compose.ui.geometry.Size(right - left, h),
        androidx.compose.ui.geometry.CornerRadius(h / 2), style = Stroke(1.5.dp.toPx()))
    // The hint, as two grey lines.
    drawRoundRect(TextSecondary.copy(alpha = 0.7f * alpha), Offset(left + 16.dp.toPx(), cy - 6.dp.toPx()), androidx.compose.ui.geometry.Size(size.width * 0.30f, 4.dp.toPx()),
        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    drawRoundRect(TextMuted.copy(alpha = 0.6f * alpha), Offset(left + 16.dp.toPx(), cy + 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width * 0.16f, 3.dp.toPx()),
        androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
    // Done, undo and the three dots, right to left.
    val done = Offset(right - 20.dp.toPx(), cy)
    drawCircle(ElectricCyan.copy(alpha = alpha), 12.dp.toPx(), done)
    val tick = Path().apply {
        moveTo(done.x - 5.dp.toPx(), done.y); lineTo(done.x - 1.5.dp.toPx(), done.y + 3.5.dp.toPx()); lineTo(done.x + 5.dp.toPx(), done.y - 3.5.dp.toPx())
    }
    drawPath(tick, Color(0xFF00363D).copy(alpha = alpha), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    val undo = Offset(done.x - 36.dp.toPx(), cy)
    drawCircle(CockpitSurfaceVariant.copy(alpha = alpha), 10.dp.toPx(), undo)
    drawArc(TextMuted.copy(alpha = alpha), 200f, 250f, false, Offset(undo.x - 4.dp.toPx(), undo.y - 4.dp.toPx()),
        androidx.compose.ui.geometry.Size(8.dp.toPx(), 8.dp.toPx()), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    val dots = Offset(undo.x - 34.dp.toPx(), cy)
    drawCircle((if (marking) ElectricCyan else CockpitSurfaceVariant).copy(alpha = alpha), 12.dp.toPx(), dots)
    val ink = (if (marking) Color(0xFF00363D) else TextPrimary).copy(alpha = alpha)
    drawLine(ink, Offset(dots.x - 6.dp.toPx(), dots.y), Offset(dots.x + 6.dp.toPx(), dots.y), 1.6.dp.toPx(), StrokeCap.Round)
    listOf(-6f, 0f, 6f).forEach { dx -> drawCircle(ink, 2.2.dp.toPx(), Offset(dots.x + dx.dp.toPx(), dots.y)) }
    return dots
}

/** Press the three dots, drag a finger along part of the route: only that part is planned again. */
private fun DrawScope.drawMark(t: Float, alpha: Float) {
    val a = p(0.08f, 0.50f)
    val b = p(0.92f, 0.26f)
    val route = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(size.width * 0.22f, size.height * 0.10f, size.width * 0.38f, size.height * 0.10f, size.width * 0.50f, size.height * 0.34f)
        cubicTo(size.width * 0.62f, size.height * 0.56f, size.width * 0.78f, size.height * 0.56f, b.x, b.y)
    }
    val from = 0.30f
    val to = 0.70f
    val p1 = pointOn(route, from)
    val p2 = pointOn(route, to)
    // What the part becomes: a longer way round, lower down.
    val detour = Path().apply {
        moveTo(p1.x, p1.y)
        cubicTo(p1.x + size.width * 0.17f, p1.y + size.height * 0.46f, p2.x - size.width * 0.17f, p2.y + size.height * 0.46f, p2.x, p2.y)
    }
    val press = phase(t, 0.10f, 0.20f)
    val marking = t in 0.18f..0.66f
    val toStart = phase(t, 0.24f, 0.34f)
    val drag = phase(t, 0.36f, 0.58f)
    val release = phase(t, 0.60f, 0.66f)
    val replan = phase(t, 0.68f, 0.86f)

    // The route in three pieces: the middle one is the part that changes.
    route(segmentOf(route, 0f, from), ElectricCyan, alpha)
    route(segmentOf(route, to, 1f), ElectricCyan, alpha)
    route(segmentOf(route, from, to), ElectricCyan, alpha * (1f - replan))
    if (replan > 0f) route(partOf(detour, replan), ElectricCyan, alpha)
    startDot(a, alpha)
    endDot(b, alpha)

    // The part marked by the finger, with a ring at each end.
    val reach = from + (to - from) * drag
    if (drag > 0f) {
        drawPath(segmentOf(route, from, reach), NeonAmber.copy(alpha = 0.85f * alpha * (1f - replan)),
            style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val ringAlpha = alpha * (1f - phase(t, 0.84f, 0.9f))
        listOf(p1, pointOn(route, reach)).forEach { e ->
            drawCircle(Color.White.copy(alpha = ringAlpha), 7.dp.toPx(), e)
            drawCircle(NeonAmber.copy(alpha = ringAlpha), 7.dp.toPx(), e, style = Stroke(3.dp.toPx()))
        }
    }
    val button = drawEditBar(alpha, marking)

    // The finger: onto the button, then along the route.
    val on = t in 0.08f..0.70f
    val at = when {
        t < 0.24f -> button
        t < 0.36f -> Offset(lerp(button.x, p1.x, toStart), lerp(button.y, p1.y, toStart))
        else -> pointOn(route, reach)
    }
    if (on) finger(at, alpha * phase(t, 0.08f, 0.12f) * (1f - phase(t, 0.64f, 0.70f)), press = if (t < 0.24f) press else 1f)
}

// ---------------------------------------------------------------------------------- SETUP / SOLO

private val Body = Color(0xFF2A3644)
private val Screen = Color(0xFF0D131B)
private val BluetoothBlue = Color(0xFF2979FF)
private val MessageAmber = NeonAmber
private val MusicCyan = ElectricCyan
private val CallGreen = EcoGreen

private fun DrawScope.drawTablet(c: Offset, alpha: Float, pop: Float) {
    if (pop <= 0f) return
    val w = 72.dp.toPx() * pop
    val h = 48.dp.toPx() * pop
    val a = alpha * pop
    drawRoundRect(Body.copy(alpha = a), Offset(c.x - w / 2, c.y - h / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
    val inset = 3.dp.toPx() * pop
    drawRoundRect(Screen.copy(alpha = a), Offset(c.x - w / 2 + inset, c.y - h / 2 + inset),
        androidx.compose.ui.geometry.Size(w - 2 * inset, h - 2 * inset), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
    // A map with a route on the screen.
    val route = Path().apply {
        moveTo(c.x - w * 0.36f, c.y + h * 0.22f)
        cubicTo(c.x - w * 0.2f, c.y - h * 0.4f, c.x, c.y + h * 0.4f, c.x + w * 0.34f, c.y - h * 0.2f)
    }
    drawPath(route, ElectricCyan.copy(alpha = a), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawPhone(c: Offset, alpha: Float, pop: Float, big: Boolean = false, showMap: Boolean = false) {
    if (pop <= 0f) return
    val s = if (big) 1.7f else 1f
    val w = 32.dp.toPx() * s * pop
    val h = 58.dp.toPx() * s * pop
    val a = alpha * pop
    drawRoundRect(Body.copy(alpha = a), Offset(c.x - w / 2, c.y - h / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(7.dp.toPx() * s))
    val inset = 2.5.dp.toPx() * s * pop
    drawRoundRect(Screen.copy(alpha = a), Offset(c.x - w / 2 + inset, c.y - h / 2 + inset * 1.8f),
        androidx.compose.ui.geometry.Size(w - 2 * inset, h - 3.6f * inset), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx() * s))
    if (showMap) {
        val route = Path().apply {
            moveTo(c.x - w * 0.3f, c.y + h * 0.3f)
            cubicTo(c.x - w * 0.3f, c.y - h * 0.1f, c.x + w * 0.3f, c.y + h * 0.1f, c.x + w * 0.28f, c.y - h * 0.3f)
        }
        drawPath(route, ElectricCyan.copy(alpha = a), style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(TextPrimary.copy(alpha = a), 2.6.dp.toPx(), Offset(c.x - w * 0.3f, c.y + h * 0.3f))
    } else {
        // Three notifications: message, music, call.
        listOf(MessageAmber, MusicCyan, CallGreen).forEachIndexed { i, col ->
            val y = c.y - h * 0.24f + i * h * 0.22f
            drawRoundRect(col.copy(alpha = 0.85f * a), Offset(c.x - w * 0.34f, y - 3.dp.toPx()), androidx.compose.ui.geometry.Size(w * 0.68f, 6.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
}

/** A helmet with the intercom on its side. */
private fun DrawScope.drawHelmet(c: Offset, alpha: Float, pop: Float) {
    if (pop <= 0f) return
    val s = 58.dp.toPx() * pop
    val a = alpha * pop
    drawRoundRect(Body.copy(alpha = a), Offset(c.x - s / 2, c.y - s * 0.46f), androidx.compose.ui.geometry.Size(s, s * 0.92f),
        androidx.compose.ui.geometry.CornerRadius(s * 0.46f))
    drawRoundRect(Screen.copy(alpha = a), Offset(c.x - s * 0.02f, c.y - s * 0.14f), androidx.compose.ui.geometry.Size(s * 0.5f, s * 0.3f),
        androidx.compose.ui.geometry.CornerRadius(s * 0.14f))
    drawRoundRect(ElectricCyan.copy(alpha = 0.6f * a), Offset(c.x - s * 0.02f, c.y - s * 0.14f), androidx.compose.ui.geometry.Size(s * 0.5f, s * 0.3f),
        androidx.compose.ui.geometry.CornerRadius(s * 0.14f), style = Stroke(1.5.dp.toPx()))
    drawCircle(NeonAmber.copy(alpha = a), s * 0.1f, Offset(c.x - s * 0.18f, c.y + s * 0.1f))
}

private fun DrawScope.bluetoothBadge(at: Offset, alpha: Float, pop: Float) {
    if (pop <= 0f) return
    val r = 9.dp.toPx() * pop
    drawCircle(BluetoothBlue.copy(alpha = alpha), r, at)
    val k = r * 0.5f
    val rune = Path().apply {
        moveTo(at.x - k * 0.7f, at.y - k * 0.6f); lineTo(at.x + k * 0.7f, at.y + k * 0.6f); lineTo(at.x, at.y + k * 1.2f)
        lineTo(at.x, at.y - k * 1.2f); lineTo(at.x + k * 0.7f, at.y - k * 0.6f); lineTo(at.x - k * 0.7f, at.y + k * 0.6f)
    }
    drawPath(rune, Color.White.copy(alpha = alpha), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A dashed link between two things, drawn in by [grow] (0..1). */
private fun DrawScope.link(a: Offset, b: Offset, grow: Float, alpha: Float) {
    if (grow <= 0f) return
    val end = Offset(lerp(a.x, b.x, grow), lerp(a.y, b.y, grow))
    drawLine(BluetoothBlue.copy(alpha = 0.8f * alpha), a, end, 2.dp.toPx(), StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
}

/** Little dots travelling from [a] to [b]: messages, music and calls on their way. */
private fun DrawScope.pulses(a: Offset, b: Offset, t: Float, colors: List<Color>, alpha: Float) {
    colors.forEachIndexed { i, col ->
        val x = (t * 3f + i / colors.size.toFloat()) % 1f
        val at = Offset(lerp(a.x, b.x, x), lerp(a.y, b.y, x))
        val edge = sin(PI.toFloat() * x)
        drawCircle(col.copy(alpha = 0.3f * edge * alpha), 7.dp.toPx(), at)
        drawCircle(col.copy(alpha = edge * alpha), 3.6.dp.toPx(), at)
    }
}

/** The tablet shows, the phone passes things on, the phone talks to the intercom in the helmet. */
private fun DrawScope.drawSetup(t: Float, alpha: Float) {
    val tablet = p(0.17f, 0.47f)
    val phone = p(0.50f, 0.47f)
    val helmet = p(0.83f, 0.47f)
    drawTablet(tablet, alpha, phase(t, 0.02f, 0.12f))
    drawPhone(phone, alpha, phase(t, 0.10f, 0.20f))
    drawHelmet(helmet, alpha, phase(t, 0.18f, 0.28f))
    val gap = 40.dp.toPx()
    val l1a = Offset(tablet.x + gap, tablet.y); val l1b = Offset(phone.x - 24.dp.toPx(), phone.y)
    val l2a = Offset(phone.x + 24.dp.toPx(), phone.y); val l2b = Offset(helmet.x - 30.dp.toPx(), helmet.y)
    link(l1a, l1b, phase(t, 0.30f, 0.44f), alpha)
    link(l2a, l2b, phase(t, 0.38f, 0.52f), alpha)
    val mid1 = Offset((l1a.x + l1b.x) / 2, l1a.y)
    val mid2 = Offset((l2a.x + l2b.x) / 2, l2a.y)
    bluetoothBadge(mid1, alpha, phase(t, 0.44f, 0.52f))
    bluetoothBadge(mid2, alpha, phase(t, 0.50f, 0.58f))
    val flow = phase(t, 0.58f, 0.64f) * alpha
    if (flow > 0f) {
        val tt = (t - 0.58f)
        // Messages, music and calls go to the tablet; music and calls go on to the helmet, the rider's voice comes back.
        pulses(Offset(l1b.x, l1b.y - 6.dp.toPx()), Offset(l1a.x, l1a.y - 6.dp.toPx()), tt, listOf(MessageAmber, MusicCyan, CallGreen), flow)
        pulses(Offset(l2a.x, l2a.y - 6.dp.toPx()), Offset(l2b.x, l2b.y - 6.dp.toPx()), tt, listOf(MusicCyan, CallGreen), flow)
        pulses(Offset(l2b.x, l2b.y + 6.dp.toPx()), Offset(l2a.x, l2a.y + 6.dp.toPx()), tt + 0.3f, listOf(CallGreen), flow)
    }
}

/** Curveon on a phone is its own companion: everything stays on the one phone, which talks to the intercom. */
private fun DrawScope.drawSolo(t: Float, alpha: Float) {
    val phone = p(0.30f, 0.50f)
    val helmet = p(0.80f, 0.50f)
    drawPhone(phone, alpha, phase(t, 0.02f, 0.14f), big = true, showMap = true)
    drawHelmet(helmet, alpha, phase(t, 0.14f, 0.26f))
    // Messages, music and calls go round on the phone itself.
    val orbit = phase(t, 0.2f, 0.3f) * alpha
    if (orbit > 0f) {
        val rx = 40.dp.toPx(); val ry = 62.dp.toPx()
        drawOval(Color.White.copy(alpha = 0.12f * orbit), Offset(phone.x - rx, phone.y - ry), androidx.compose.ui.geometry.Size(2 * rx, 2 * ry),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))))
        listOf(MessageAmber, MusicCyan, CallGreen).forEachIndexed { i, col ->
            val ang = (t * 2.5f + i / 3f) * 2f * PI.toFloat()
            val at = Offset(phone.x + rx * cos(ang), phone.y + ry * sin(ang))
            drawCircle(col.copy(alpha = 0.3f * orbit), 7.dp.toPx(), at)
            drawCircle(col.copy(alpha = orbit), 3.6.dp.toPx(), at)
        }
    }
    val a = Offset(phone.x + 56.dp.toPx(), phone.y); val b = Offset(helmet.x - 32.dp.toPx(), helmet.y)
    link(a, b, phase(t, 0.34f, 0.48f), alpha)
    bluetoothBadge(Offset((a.x + b.x) / 2, a.y), alpha, phase(t, 0.48f, 0.56f))
    val flow = phase(t, 0.58f, 0.64f) * alpha
    if (flow > 0f) {
        val tt = t - 0.58f
        pulses(Offset(a.x, a.y - 6.dp.toPx()), Offset(b.x, b.y - 6.dp.toPx()), tt, listOf(MusicCyan, CallGreen), flow)
        pulses(Offset(b.x, b.y + 6.dp.toPx()), Offset(a.x, a.y + 6.dp.toPx()), tt + 0.3f, listOf(CallGreen), flow)
    }
}
