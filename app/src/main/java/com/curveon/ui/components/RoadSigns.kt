package com.curveon.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.curveon.R
import com.curveon.ui.theme.*

/** A road's number as its sign shows it (green E- and riksveg, white fylkesveg, …), [height] tall. */
@Composable
fun RoadShield(ref: com.curveon.data.road.RoadRef, height: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(height * 0.16f)
    Box(
        modifier.height(height).clip(shape).background(Color(ref.style.background))
            .border(height * 0.08f, Color(ref.style.border), shape)
            .padding(horizontal = height * 0.24f),
        contentAlignment = Alignment.Center
    ) {
        Text(ref.text, color = Color(ref.style.text), fontSize = (height.value * 0.52f).sp, lineHeight = (height.value * 0.6f).sp,
            fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/** The priority road sign: a yellow diamond with a white edge. */
@Composable
fun PriorityRoadSign(size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        fun diamond(inset: Float) = androidx.compose.ui.graphics.Path().apply {
            val c = this@Canvas.size.width / 2
            moveTo(c, inset); lineTo(2 * c - inset, c); lineTo(c, 2 * c - inset); lineTo(inset, c); close()
        }
        val w = this.size.width
        drawPath(diamond(0f), Color(0xFF1A1A1A))
        drawPath(diamond(w * 0.03f), Color.White)
        drawPath(diamond(w * 0.2f), Color(0xFFFFC20E))
    }
}

/** The give way sign: a white triangle pointing down with a red edge. */
@Composable
fun GiveWaySign(size: Dp, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.give_way)
    androidx.compose.foundation.Canvas(modifier.size(size).semantics { contentDescription = label }) {
        val w = this.size.width
        val h = w * 0.87f
        val top = (w - h) / 2
        fun triangle(inset: Float) = androidx.compose.ui.graphics.Path().apply {
            // The centre of the triangle is a third of its height from the top edge.
            val cy = top + h / 3
            val k = 1 - inset / (h / 3) // shrink towards the centre by [inset] at the top edge
            moveTo(w / 2 + (0 - w / 2) * k, cy + (top - cy) * k)
            lineTo(w / 2 + (w - w / 2) * k, cy + (top - cy) * k)
            lineTo(w / 2, cy + (top + h - cy) * k)
            close()
        }
        drawPath(triangle(0f), Color.White)
        drawPath(triangle(w * 0.035f), Color(0xFFD52B1E))
        drawPath(triangle(w * 0.15f), Color.White)
    }
}

/** The stop sign: a red octagon with a white edge and STOP. */
@Composable
fun StopSign(size: Dp, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.stop_sign)
    Box(modifier.size(size).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            fun octagon(inset: Float) = androidx.compose.ui.graphics.Path().apply {
                val c = this@Canvas.size.width / 2
                val r = (c - inset) / kotlin.math.cos(Math.PI / 8).toFloat()
                for (i in 0 until 8) {
                    val a = Math.PI / 8 + i * Math.PI / 4
                    val x = c + r * kotlin.math.cos(a).toFloat(); val y = c + r * kotlin.math.sin(a).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(octagon(0f), Color.White)
            drawPath(octagon(this.size.width * 0.06f), Color(0xFFD52B1E))
        }
        // The word on the sign itself, the same everywhere.
        Text("STOP", color = Color.White, fontSize = (size.value * 0.27f).sp, lineHeight = (size.value * 0.3f).sp,
            fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/**
 * The road the rider is on, in a little card of its own: its number and, on a priority road, the
 * priority sign (Settings → Display → Road info). A junction just ahead where the rider gives way
 * or stops adds its sign and how far it is, with or without a route: this card is the one place for it.
 */
@Composable
fun CurrentRoadCard(
    road: com.curveon.data.road.CurrentRoad, modifier: Modifier = Modifier, giveWay: com.curveon.data.routing.GiveWayAlert? = null,
    /** Settings → Display → Road info size: the card and what is on it, 1 is normal. */
    scale: Float = 1f,
    /** The road's name next to the signs (for a moment after a tap on the card). */
    showName: Boolean = false
) {
    // While the name shows, the road's signs grow, and the name wraps under them when it doesn't fit beside.
    val sign = if (showName && road.name != null) scale * 1.6f else scale
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    FlowRow(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CockpitSurface.copy(alpha = 0.92f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp * scale, vertical = 8.dp * scale)
            .testTag("current_road"),
        horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
        verticalArrangement = Arrangement.spacedBy(4.dp * scale)
    ) {
        road.ref?.let { RoadShield(it, 26.dp * sign, Modifier.align(Alignment.CenterVertically)) }
        if (road.priority) PriorityRoadSign(30.dp * sign, Modifier.align(Alignment.CenterVertically))
        if (showName && road.name != null) Text(road.name, color = TextPrimary, fontSize = (16 * scale).sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterVertically).testTag("current_road_name"))
        giveWay?.let { g ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterVertically).testTag("free_give_way")) {
                if (g.stop) StopSign(30.dp * scale) else GiveWaySign(30.dp * scale)
                if (g.distanceMeters >= 10) {
                    Spacer(Modifier.width(6.dp * scale))
                    Text(stringResource(R.string.give_way_in, (g.distanceMeters / 10).toInt() * 10),
                        color = TextPrimary, fontSize = (15 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SpeedLimitSign(limit: Int, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(SpeedLimitWhite).border(size * 0.11f, SpeedLimitBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(limit.toString(), color = Color.Black, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Black)
    }
}
