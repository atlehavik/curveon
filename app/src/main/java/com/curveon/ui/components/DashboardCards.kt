package com.curveon.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.bluetooth.MediaApp
import com.curveon.data.bluetooth.VoiceReply
import androidx.compose.ui.res.stringResource
import com.curveon.R
import com.curveon.data.model.MusicTrack
import com.curveon.data.model.NotificationItem
import com.curveon.data.model.TelemetryData
import com.curveon.protocol.Message.VoiceState
import com.curveon.ui.theme.*

private val CardShape = RoundedCornerShape(20.dp)
private val OnCyan = Color(0xFF00363D)

@Composable
internal fun DashCard(modifier: Modifier = Modifier, border: Color = CockpitBorder, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .clip(CardShape)
            .background(CockpitSurface)
            .border(1.dp, border, CardShape),
        content = content
    )
}

// ---------------------------------------------------------------------------------- speed

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
    Box(modifier.clickable(interactionSource = null, indication = null) { peek = !peek }.animateContentSize().testTag("full_map_speed_cluster")) {
        Box(Modifier.matchParentSize().background(CockpitSurface.copy(alpha = 0.92f), RoundedCornerShape(16.dp)).border(1.dp, border, RoundedCornerShape(16.dp)))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(telemetry.displaySpeed.toString(), color = speedColor, fontSize = 32.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Black, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.testTag("map_speed"))
                if (limit > 0) SpeedLimitSign(if (telemetry.useMph) (limit * 0.621371f).toInt() else limit, signSize,
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
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CockpitSurface.copy(alpha = 0.92f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp * scale, vertical = 8.dp * scale)
            .testTag("current_road"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp * scale)
    ) {
        road.ref?.let { RoadShield(it, 26.dp * scale) }
        if (road.priority) PriorityRoadSign(30.dp * scale)
        if (showName && road.name != null) Text(road.name, color = TextPrimary, fontSize = (16 * scale).sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("current_road_name"))
        giveWay?.let { g ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("free_give_way")) {
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

// ---------------------------------------------------------------------------------- media

/**
 * Cover art fills the card, like Android Auto's media card when nothing else needs the space.
 * With more than one media app on the phone, flip the card sideways to switch between them;
 * small dots at the top show there are more.
 */
@Composable
fun FullMediaCard(
    track: MusicTrack?,
    art: Bitmap?,
    phoneState: CompanionConnectionState,
    phoneName: String,
    apps: List<MediaApp>,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPlayApp: (String) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Why no music can come (notification access off on the phone), and what tapping does about it (null: nothing to do here). */
    blocked: String? = null,
    onFixBlocked: (() -> Unit)? = null
) {
    val current = apps.indexOfFirst { it.name == track?.sourceApp }
    val switchable = track != null && apps.size > 1
    val switchTo: (Int) -> Unit = { step -> onPlayApp(apps[Math.floorMod(current + step, apps.size)].packageName) }
    DashCard(
        modifier.fillMaxWidth()
            .then(if (switchable) Modifier.flipToSwitch(onNext = { switchTo(1) }, onPrevious = { switchTo(-1) }) else Modifier)
            .testTag("media_card_full")
    ) {
        if (track == null) {
            val connected = phoneState == CompanionConnectionState.CONNECTED
            val canRetry = phoneState == CompanionConnectionState.DISCONNECTED || phoneState == CompanionConnectionState.UNREACHABLE ||
                phoneState == CompanionConnectionState.CONNECTING
            // Connected but silent: pick an app, or tap to make the phone resume whatever played last.
            // Not connected: tap to try right now (e.g. sharing was just turned on on the phone).
            val stuck = connected && blocked != null
            Box(Modifier.matchParentSize().clickable(enabled = if (stuck) onFixBlocked != null else (connected && apps.isEmpty()) || canRetry,
                onClick = if (stuck) ({ onFixBlocked?.invoke(); Unit }) else if (connected) onPlayPause else onReconnect)) {
                NothingPlaying(phoneState, phoneName, choosing = connected && apps.isNotEmpty() && !stuck, blocked = if (connected) blocked else null)
                if (connected && apps.isNotEmpty()) {
                    Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        apps.take(4).forEach { app ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                                MediaAppIcon(app, 56.dp, current = false) { onPlayApp(app.packageName) }
                                Text(app.name, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            return@DashCard
        }
        if (art != null) {
            Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(CockpitSurfaceVariant, CockpitBackground))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(96.dp))
            }
        }
        // Darken the lower half so text and buttons stay readable on any cover.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color(0xF2000000))))
        if (switchable) {
            Row(Modifier.align(Alignment.TopCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                apps.indices.forEach { i ->
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == current) ElectricCyan else Color(0x88FFFFFF)))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)) {
            Text(track.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artist, track.sourceApp).filter { it.isNotEmpty() }.joinToString(" · "),
                color = Color(0xFFD0D7E0), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (track.durationSeconds > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { track.progressFraction },
                    color = ElectricCyan, trackColor = Color(0x55FFFFFF),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Default.SkipPrevious, stringResource(R.string.media_previous), 56.dp, Color(0x33FFFFFF), Color.White, onPrevious)
                RoundButton(if (track.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.media_play_pause), 72.dp, ElectricCyan, OnCyan, onPlayPause)
                RoundButton(Icons.Default.SkipNext, stringResource(R.string.media_next), 56.dp, Color(0x33FFFFFF), Color.White, onNext)
            }
        }
    }
}

/** One-row player used when a notification or reply needs the space. */
@Composable
fun MiniMediaCard(track: MusicTrack, art: Bitmap?, onPlayPause: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier, onCollapse: (() -> Unit)? = null) {
    DashCard(modifier.fillMaxWidth().testTag("media_card_mini")) {
        Row(Modifier.fillMaxWidth().align(Alignment.Center).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant)
                .then(if (onCollapse != null) Modifier.clickable(onClick = onCollapse) else Modifier), contentAlignment = Alignment.Center) {
                if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                else Icon(Icons.Default.MusicNote, null, tint = TextMuted)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundButton(if (track.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.media_play_pause), 48.dp, ElectricCyan, OnCyan, onPlayPause)
            Spacer(Modifier.width(6.dp))
            RoundButton(Icons.Default.SkipNext, stringResource(R.string.media_next), 48.dp, CockpitSurfaceVariant, TextPrimary, onNext)
        }
    }
}

/** The music on the map, folded in: just the cover (a note without one). A tap opens [MiniMediaCard]. */
@Composable
fun MediaPillIcon(track: MusicTrack, art: Bitmap?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.size(MEDIA_ICON_SIZE).clip(shape).background(CockpitSurface.copy(alpha = 0.95f))
            .border(1.dp, if (track.isPlaying) ElectricCyan else CockpitBorder, shape).clickable(onClick = onClick)
            .testTag("media_pill_icon"),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) Image(art.asImageBitmap(), track.title, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Icon(Icons.Default.MusicNote, track.title, tint = TextMuted, modifier = Modifier.size(28.dp))
        // What it is doing now, in the corner.
        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Icon(if (track.isPlaying) Icons.Default.GraphicEq else Icons.Default.Pause, null, tint = if (track.isPlaying) ElectricCyan else Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/** The folded music icon's size; the turn card beside it and the map buttons above it make room for this. */
val MEDIA_ICON_SIZE = 56.dp

/** A media app on the phone; the one being shown gets a cyan ring. */
@Composable
private fun MediaAppIcon(app: MediaApp, size: Dp, current: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xCC1A1F26))
            .border(if (current) 3.dp else 1.dp, if (current) ElectricCyan else Color(0x55FFFFFF), CircleShape)
            .clickable(onClick = onClick).padding(size * 0.14f).testTag("media_app_${app.packageName}"),
        contentAlignment = Alignment.Center
    ) {
        if (app.icon != null) Image(app.icon.asImageBitmap(), app.name, modifier = Modifier.fillMaxSize())
        else Text(app.name.take(1), color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun BoxScope.NothingPlaying(state: CompanionConnectionState, phoneName: String, choosing: Boolean, blocked: String? = null) {
    Column(
        Modifier.align(if (choosing) Alignment.TopCenter else Alignment.Center).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            if (state == CompanionConnectionState.CONNECTED) Icons.Default.MusicOff else Icons.Default.PhonelinkOff,
            null, tint = TextMuted, modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (blocked != null) blocked else when (state) {
                CompanionConnectionState.CONNECTED -> stringResource(if (choosing) R.string.media_nothing_pick else R.string.media_nothing_tap, phoneName)
                CompanionConnectionState.CONNECTING -> stringResource(R.string.phone_connecting_to, phoneName)
                // Curveon on a phone: that phone itself is the obvious choice.
                CompanionConnectionState.NO_PHONE_SELECTED -> stringResource(
                    if (androidx.compose.ui.platform.LocalContext.current.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY))
                        R.string.media_no_phone_this else R.string.media_no_phone
                )
                CompanionConnectionState.NO_PERMISSION -> stringResource(R.string.media_no_permission)
                CompanionConnectionState.BLUETOOTH_OFF -> stringResource(R.string.bluetooth_off)
                CompanionConnectionState.DISCONNECTED -> stringResource(R.string.media_disconnected)
                CompanionConnectionState.UNREACHABLE -> stringResource(R.string.media_unreachable, phoneName)
                CompanionConnectionState.DEMO -> stringResource(R.string.media_nothing)
            },
            color = TextSecondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------------------------- notifications

/**
 * The newest message, with big glove-friendly Reply / Dismiss (or call actions). Dismiss clears it
 * on the phone too; swiping it sideways only hides it here and leaves it unread on the phone.
 */
@Composable
fun NotificationCard(
    notifications: List<NotificationItem>,
    onReply: (NotificationItem) -> Unit,
    onDismiss: (NotificationItem) -> Unit,
    onSwipe: (NotificationItem) -> Unit,
    onAction: (NotificationItem, Int) -> Unit,
    onRead: (NotificationItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var index by remember(notifications.size) { mutableIntStateOf(0) }
    val n = notifications.getOrNull(index) ?: notifications.firstOrNull() ?: return
    DashCard(modifier.fillMaxWidth().swipeAway(n.id) { onSwipe(n) }.testTag("notification_card"), border = if (n.isIncomingCall) EcoGreen else NeonAmber.copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(n, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.senderName, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${n.appName} · ${ago(n.timestampMs)}", color = TextSecondary, fontSize = 12.sp, maxLines = 1)
                }
                // Read aloud in the headset, so the rider needn't take their eyes off the road.
                if (!n.isIncomingCall) Box(
                    Modifier.size(52.dp).clip(CircleShape).background(CockpitSurfaceVariant).clickable { onRead(n) }.testTag("notification_read_btn"),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.VolumeUp, stringResource(R.string.read_aloud), tint = ElectricCyan, modifier = Modifier.size(28.dp)) }
                if (notifications.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(NeonAmber).clickable { index = (index + 1) % notifications.size }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("${index + 1}/${notifications.size}  ›", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black) }
                }
            }
            Spacer(Modifier.height(10.dp))
            // The message gets what height is left, so a short card (a phone upright) cuts the text, never the buttons.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Text(
                    n.message, color = TextPrimary, fontSize = 17.sp, lineHeight = 22.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (n.isIncomingCall && n.actions.isNotEmpty()) {
                    n.actions.take(2).forEachIndexed { i, title ->
                        val decline = title.contains("decline", true) || title.contains("avvis", true) || title.contains("hang", true)
                        PillButton(if (decline) Icons.Default.CallEnd else Icons.Default.Call, title,
                            if (decline) WarningRed else EcoGreen, Color.Black, Modifier.weight(1f)) { onAction(n, i) }
                    }
                } else {
                    if (n.canReply) PillButton(Icons.Default.Mic, stringResource(R.string.reply), ElectricCyan, OnCyan, Modifier.weight(1f).testTag("notification_reply_btn")) { onReply(n) }
                    PillButton(Icons.Default.Close, stringResource(R.string.dismiss), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f).testTag("notification_dismiss_btn")) { onDismiss(n) }
                }
            }
        }
    }
}

