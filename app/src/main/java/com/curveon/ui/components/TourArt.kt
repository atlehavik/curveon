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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.curveon.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The little animated drawings in the welcome tour. They carry no text, so they read the same in every language. */
enum class TourArt(val seconds: Int) { PLAN(8), BUILD(10), EDIT(9), MARK(14), LOOP(9), ADVENTURE(11), SETUP(10), SOLO(10), CARDS(16), ENGINE(10) }

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
        if (art == TourArt.SETUP || art == TourArt.SOLO || art == TourArt.CARDS || art == TourArt.ENGINE) drawRect(Color(0xFF0D131B)) else drawBackdrop()
        // Everything fades out at the end of a round, so the next one starts from a clean map.
        val fade = 1f - phase(t, 0.93f, 1f)
        val appear = phase(t, 0f, 0.04f)
        when (art) {
            TourArt.PLAN -> drawPlan(t, fade * appear)
            TourArt.BUILD -> drawBuild(t, fade * appear)
            TourArt.EDIT -> drawEdit(t, fade * appear)
            TourArt.ADVENTURE -> drawAdventure(t, fade * appear)
            TourArt.MARK -> drawMark(t, fade * appear)
            TourArt.LOOP -> drawLoop(t, fade * appear)
            TourArt.SETUP -> drawSetup(t, fade * appear)
            TourArt.SOLO -> drawSolo(t, fade * appear)
            TourArt.CARDS -> drawCards(t, fade * appear)
            TourArt.ENGINE -> drawEngine(t, fade * appear)
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

// ---------------------------------------------------------------------------------- BUILD

/** A trip built point by point: a tap for the start, one for a stop, one for the end; the route follows each tap. */
private fun DrawScope.drawBuild(t: Float, alpha: Float) {
    val a = p(0.09f, 0.72f)
    val stop = p(0.48f, 0.26f)
    val b = p(0.91f, 0.64f)
    val first = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(size.width * 0.16f, size.height * 0.30f, size.width * 0.34f, size.height * 0.10f, stop.x, stop.y)
    }
    val second = Path().apply {
        moveTo(stop.x, stop.y)
        cubicTo(size.width * 0.62f, size.height * 0.42f, size.width * 0.74f, size.height * 0.86f, b.x, b.y)
    }
    val grow1 = phase(t, 0.34f, 0.46f)
    val grow2 = phase(t, 0.64f, 0.78f)
    if (grow1 > 0f) route(partOf(first, grow1), ElectricCyan, alpha)
    if (grow2 > 0f) route(partOf(second, grow2), ElectricCyan, alpha)
    startDot(a, alpha * phase(t, 0.10f, 0.16f))
    stopDot(stop, alpha, phase(t, 0.30f, 0.36f))
    endDot(b, alpha * phase(t, 0.60f, 0.66f), phase(t, 0.60f, 0.68f))
    // A tap for each point.
    fun tap(at: Offset, from: Float, to: Float) {
        if (t in from..to) finger(at, alpha * (1f - phase(t, to - 0.03f, to)), press = phase(t, from, from + 0.07f))
    }
    tap(a, 0.06f, 0.18f)
    tap(stop, 0.26f, 0.38f)
    tap(b, 0.56f, 0.68f)
}

// ---------------------------------------------------------------------------------- ADVENTURE

