package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.ui.theme.*
import org.maplibre.android.style.layers.PropertyFactory.*

/** The rider arrow on screen: position in px, rotation from screen-up, and the map's tilt. */
/** Where the arrow is drawn, and the halo's radii round it (px). */
internal data class Puck(val x: Float, val y: Float, val rotation: Float, val tilt: Float, val haloRx: Float = 0f, val haloRy: Float = 0f)

/** The smallest halo: a ring just outside the arrow (44 dp, its tip ~21 dp from the middle). */
internal const val HALO_MIN_DP = 27f

/** How far round the rider the map's roads are read to put the rider on one (see onRoadsNear). */
internal const val ROADS_NEAR_M = 40.0

/**
 * A see-through disc under the arrow, as big as the GPS's accuracy: a small ring round the arrow
 * normally, wider where the fix is poor or the position is guessed (tunnels). White
 * on the dark map; the deeper blue of the route on the light one, where cyan would fade into it.
 */
@Composable
internal fun RiderHalo(dark: Boolean, color: Color, puck: () -> Puck?) {
    // White on the dark map: a tinted halo vanished into it.
    val fill = if (dark) Color.White.copy(alpha = 0.18f) else Color(0x2E0091EA)
    val edge = if (dark) Color.White.copy(alpha = 0.7f) else Color(0x990091EA)
    val stroke = with(androidx.compose.ui.platform.LocalDensity.current) { 1.5.dp.toPx() }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val p = puck() ?: return@Canvas
        if (p.haloRx <= 0f) return@Canvas
        val topLeft = androidx.compose.ui.geometry.Offset(p.x - p.haloRx, p.y - p.haloRy)
        val size = androidx.compose.ui.geometry.Size(2 * p.haloRx, 2 * p.haloRy)
        drawOval(fill, topLeft, size)
        drawOval(edge, topLeft, size, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
    }
}

/** An amber satellite up to the right of the (ghost) arrow: no GPS signal (or only a guess, in a tunnel). */
@Composable
internal fun RiderNoSatellite(puck: () -> Puck?) {
    val off = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
    Box(
        Modifier
            .graphicsLayer {
                val p = puck()
                alpha = if (p == null) 0f else 1f
                if (p != null) { translationX = p.x + off - size.width / 2; translationY = p.y - off - size.height / 2 }
            }
            .size(30.dp).clip(CircleShape).background(CockpitSurface).border(1.5.dp, NeonAmber, CircleShape)
            .testTag("rider_no_gps"),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.SatelliteAlt, stringResource(R.string.gps_lost), tint = NeonAmber, modifier = Modifier.size(19.dp))
    }
}

/** "DEMO" right under the arrow while the ride is a simulation, so it can't be taken for a real one. */
@Composable
internal fun RiderDemoTag(onTap: () -> Unit, puck: () -> Puck?) {
    val below = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    // Placed (not just drawn) at the arrow, so a tap on it lands on it: it opens the demo's tricks.
    Box(
        Modifier
            .offset { puck()?.let { androidx.compose.ui.unit.IntOffset((it.x - width / 2).toInt(), (it.y + below).toInt()) } ?: androidx.compose.ui.unit.IntOffset(-10_000, 0) }
            .onSizeChanged { size -> width = size.width }
            .clip(RoundedCornerShape(50)).clickable(onClick = onTap).background(NeonAmber).border(2.dp, Color.Black, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .testTag("demo_ride_tag")
    ) {
        Text(stringResource(R.string.map_demo_ride), color = Color.Black, fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/**
 * The rider arrow, drawn over the map. Read through [puck] inside the layer block, so moving it
 * every frame only redraws the arrow. Tilted with the map in 3D so it lies on the road.
 */
@Composable
internal fun RiderArrow(orange: Boolean, ghost: Boolean = false, puck: () -> Puck?) {
    val size = 44.dp
    val half = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() / 2 }
    // Without a fix: grey and see-through, a ghost of where the rider was.
    val grey = remember { androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) }
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(if (orange) com.curveon.R.drawable.ic_nav_arrow_night else com.curveon.R.drawable.ic_nav_arrow),
        contentDescription = null,
        colorFilter = if (ghost) grey else null,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val p = puck()
                alpha = if (p == null) 0f else if (ghost) 0.45f else 1f
                if (p != null) {
                    translationX = p.x - half
                    translationY = p.y - half
                    rotationX = p.tilt * 0.8f
                    cameraDistance = 12f * density
                }
            }
            .graphicsLayer { rotationZ = puck()?.rotation ?: 0f }
    )
}