/**
 * Flip the card like a playing card to go to the next (drag left) or previous (drag right) media
 * app. It turns with the finger; let go past ~40° and it turns edge-on, switches, and the new app's
 * card (shown at once from what the tablet remembers) turns in from the other side. Less than that
 * and it falls back.
 */
private fun Modifier.flipToSwitch(onNext: () -> Unit, onPrevious: () -> Unit): Modifier = composed {
    val angle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(1) }
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    var dragged by remember { mutableFloatStateOf(0f) }
    this
        .onSizeChanged { width = it.width.coerceAtLeast(1) }
        .graphicsLayer {
            rotationY = angle.value
            cameraDistance = 14f * density
            // A turning card gets a touch smaller and darker, which sells the depth.
            val turned = kotlin.math.abs(angle.value) / 90f
            scaleX = 1f - 0.06f * turned
            scaleY = 1f - 0.06f * turned
            alpha = 1f - 0.35f * turned
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = {
                    val target = angle.value
                    scope.launch {
                        if (kotlin.math.abs(target) < 40f) {
                            angle.animateTo(0f, spring(dampingRatio = 0.6f))
                            return@launch
                        }
                        val sign = kotlin.math.sign(target)
                        angle.animateTo(90f * sign, tween(90, easing = FastOutLinearInEasing))
                        if (sign < 0) next() else previous()
                        angle.snapTo(-90f * sign)
                        angle.animateTo(0f, tween(160, easing = LinearOutSlowInEasing))
                    }
                },
                onDragCancel = { scope.launch { angle.animateTo(0f, spring()) } }
            ) { change, dx ->
                change.consume()
                dragged += dx
                // Dragging left turns the right edge towards you, like lifting a card from that side.
                scope.launch { angle.snapTo((dragged / width * 150f).coerceIn(-90f, 90f)) }
            }
        }
}