/** The way to a place; an area is drawn beside it, and the route bends out to wind through it before going on. */
private fun DrawScope.drawAdventure(t: Float, alpha: Float) {
    val a = p(0.08f, 0.70f)
    val b = p(0.92f, 0.28f)
    val direct = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(size.width * 0.34f, size.height * 0.66f, size.width * 0.62f, size.height * 0.36f, b.x, b.y)
    }
    val winding = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(size.width * 0.20f, size.height * 0.92f, size.width * 0.36f, size.height * 0.90f, size.width * 0.44f, size.height * 0.76f)
        cubicTo(size.width * 0.50f, size.height * 0.62f, size.width * 0.60f, size.height * 0.60f, size.width * 0.66f, size.height * 0.76f)
        cubicTo(size.width * 0.72f, size.height * 0.92f, size.width * 0.86f, size.height * 0.86f, size.width * 0.82f, size.height * 0.62f)
        cubicTo(size.width * 0.80f, size.height * 0.44f, size.width * 0.90f, size.height * 0.46f, b.x, b.y)
    }
    val area = List(61) { i ->
        val ang = (i.toFloat() / 60) * 2f * PI.toFloat() + PI.toFloat() * 0.5f
        val k = 1f + 0.07f * sin(3f * ang)
        p(0.64f + 0.27f * k * cos(ang), 0.72f + 0.19f * k * sin(ang))
    }
    val drawDirect = phase(t, 0.04f, 0.20f)
    val sketch = phase(t, 0.26f, 0.5f)
    val closed = phase(t, 0.5f, 0.56f)
    val bend = phase(t, 0.6f, 0.86f)

    // The quick way, which gives way to the winding one.
    route(partOf(direct, drawDirect), ElectricCyan, alpha * (1f - 0.65f * bend))
    val count = (area.size * sketch).toInt().coerceIn(0, area.size - 1)
    val outline = Path().apply { if (count > 0) { moveTo(area[0].x, area[0].y); for (i in 1..count) lineTo(area[i].x, area[i].y) } }
    if (closed > 0f) drawPath(Path().apply { moveTo(area[0].x, area[0].y); area.forEach { lineTo(it.x, it.y) }; close() },
        NeonAmber.copy(alpha = 0.13f * closed * alpha))
    val outlineAlpha = alpha * (1f - 0.6f * bend)
    drawPath(outline, NeonAmber.copy(alpha = outlineAlpha), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (closed > 0f) drawPath(Path().apply { moveTo(area.last().x, area.last().y); lineTo(area[0].x, area[0].y) },
        NeonAmber.copy(alpha = outlineAlpha * closed), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    if (t in 0.26f..0.56f) finger(area[count], alpha * (1f - phase(t, 0.5f, 0.56f)), press = phase(t, 0.26f, 0.34f))
    if (bend > 0f) route(partOf(winding, bend), ElectricCyan, alpha)
    startDot(a, alpha)
    endDot(b, alpha)
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
        cubicTo(p1.x + size.width * 0.17f, p1.y + size.height * 0.36f, p2.x - size.width * 0.17f, p2.y + size.height * 0.36f, p2.x, p2.y)
    }
    val press = phase(t, 0.10f, 0.20f)
    val marking = t in 0.18f..0.66f
    val toStart = phase(t, 0.24f, 0.34f)
    val drag = phase(t, 0.36f, 0.58f)
    // Once the part is marked it is changed as on the map: a handle on it is dragged out, a dashed line follows.
    val toMid = phase(t, 0.62f, 0.70f)
    val grab = phase(t, 0.70f, 0.74f)
    val pull = phase(t, 0.74f, 0.86f)
    val replan = phase(t, 0.88f, 0.92f)
    val mid = pointOn(route, (from + to) / 2)
    val low = Offset(mid.x, mid.y + size.height * 0.27f)
    val handle = Offset(mid.x, lerp(mid.y, low.y, pull))

    // The route in three pieces: the middle one is the part that changes.
    route(segmentOf(route, 0f, from), ElectricCyan, alpha)
    route(segmentOf(route, to, 1f), ElectricCyan, alpha)
    route(segmentOf(route, from, to), ElectricCyan, alpha * (1f - replan) * (if (pull > 0f) 0.55f else 1f))
    route(detour, ElectricCyan, alpha * replan)
    if (pull > 0f && replan < 1f) {
        val guess = Path().apply { moveTo(p1.x, p1.y); lineTo(handle.x, handle.y); lineTo(p2.x, p2.y) }
        drawPath(guess, NeonAmber.copy(alpha = alpha * (1f - replan)), style = Stroke(
            3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 7.dp.toPx()))
        ))
    }
    startDot(a, alpha)
    endDot(b, alpha)

    // The part marked by the finger, with a ring at each end.
    val reach = from + (to - from) * drag
    if (drag > 0f) {
        drawPath(segmentOf(route, from, reach), NeonAmber.copy(alpha = 0.85f * alpha * (1f - phase(t, 0.68f, 0.74f))),
            style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val ringAlpha = alpha * (1f - phase(t, 0.86f, 0.92f))
        listOf(p1, pointOn(route, reach)).forEach { e ->
            drawCircle(Color.White.copy(alpha = ringAlpha), 7.dp.toPx(), e)
            drawCircle(NeonAmber.copy(alpha = ringAlpha), 7.dp.toPx(), e, style = Stroke(3.dp.toPx()))
        }
    }
    // The handle on the marked part, where it is held and dragged.
    if (t >= 0.70f) {
        val h = if (replan > 0f) low else handle
        drawCircle(CockpitBackground.copy(alpha = alpha), 9.dp.toPx(), h)
        drawCircle(NeonAmber.copy(alpha = alpha), 6.dp.toPx(), h, style = Stroke(3.dp.toPx()))
    }
    val button = drawEditBar(alpha, marking)

    // The finger: onto the button, along the route, then to the handle and down with it.
    val on = t in 0.08f..0.92f
    val at = when {
        t < 0.24f -> button
        t < 0.36f -> Offset(lerp(button.x, p1.x, toStart), lerp(button.y, p1.y, toStart))
        t < 0.62f -> pointOn(route, reach)
        t < 0.70f -> Offset(lerp(p2.x, mid.x, toMid), lerp(p2.y, mid.y, toMid))
        else -> handle
    }
    val pressNow = when { t < 0.24f -> press; t in 0.70f..0.74f -> grab; else -> 1f }
    if (on) finger(Offset(at.x + (if (t >= 0.70f) 2.dp.toPx() else 0f), at.y + (if (t >= 0.70f) 6.dp.toPx() else 0f)),
        alpha * phase(t, 0.08f, 0.12f) * (1f - phase(t, 0.88f, 0.92f)), press = pressNow)
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

// ---------------------------------------------------------------------------------- ENGINE

/** An OBD2 adapter: a small box with its plug on one side and a blinking light. */
private fun DrawScope.drawObd(c: Offset, alpha: Float, pop: Float, t: Float) {
    if (pop <= 0f) return
    val w = 46.dp.toPx() * pop
    val h = 28.dp.toPx() * pop
    val a = alpha * pop
    drawRoundRect(Body.copy(alpha = a), Offset(c.x - w / 2, c.y - h / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
    // The plug.
    drawRoundRect(TextMuted.copy(alpha = a), Offset(c.x - w / 2 - 7.dp.toPx() * pop, c.y - h * 0.3f), androidx.compose.ui.geometry.Size(9.dp.toPx() * pop, h * 0.6f),
        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    drawRoundRect(TextSecondary.copy(alpha = 0.6f * a), Offset(c.x - w * 0.28f, c.y - h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.5f, 4.dp.toPx()),
        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    val blink = if ((t * 8f) % 1f < 0.5f) 1f else 0.35f
    drawCircle(NeonAmber.copy(alpha = a * blink), 3.dp.toPx(), Offset(c.x + w * 0.3f, c.y + h * 0.18f))
}

/** A needle gauge: an arc with a mark at [value] (0..1). */
private fun DrawScope.gauge(c: Offset, r: Float, value: Float, color: Color, alpha: Float) {
    val tl = Offset(c.x - r, c.y - r)
    val sz = androidx.compose.ui.geometry.Size(2 * r, 2 * r)
    drawArc(CockpitSurfaceVariant.copy(alpha = alpha), 150f, 240f, false, tl, sz, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    drawArc(color.copy(alpha = alpha), 150f, 240f * value, false, tl, sz, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
}

/** The adapter in the bike talks to Curveon over Bluetooth; an engine panel with gauges appears beside the map. */
private fun DrawScope.drawEngine(t: Float, alpha: Float) {
    val obd = p(0.16f, 0.50f)
    val tablet = p(0.66f, 0.50f)
    drawObd(obd, alpha, phase(t, 0.02f, 0.12f), t)
    val pop = phase(t, 0.10f, 0.20f)
    if (pop > 0f) {
        val w = 150.dp.toPx() * pop
        val h = 94.dp.toPx() * pop
        val a = alpha * pop
        val left = tablet.x - w / 2
        val top = tablet.y - h / 2
        drawRoundRect(Body.copy(alpha = a), Offset(left, top), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
        val inset = 4.dp.toPx() * pop
        drawRoundRect(Screen.copy(alpha = a), Offset(left + inset, top + inset), androidx.compose.ui.geometry.Size(w - 2 * inset, h - 2 * inset),
            androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        // The map on the left of the screen.
        val route = Path().apply {
            moveTo(left + w * 0.10f, top + h * 0.78f)
            cubicTo(left + w * 0.2f, top + h * 0.2f, left + w * 0.42f, top + h * 0.9f, left + w * 0.56f, top + h * 0.26f)
        }
        drawPath(route, ElectricCyan.copy(alpha = a), style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
        // The engine panel slides in on the right once data arrives.
        val show = phase(t, 0.52f, 0.62f)
        if (show > 0f) {
            val pw = w * 0.34f
            val px = left + w - inset - pw - 3.dp.toPx() + (1f - show) * 14.dp.toPx()
            val py = top + inset + 4.dp.toPx()
            val ph = h - 2 * inset - 8.dp.toPx()
            val ca = a * show
            drawRoundRect(CardFill.copy(alpha = ca), Offset(px, py), androidx.compose.ui.geometry.Size(pw, ph), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
            val live = t * 6f * PI.toFloat()
            val rpm = 0.45f + 0.3f * sin(live) * sin(live * 0.37f + 1f)
            gauge(Offset(px + pw / 2, py + ph * 0.38f), pw * 0.3f, rpm.coerceIn(0.1f, 0.95f), ElectricCyan, ca)
            // Coolant temperature creeping up, and a second reading.
            val temp = (0.3f + 0.5f * phase(t, 0.6f, 0.9f))
            drawRoundRect(CockpitSurfaceVariant.copy(alpha = ca), Offset(px + 5.dp.toPx(), py + ph * 0.74f), androidx.compose.ui.geometry.Size(pw - 10.dp.toPx(), 4.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            drawRoundRect(NeonAmber.copy(alpha = ca), Offset(px + 5.dp.toPx(), py + ph * 0.74f), androidx.compose.ui.geometry.Size((pw - 10.dp.toPx()) * temp, 4.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            drawRoundRect(CockpitSurfaceVariant.copy(alpha = ca), Offset(px + 5.dp.toPx(), py + ph * 0.88f), androidx.compose.ui.geometry.Size(pw - 10.dp.toPx(), 4.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            drawRoundRect(EcoGreen.copy(alpha = ca), Offset(px + 5.dp.toPx(), py + ph * 0.88f), androidx.compose.ui.geometry.Size((pw - 10.dp.toPx()) * 0.8f, 4.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
    val a = Offset(obd.x + 30.dp.toPx(), obd.y)
    val b = Offset(tablet.x - 82.dp.toPx(), tablet.y)
    link(a, b, phase(t, 0.26f, 0.40f), alpha)
    bluetoothBadge(Offset((a.x + b.x) / 2, a.y), alpha, phase(t, 0.40f, 0.48f))
    val flow = phase(t, 0.48f, 0.54f) * alpha
    if (flow > 0f) pulses(Offset(a.x, a.y - 6.dp.toPx()), Offset(b.x, b.y - 6.dp.toPx()), t - 0.48f, listOf(NeonAmber, ElectricCyan, EcoGreen), flow)
}

// ---------------------------------------------------------------------------------- CARDS

private val CardFill = Color(0xFF16212D)
private val OtherMusic = Color(0xFFB388FF)
private val DeclineRed = Color(0xFFFF5252)

private fun DrawScope.cardBar(x: Float, y: Float, w: Float, color: Color, alpha: Float, h: Float = 6.dp.toPx()) {
    if (alpha <= 0f || w <= 0f) return
    drawRoundRect(color.copy(alpha = alpha), Offset(x, y - h / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
}

private fun DrawScope.cardFrame(cw: Float, ch: Float, accent: Color, alpha: Float) {
    val r = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
    drawRoundRect(CardFill.copy(alpha = alpha), Offset.Zero, androidx.compose.ui.geometry.Size(cw, ch), r)
    drawRoundRect(accent.copy(alpha = 0.5f * alpha), Offset.Zero, androidx.compose.ui.geometry.Size(cw, ch), r, style = Stroke(1.5.dp.toPx()))
}

/** A music card at the origin: a disc, two lines of text, a bouncing equaliser and a progress bar. */
private fun DrawScope.musicCard(t: Float, cw: Float, ch: Float, accent: Color, alpha: Float) {
    if (alpha <= 0f) return
    cardFrame(cw, ch, accent, alpha)
    val c = Offset(14.dp.toPx() + 25.dp.toPx(), ch * 0.4f)
    drawCircle(accent.copy(alpha = 0.9f * alpha), 25.dp.toPx(), c)
    drawCircle(Color.White.copy(alpha = 0.25f * alpha), 17.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
    drawCircle(CardFill.copy(alpha = alpha), 6.dp.toPx(), c)
    val x = 80.dp.toPx()
    cardBar(x, ch * 0.28f, cw * 0.36f, TextPrimary, 0.85f * alpha)
    cardBar(x, ch * 0.28f + 16.dp.toPx(), cw * 0.22f, TextMuted, 0.7f * alpha)
    for (i in 0 until 4) {
        val h = (6f + 18f * kotlin.math.abs(sin(t * 60f + i * 1.7f))).dp.toPx()
        val bx = cw - 70.dp.toPx() + i * 11.dp.toPx()
        drawLine(accent.copy(alpha = alpha), Offset(bx, ch * 0.4f + h / 2), Offset(bx, ch * 0.4f - h / 2), 4.dp.toPx(), StrokeCap.Round)
    }
    val y = ch - 18.dp.toPx()
    cardBar(14.dp.toPx(), y, cw - 28.dp.toPx(), Color.White, 0.12f * alpha, 4.dp.toPx())
    cardBar(14.dp.toPx(), y, (cw - 28.dp.toPx()) * (0.3f + 0.4f * ((t * 4f) % 1f)), accent, alpha, 4.dp.toPx())
}

/** Sound bars: a voice, [amount] 0..1 of full height. */
private fun DrawScope.voice(t: Float, x0: Float, x1: Float, y: Float, color: Color, alpha: Float) {
    if (alpha <= 0f) return
    val n = ((x1 - x0) / 9.dp.toPx()).toInt().coerceAtLeast(2)
    for (i in 0 until n) {
        val h = (4f + 16f * kotlin.math.abs(sin(t * 70f + i * 0.9f) * cos(i * 0.5f))).dp.toPx()
        val x = x0 + i * (x1 - x0) / (n - 1)
        drawLine(color.copy(alpha = alpha), Offset(x, y - h / 2), Offset(x, y + h / 2), 3.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.messageCard(t: Float, cw: Float, ch: Float, alpha: Float, pills: Float, dictate: Float, fill: Float, check: Float) {
    if (alpha <= 0f) return
    cardFrame(cw, ch, MessageAmber, alpha)
    val bx = 14.dp.toPx(); val by = 14.dp.toPx()
    drawRoundRect(MessageAmber.copy(alpha = alpha), Offset(bx, by), androidx.compose.ui.geometry.Size(46.dp.toPx(), 34.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
    val tail = Path().apply { moveTo(bx + 8.dp.toPx(), by + 32.dp.toPx()); lineTo(bx + 6.dp.toPx(), by + 44.dp.toPx()); lineTo(bx + 20.dp.toPx(), by + 33.dp.toPx()); close() }
    drawPath(tail, MessageAmber.copy(alpha = alpha))
    cardBar(bx + 9.dp.toPx(), by + 12.dp.toPx(), 28.dp.toPx(), CardFill, alpha, 4.dp.toPx())
    cardBar(bx + 9.dp.toPx(), by + 22.dp.toPx(), 18.dp.toPx(), CardFill, alpha, 4.dp.toPx())
    val x = 78.dp.toPx()
    cardBar(x, ch * 0.28f, cw * 0.44f, TextPrimary, 0.85f * alpha)
    cardBar(x, ch * 0.28f + 16.dp.toPx(), cw * 0.3f, TextMuted, 0.7f * alpha)
    val py = ch - 20.dp.toPx()
    if (pills > 0f) {
        val h = 22.dp.toPx()
        val reply = Offset(cw - 14.dp.toPx() - 64.dp.toPx(), py - h / 2)
        drawRoundRect(ElectricCyan.copy(alpha = alpha * pills), reply, androidx.compose.ui.geometry.Size(64.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawRoundRect(TextMuted.copy(alpha = alpha * pills), Offset(reply.x - 8.dp.toPx() - 56.dp.toPx(), reply.y), androidx.compose.ui.geometry.Size(56.dp.toPx(), h),
            androidx.compose.ui.geometry.CornerRadius(h / 2), style = Stroke(1.5.dp.toPx()))
    }
    // Reply: the rider speaks, the words fill in as a line of text.
    voice(t, 14.dp.toPx(), 90.dp.toPx(), py, MessageAmber, alpha * dictate)
    cardBar(100.dp.toPx(), py, (cw - 150.dp.toPx()) * fill, TextPrimary, 0.8f * alpha * (if (dictate > 0f || check > 0f) 1f else 0f), 6.dp.toPx())
    if (check > 0f) {
        val c = Offset(cw - 30.dp.toPx(), py)
        drawCircle(CallGreen.copy(alpha = alpha * check), 11.dp.toPx() * check, c)
        val tick = Path().apply { moveTo(c.x - 4.5.dp.toPx(), c.y); lineTo(c.x - 1.dp.toPx(), c.y + 3.5.dp.toPx()); lineTo(c.x + 5.dp.toPx(), c.y - 3.5.dp.toPx()) }
        drawPath(tick, CardFill.copy(alpha = alpha * check), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.callCard(t: Float, cw: Float, ch: Float, alpha: Float, ringing: Boolean, answered: Float) {
    if (alpha <= 0f) return
    cardFrame(cw, ch, CallGreen, alpha)
    val c = Offset(14.dp.toPx() + 24.dp.toPx(), ch / 2)
    if (ringing && answered < 1f) {
        for (k in 0..1) {
            val x = (t * 9f + k * 0.5f) % 1f
            drawCircle(CallGreen.copy(alpha = 0.45f * (1f - x) * alpha * (1f - answered)), lerp(24f, 44f, x).dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
    drawCircle(CallGreen.copy(alpha = alpha), 24.dp.toPx(), c)
    rotate(-40f, c) {
        drawRoundRect(CardFill.copy(alpha = alpha), Offset(c.x - 4.dp.toPx(), c.y - 12.dp.toPx()), androidx.compose.ui.geometry.Size(8.dp.toPx(), 24.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
    }
    val x = 80.dp.toPx()
    cardBar(x, ch * 0.3f, cw * 0.3f, TextPrimary, 0.85f * alpha)
    cardBar(x, ch * 0.3f + 16.dp.toPx(), cw * 0.18f, TextMuted, 0.7f * alpha)
    val ry = ch * 0.7f
    val before = 1f - answered
    if (before > 0f) {
        drawCircle(DeclineRed.copy(alpha = alpha * before), 13.dp.toPx(), Offset(cw - 66.dp.toPx(), ch / 2))
        drawCircle(CallGreen.copy(alpha = alpha * before), 13.dp.toPx(), Offset(cw - 30.dp.toPx(), ch / 2))
    }
    // Answered: both of them talking.
    voice(t, 80.dp.toPx(), cw - 18.dp.toPx(), ry + 4.dp.toPx(), CallGreen, alpha * answered)
}

/** The dashboard: the map on the left, the cards on the right. Music plays, the rider flips between music apps,
 *  a message comes in and is answered by voice, a call is taken. */
private fun DrawScope.drawCards(t: Float, alpha: Float) {
    // The map, with the route and the rider on it.
    val mapW = size.width * 0.56f
    val mapR = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
    val mapTL = Offset(size.width * 0.03f, size.height * 0.1f)
    val mapSize = androidx.compose.ui.geometry.Size(mapW, size.height * 0.8f)
    drawRoundRect(Water.copy(alpha = alpha), mapTL, mapSize, mapR)
    withTransform({ clipRect(mapTL.x, mapTL.y, mapTL.x + mapW, mapTL.y + mapSize.height) }) {
        listOf(0f to 0.3f, 0.2f to 0.62f, 0.7f to 0.2f).forEach { (a, b) ->
            drawLine(Road.copy(alpha = alpha), Offset(mapTL.x, mapTL.y + mapSize.height * (a + 0.1f)), Offset(mapTL.x + mapW, mapTL.y + mapSize.height * b), 3.dp.toPx(), StrokeCap.Round)
        }
        val r = Path().apply {
            moveTo(mapTL.x + mapW * 0.06f, mapTL.y + mapSize.height * 0.85f)
            cubicTo(mapTL.x + mapW * 0.3f, mapTL.y + mapSize.height * 0.2f, mapTL.x + mapW * 0.5f, mapTL.y + mapSize.height * 1.0f, mapTL.x + mapW * 0.95f, mapTL.y + mapSize.height * 0.25f)
        }
        route(r, ElectricCyan, alpha)
    }
    drawRoundRect(CardFill.copy(alpha = 0.9f * alpha), Offset(mapTL.x + 6.dp.toPx(), mapTL.y + 6.dp.toPx()),
        androidx.compose.ui.geometry.Size(34.dp.toPx(), 16.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
    cardBar(mapTL.x + 12.dp.toPx(), mapTL.y + 14.dp.toPx(), 22.dp.toPx(), TextPrimary, 0.8f * alpha, 4.dp.toPx())

    // The card column, drawn at design size and scaled to fit.
    val colLeft = size.width * 0.62f
    val colW = size.width * 0.35f
    val clipLeft = colLeft - 2.dp.toPx()
    val cwD = 250.dp.toPx(); val chD = 92.dp.toPx()
    val sc = colW / cwD
    val cw = colW; val ch = chD * sc
    val top = (size.height - ch) / 2 - 6.dp.toPx()
    val swipe = phase(t, 0.20f, 0.30f)
    val msgIn = phase(t, 0.40f, 0.47f)
    val msgOut = phase(t, 0.70f, 0.76f)
    val callIn = phase(t, 0.70f, 0.76f)
    val answered = phase(t, 0.84f, 0.87f)
    fun DrawScope.card(dx: Float, dy: Float, body: DrawScope.() -> Unit) =
        translate(colLeft + dx, top + dy) { scale(sc, sc, Offset.Zero) { body() } }
    withTransform({ clipRect(clipLeft, 0f, size.width, size.height) }) {
        card(-swipe * cw, 0f) { musicCard(t, cwD, chD, MusicCyan, alpha) }
        card((1f - swipe) * cw, msgIn * ch * 0.5f) { musicCard(t, cwD, chD, OtherMusic, alpha * (1f - msgIn) * phase(t, 0.19f, 0.2f)) }
        val pills = 1f - phase(t, 0.54f, 0.57f)
        val dictate = phase(t, 0.54f, 0.57f) * (1f - phase(t, 0.66f, 0.69f))
        card(0f, -(1f - msgIn) * ch - msgOut * ch) {
            messageCard(t, cwD, chD, alpha * msgIn * (1f - msgOut), pills, dictate, phase(t, 0.57f, 0.66f), phase(t, 0.67f, 0.71f))
        }
        card(0f, (1f - callIn) * ch * 0.5f) { callCard(t, cwD, chD, alpha * callIn, t > 0.72f, answered) }
    }
    // Which music app is showing.
    val dotsA = alpha * (1f - msgIn)
    val dy = top + ch + 12.dp.toPx()
    drawCircle((if (swipe < 0.5f) MusicCyan else OtherMusic).copy(alpha = dotsA), 3.dp.toPx(), Offset(colLeft + cw / 2 - 7.dp.toPx(), dy))
    drawCircle(TextMuted.copy(alpha = 0.5f * dotsA), 3.dp.toPx(), Offset(colLeft + cw / 2 + 7.dp.toPx(), dy))
    // Fingers: the swipe, the tap on Reply, the tap on Answer.
    val y = top + ch * 0.5f
    finger(Offset(lerp(colLeft + cw * 0.8f, colLeft + cw * 0.2f, swipe), y), alpha * phase(t, 0.18f, 0.2f) * (1f - phase(t, 0.3f, 0.32f)))
    val replyAt = Offset(colLeft + (cwD - 46.dp.toPx()) * sc, top + (chD - 20.dp.toPx()) * sc)
    val move = phase(t, 0.46f, 0.51f)
    finger(Offset(lerp(colLeft + cw * 0.55f, replyAt.x, move), lerp(top + ch * 0.8f, replyAt.y, move)),
        alpha * phase(t, 0.45f, 0.47f) * (1f - phase(t, 0.55f, 0.58f)), ((t - 0.51f) / 0.05f).coerceIn(0f, 1f))
    val answerAt = Offset(colLeft + (cwD - 30.dp.toPx()) * sc, top + ch / 2)
    val move3 = phase(t, 0.78f, 0.82f)
    finger(Offset(lerp(colLeft + cw * 0.55f, answerAt.x, move3), lerp(top + ch * 0.9f, answerAt.y, move3)),
        alpha * phase(t, 0.77f, 0.79f) * (1f - phase(t, 0.88f, 0.91f)), ((t - 0.82f) / 0.05f).coerceIn(0f, 1f))
}