/** Where the remote is pointing while it moves the map: white in a dark edge, for the day map and the night map. */
@Composable
internal fun RemoteCrosshair(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(44.dp).testTag("map_remote_crosshair")) {
        val c = center
        val arm = size.minDimension / 2
        val gap = arm * 0.3f
        listOf(Color.Black.copy(alpha = 0.75f) to 5.dp.toPx(), Color.White to 2.5.dp.toPx()).forEach { (color, width) ->
            drawLine(color, c.copy(x = c.x - arm), c.copy(x = c.x - gap), width)
            drawLine(color, c.copy(x = c.x + gap), c.copy(x = c.x + arm), width)
            drawLine(color, c.copy(y = c.y - arm), c.copy(y = c.y - gap), width)
            drawLine(color, c.copy(y = c.y + gap), c.copy(y = c.y + arm), width)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun MapButton(
    onClick: () -> Unit,
    tag: String,
    highlighted: Boolean = false,
    /** Held down; the button is then a box that tells taps and holds apart. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (onLongClick != null) return Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (highlighted) ElectricCyan else CockpitSurface)
            .border(1.dp, CockpitBorder, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) { content() }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (highlighted) ElectricCyan else CockpitSurface)
            .border(1.dp, CockpitBorder, CircleShape)
            .testTag(tag)
    ) { content() }
}

/**
 * The rider is off screen: a glow on the edge in their direction, stronger the closer they are,
 * so it's clear at a glance where "me" went after panning around. Cyan alone vanishes into the day
 * map's light blue water, so there it sits in a dark ring, like the rider arrow's dark outline.
 */
@Composable
internal fun RiderEdgeGlow(rider: androidx.compose.ui.geometry.Offset, dark: Boolean, color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val margin = 12.dp.toPx()
        if (rider.x in -margin..w + margin && rider.y in -margin..h + margin) return@Canvas
        if (rider.x.isNaN() || rider.y.isNaN()) return@Canvas
        val c = center
        val d = rider - c
        // Where the line from the centre towards the rider leaves the screen.
        val t = minOf(
            if (d.x != 0f) (w / 2) / kotlin.math.abs(d.x) else Float.MAX_VALUE,
            if (d.y != 0f) (h / 2) / kotlin.math.abs(d.y) else Float.MAX_VALUE
        )
        val edge = c + d * t
        val beyond = d.getDistance() * (1 - t)
        val near = (1f - beyond / (maxOf(w, h) * 3f)).coerceIn(0.35f, 1f)
        val radius = 110.dp.toPx() * (0.7f + 0.3f * near)
        // A small cap peeking in from the edge: the circle sits partly beyond it. On the day map the
        // cyan centre fades into a dark ring, on the night map straight into the map.
        val r = radius * 0.55f
        val at = edge + d / d.getDistance() * (r * 0.3f)
        val stops = if (dark) arrayOf(
            0f to color.copy(alpha = near),
            0.35f to color.copy(alpha = 0.8f * near),
            0.7f to color.copy(alpha = 0.2f * near),
            1f to Color.Transparent
        ) else arrayOf(
            0f to color.copy(alpha = near),
            0.35f to color.copy(alpha = 0.9f * near),
            0.55f to CockpitBackground.copy(alpha = 0.6f * near),
            1f to Color.Transparent
        )
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(*stops, center = at, radius = r), radius = r, center = at)
    }
}
