package com.curveon.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.curveon.R
import com.curveon.data.model.TelemetryData
import com.curveon.ui.theme.*

/** The speed limit sign's normal size; the card beside it is sized for this. */
val SPEED_SIGN_BASE = 36.dp

/**
 * Speed, the signed limit when known and the clock, in the map's top-right corner (always, not
 * only in full screen: the dashboard's side has the engine panel and music). A speed camera ahead
 * adds a line underneath. (A junction where the rider gives way shows in the
 * road card, see [CurrentRoadCard], not here.)
 */
@Composable
fun MapSpeedCluster(
    /** Read here, so a GPS fix redraws just this card and not whatever holds it. */
    telemetryNow: () -> TelemetryData,
    camera: com.curveon.data.routing.CameraAlert?,
    modifier: Modifier = Modifier,
    /** Settings → Display: the height above sea level under the speed. */
    showAltitude: Boolean = false,
    /** Settings → Display: the clock, and this device's battery to the right of it. */
    showClock: Boolean = true,
    showBattery: Boolean = false,
    /** The linked phone's battery (percent, charging); null when no companion phone is connected. */
    phoneBattery: Pair<Int, Boolean>? = null,
    /** Settings → Display: the phone's battery shown all the time (else only for a moment after a tap). */
    showPhoneBattery: Boolean = false,
    /** The limit sign's size (Settings → Display). Over [SPEED_SIGN_BASE] it sticks out over the card's top and bottom. */
    signSize: Dp = SPEED_SIGN_BASE
) {
    val telemetry = telemetryNow()
    val limit = camera?.maxSpeedKmh ?: telemetry.speedLimitKmh
    val over = limit > 0 && telemetry.speedKmh > limit + 3
    val speedColor by animateColorAsState(if (over) WarningRed else TextPrimary, label = "speed")
    val border = when {
        over -> WarningRed
        camera != null -> NeonAmber
        else -> CockpitBorder
    }
    // No clip, and the edge drawn behind the content: a big sign sticks out over the card's edges and
    // must lie on top of the edge line, not under it.
    val sticksOut = ((signSize - SPEED_SIGN_BASE) / 2).coerceAtLeast(0.dp)
    // A tap shows the clock and batteries for a few seconds, even those switched off in Settings.
    var peek by remember { mutableStateOf(false) }
    LaunchedEffect(peek) { if (peek) { delay(5_000); peek = false } }
    Box(modifier.clickable(interactionSource = null, indication = null) { peek = !peek }.testTag("full_map_speed_cluster")) {
        Box(Modifier.matchParentSize().background(CockpitSurface.copy(alpha = 0.92f), RoundedCornerShape(16.dp)).border(1.dp, border, RoundedCornerShape(16.dp)))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(telemetry.displaySpeed.toString(), color = speedColor, fontSize = 32.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Black, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.testTag("map_speed"))
                if (limit > 0) SpeedLimitSign(if (telemetry.useMph) com.curveon.data.model.TelemetryData.mphLimit(limit) else limit, signSize,
                    modifier = Modifier.layout { measurable, constraints ->
                        // Takes the room of a normal sign and is centred on it; the rest sticks out.
                        val p = measurable.measure(constraints)
                        val base = SPEED_SIGN_BASE.roundToPx()
                        layout(p.width, minOf(p.height, base)) { p.place(0, (minOf(p.height, base) - p.height) / 2) }
                    })
                if (showClock || peek) Clock(17.sp)
                if (showBattery || peek) BatteryLevel()
                if (showPhoneBattery || peek) phoneBattery?.let { (p, c) -> PhoneBatteryLevel(p, c) }
            }
            // A lost GPS fix shows at the rider's arrow (see CurveonMapView), not here: the card keeps its height.
            val below = camera != null || (showAltitude && telemetry.hasGpsFix)
            if (below && limit > 0) Spacer(Modifier.height(sticksOut))
            when {
                camera != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhotoCamera, stringResource(R.string.speed_camera), tint = NeonAmber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.speed_camera_in, (camera.distanceMeters / 10).toInt() * 10),
                        color = NeonAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            if (showAltitude && telemetry.hasGpsFix) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("map_altitude")) {
                Icon(Icons.Default.Terrain, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (telemetry.useMph) stringResource(R.string.altitude_ft, (telemetry.altitudeMeters * 3.28084).toInt())
                    else stringResource(R.string.altitude_m, telemetry.altitudeMeters),
                    color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
        }
        }
    }
}

