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
            // Big directional maneuver icon. A roundabout is two big arrows round the number of the exit to take.
            val roundabout = instruction.type == TurnType.ROUNDABOUT
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(ElectricCyan).padding(if (roundabout) 1.dp else 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getTurnIcon(instruction.type),
                    contentDescription = instruction.text,
                    tint = Color(0xFF00363D),
                    modifier = Modifier.size(if (roundabout) 50.dp else 36.dp)
                )
                if (roundabout && instruction.roundaboutExit > 0) Text(
                    instruction.roundaboutExit.toString(), color = Color(0xFF00363D), fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 26.sp
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
                RoadShield(it, 26.dp)
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