/** Drag sideways past a third of the width to fling the card away; less snaps back. */
private fun Modifier.swipeAway(key: Any, onSwiped: () -> Unit): Modifier = composed {
    val offset = remember(key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(1) }
    val swiped by rememberUpdatedState(onSwiped)
    this
        .onSizeChanged { width = it.width.coerceAtLeast(1) }
        .graphicsLayer {
            translationX = offset.value
            alpha = 1f - (kotlin.math.abs(offset.value) / width).coerceIn(0f, 1f) * 0.7f
        }
        .pointerInput(key) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (kotlin.math.abs(offset.value) > width / 3f) {
                            offset.animateTo(kotlin.math.sign(offset.value) * width, tween(150))
                            swiped()
                        } else offset.animateTo(0f, spring())
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } }
            ) { change, dx ->
                change.consume()
                scope.launch { offset.snapTo(offset.value + dx) }
            }
        }
}

/** Android Auto-style spoken reply: listen on the phone, show what was heard, send on confirm. */
@Composable
fun VoiceReplyCard(reply: VoiceReply, onSend: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    DashCard(modifier.fillMaxWidth().testTag("voice_reply_card"), border = ElectricCyan) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(if (reply.isSms) R.string.sms_to else R.string.reply_to, reply.senderName), color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (reply.state) {
                    VoiceState.LISTENING -> ListeningPulse(stringResource(R.string.speak_now))
                    VoiceState.PARTIAL -> Text("“${reply.text}…”", color = TextPrimary, fontSize = 20.sp, lineHeight = 26.sp)
                    VoiceState.RESULT -> Text("“${reply.text}”", color = TextPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    VoiceState.ERROR -> Text(reply.text.ifEmpty { stringResource(R.string.something_went_wrong) }, color = NeonAmber, fontSize = 18.sp)
                    VoiceState.SENT -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = EcoGreen, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.sent), color = EcoGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (reply.state) {
                    VoiceState.RESULT -> {
                        PillButton(Icons.Default.Send, stringResource(R.string.send), EcoGreen, Color.Black, Modifier.weight(1f).testTag("voice_send_btn"), onSend)
                        RoundButton(Icons.Default.Refresh, stringResource(R.string.say_again), 56.dp, CockpitSurfaceVariant, TextPrimary, onRetry)
                        RoundButton(Icons.Default.Close, stringResource(R.string.cancel), 56.dp, CockpitSurfaceVariant, TextPrimary, onCancel)
                    }
                    VoiceState.ERROR -> {
                        PillButton(Icons.Default.Mic, stringResource(R.string.try_again), ElectricCyan, OnCyan, Modifier.weight(1f), onRetry)
                        PillButton(Icons.Default.Close, stringResource(R.string.cancel), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f), onCancel)
                    }
                    VoiceState.SENT -> Unit
                    else -> PillButton(Icons.Default.Close, stringResource(R.string.cancel), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f), onCancel)
                }
            }
        }
    }
}

@Composable
private fun ListeningPulse(label: String) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.85f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).scale(pulse).clip(CircleShape).background(ElectricCyan), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Mic, null, tint = OnCyan, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = TextSecondary, fontSize = 15.sp)
    }
}

@Composable
internal fun Avatar(n: NotificationItem, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(CockpitSurfaceVariant), contentAlignment = Alignment.Center) {
        if (n.avatar != null) Image(n.avatar.asImageBitmap(), n.senderName, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Text(n.initials, color = ElectricCyan, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ago(ms: Long): String {
    val min = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> stringResource(R.string.ago_now)
        min < 60 -> stringResource(R.string.ago_min, min)
        else -> stringResource(R.string.ago_hours, min / 60)
    }
}

// ---------------------------------------------------------------------------------- buttons

@Composable
fun RoundButton(icon: ImageVector, description: String, size: Dp, background: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun PillButton(icon: ImageVector, label: String, background: Color, content: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(background).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = content, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