/**
 * This device's battery as a slim upright cell: filled to the level, green while charging, red when
 * low. Listens to the system's battery broadcast only while shown.
 */
@Composable
fun BatteryLevel(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var level by remember { mutableFloatStateOf(-1f) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        fun read(i: android.content.Intent?) {
            i ?: return
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) / scale.toFloat()
            charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) = read(i)
        }
        read(context.registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)))
        onDispose { context.unregisterReceiver(receiver) }
    }
    if (level < 0f) return
    val fill = when {
        charging -> EcoGreen
        level < 0.15f -> WarningRed
        else -> TextSecondary
    }
    val percent = (level * 100).toInt()
    androidx.compose.foundation.Canvas(
        modifier.size(width = 11.dp, height = 20.dp).testTag("battery")
            .semantics { contentDescription = "$percent %" }
    ) {
        val stroke = 1.5.dp.toPx()
        val cap = 2.dp.toPx()
        // The terminal nub on top, then the cell and its fill from the bottom.
        drawRect(TextSecondary, topLeft = Offset(size.width * 0.3f, 0f), size = Size(size.width * 0.4f, cap))
        val body = Size(size.width - stroke, size.height - cap - stroke)
        drawRoundRect(TextSecondary, topLeft = Offset(stroke / 2, cap + stroke / 2), size = body,
            cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(stroke))
        // The fill sits a small gap inside the outline.
        val gap = stroke + 1.dp.toPx()
        val top = cap + gap
        val bottom = size.height - gap
        val h = (bottom - top) * level.coerceIn(0.05f, 1f)
        drawRect(fill, topLeft = Offset(gap, bottom - h), size = Size(size.width - gap * 2, h))
    }
}

/**
 * The linked phone's battery (the one in the pocket, running the companion): a slim phone filled to its
 * level, so it can't be taken for this device's cell beside it. Green while charging, red when low.
 */
@Composable
fun PhoneBatteryLevel(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    if (percent < 0) return
    val level = percent / 100f
    val fill = when {
        charging -> EcoGreen
        level < 0.15f -> WarningRed
        else -> TextSecondary
    }
    val label = stringResource(R.string.phone_battery_level, percent)
    androidx.compose.foundation.Canvas(
        modifier.size(width = 12.dp, height = 20.dp).testTag("phone_battery").semantics { contentDescription = label }
    ) {
        val stroke = 1.5.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        drawRoundRect(TextSecondary, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = radius, style = Stroke(stroke))
        // The fill from the bottom, under a thicker top edge like a phone's earpiece end.
        val gap = stroke + 1.dp.toPx()
        val top = gap + 2.5.dp.toPx()
        val bottom = size.height - gap
        val h = (bottom - top) * level.coerceIn(0.05f, 1f)
        drawRect(fill, topLeft = Offset(gap, bottom - h), size = Size(size.width - gap * 2, h))
        drawLine(TextSecondary, Offset(size.width * 0.35f, gap + 0.8.dp.toPx()), Offset(size.width * 0.65f, gap + 0.8.dp.toPx()), 1.dp.toPx())
    }
}

/** HH:mm, next to the speed (so it doesn't take room from the rail's buttons). */
@Composable
fun Clock(fontSize: TextUnit, modifier: Modifier = Modifier) {
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    // The text, not the time: a tick that stays within the minute changes nothing and redraws nothing.
    var text by remember { mutableStateOf(format.format(Date())) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        while (true) {
            text = format.format(Date())
            delay(10_000)
            lifecycle.untilStarted()
        }
    }
    Text(
        text,
        color = TextSecondary, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.testTag("clock")
    )
}
