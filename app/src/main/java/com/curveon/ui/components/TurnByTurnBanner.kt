package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.model.TurnInstruction
import com.curveon.data.model.TurnType
import com.curveon.ui.theme.*

/**
 * The next turn, as small as it can be: the arrow (a roundabout with the exit's number inside it), the distance
 * and the number sign of the road. It grows by what has to be in it (a wide sign) up to [maxWidth].
 * A tap folds out the words about the turn (and what comes after) for a few seconds.
 */
@Composable
fun TurnByTurnBanner(
    instruction: TurnInstruction,
    speedLimitKmh: Int = 0,
    modifier: Modifier = Modifier,
    maxWidth: Dp = Dp.Unspecified
) {
    var open by remember(instruction.index) { mutableStateOf(false) }
    LaunchedEffect(open, instruction.index) { if (open) { delay(8000); open = false } }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .background(CockpitSurface.copy(alpha = 0.95f))
            .border(1.5.dp, ElectricCyanDim, shape)
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag("turn_by_turn_banner")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Big directional maneuver icon. A roundabout is three arrows round a wide ring, as on the Norwegian sign with the exit's number clear inside it.
            val roundabout = instruction.type == TurnType.ROUNDABOUT
            Box(
                modifier = Modifier.size(if (roundabout) 64.dp else 52.dp).clip(RoundedCornerShape(12.dp)).background(ElectricCyan).padding(if (roundabout) 0.dp else 6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (roundabout) {
                    RoundaboutRing(instruction.leftHandTraffic, Modifier.fillMaxSize())
                    if (instruction.roundaboutExit > 0) Text(
                        instruction.roundaboutExit.toString(), color = Color(0xFF00363D), fontSize = 27.sp, fontWeight = FontWeight.Black, lineHeight = 27.sp
                    )
                } else Icon(
                    imageVector = getTurnIcon(instruction.type),
                    contentDescription = instruction.text,
                    tint = Color(0xFF00363D),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = formatDistance(instruction.distanceMeters),
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = (-0.5).sp,
                maxLines = 1
            )
            instruction.roadRef?.let {
                Spacer(modifier = Modifier.width(12.dp))
                RoadShield(it, 38.dp)
            }
        }
        if (open) Text(
            text = instruction.text,
            color = ElectricCyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
        )
    }
}

/**
 * Three curved arrows chasing each other round a wide ring, as on the Norwegian roundabout sign, with the
 * whole middle left free. Against the clock where they drive on the right, with it where they drive on the left.
 */
@Composable
private fun RoundaboutRing(leftHandTraffic: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val ink = Color(0xFF00363D)
        val stroke = size.minDimension * 0.075f
        val r = size.minDimension * 0.4f
        val c = center
        val head = size.minDimension * 0.17f
        val dir = if (leftHandTraffic) 1f else -1f // clockwise on screen is positive
        // Three arcs of 80° with 40° between them, where each arrow's head sits.
        for (k in 0..2) {
            val start = if (leftHandTraffic) -70f + k * 120f else 10f + k * 120f
            val sweep = dir * 80f
            drawArc(ink, start, sweep, false, topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r),
                size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            val end = Math.toRadians((start + sweep).toDouble())
            val tip = androidx.compose.ui.geometry.Offset(c.x + r * kotlin.math.cos(end).toFloat(), c.y + r * kotlin.math.sin(end).toFloat())
            // The way of travel at the tip, and the normal across it.
            val tx = -dir * kotlin.math.sin(end).toFloat(); val ty = dir * kotlin.math.cos(end).toFloat()
            val nx = kotlin.math.cos(end).toFloat(); val ny = kotlin.math.sin(end).toFloat()
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(tip.x + tx * head, tip.y + ty * head)
                lineTo(tip.x + nx * head * 0.8f, tip.y + ny * head * 0.8f)
                lineTo(tip.x - nx * head * 0.8f, tip.y - ny * head * 0.8f)
                close()
            }
            drawPath(path, ink)
        }
    }
}

@Composable
private fun formatDistance(meters: Int): String =
    if (meters >= 1000) stringResource(R.string.distance_km, meters / 1000f) else stringResource(R.string.distance_m_int, meters)

private fun getTurnIcon(type: TurnType): ImageVector {
    return when (type) {
        TurnType.START -> Icons.Default.Navigation
        TurnType.STRAIGHT -> Icons.Default.Straight
        TurnType.SLIGHT_LEFT -> Icons.Default.TurnSlightLeft
        TurnType.LEFT -> Icons.Default.TurnLeft
        TurnType.SHARP_LEFT -> Icons.Default.TurnSharpLeft
        TurnType.SLIGHT_RIGHT -> Icons.Default.TurnSlightRight
        TurnType.RIGHT -> Icons.Default.TurnRight
        TurnType.SHARP_RIGHT -> Icons.Default.TurnSharpRight
        TurnType.U_TURN -> Icons.Default.RotateLeft
        TurnType.ROUNDABOUT -> Icons.Default.Autorenew
        TurnType.DESTINATION -> Icons.Default.Flag
    }
}
