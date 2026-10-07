package com.curveon.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.bluetooth.MediaApp
import com.curveon.data.bluetooth.VoiceReply
import com.curveon.data.model.MusicTrack
import com.curveon.data.model.NotificationItem
import com.curveon.data.model.TelemetryData
import com.curveon.protocol.Message.VoiceState
import com.curveon.ui.theme.*

private val CardShape = RoundedCornerShape(20.dp)
private val OnCyan = Color(0xFF00363D)

@Composable
private fun DashCard(modifier: Modifier = Modifier, border: Color = CockpitBorder, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .clip(CardShape)
            .background(CockpitSurface)
            .border(1.dp, border, CardShape),
        content = content
    )
}

// ---------------------------------------------------------------------------------- speed

/**
 * Big speed, the signed limit when known, and trip/GPS (or RPM with OBD2) underneath. A speed
 * camera ahead takes over the bottom line. Tap for the ride log.
 */
@Composable
fun SpeedCard(
    telemetry: TelemetryData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    camera: com.curveon.data.routing.CameraAlert? = null
) {
    val limit = camera?.maxSpeedKmh ?: telemetry.speedLimitKmh
    val over = limit > 0 && telemetry.speedKmh > limit + 3
    val speedColor by animateColorAsState(if (over) WarningRed else TextPrimary, label = "speed")
    val border = when {
        over -> WarningRed
        camera != null -> NeonAmber
        else -> CockpitBorder
    }
    DashCard(modifier.fillMaxWidth().testTag("speed_card"), border = border) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        telemetry.displaySpeed.toString(), color = speedColor,
                        fontSize = 54.sp, lineHeight = 54.sp, fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(telemetry.speedUnit.lowercase(), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                if (camera != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhotoCamera, "Speed camera", tint = NeonAmber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Speed camera %d m".format((camera.distanceMeters / 10).toInt() * 10),
                        color = NeonAmber, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                } else Text(
                    when {
                        telemetry.isDeadReckoning -> "No GPS – estimating position"
                        telemetry.isObdConnected -> "%d rpm · %s".format(telemetry.rpm, tripText(telemetry))
                        telemetry.hasGpsFix -> tripText(telemetry)
                        else -> "Searching for GPS…"
                    },
                    color = if (telemetry.hasGpsFix) TextSecondary else NeonAmber,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1
                )
            }
            if (limit > 0) SpeedLimitSign(if (telemetry.useMph) (limit * 0.621371f).toInt() else limit, 64.dp)
        }
    }
}

private fun tripText(t: TelemetryData) =
    "Trip %.1f km · %d:%02d".format(t.tripDistanceKm, t.tripDurationMinutes / 60, t.tripDurationMinutes % 60)

@Composable
fun SpeedLimitSign(limit: Int, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(SpeedLimitWhite).border(size * 0.11f, SpeedLimitBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(limit.toString(), color = Color.Black, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Black)
    }
}

// ---------------------------------------------------------------------------------- media

/**
 * Cover art fills the card, like Android Auto's media card when nothing else needs the space.
 * With more than one media app on the phone, their icons sit on top to switch between them.
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
    modifier: Modifier = Modifier
) {
    DashCard(modifier.fillMaxWidth().testTag("media_card_full")) {
        if (track == null) {
            val connected = phoneState == CompanionConnectionState.CONNECTED
            val canRetry = phoneState == CompanionConnectionState.DISCONNECTED || phoneState == CompanionConnectionState.UNREACHABLE ||
                phoneState == CompanionConnectionState.CONNECTING
            // Connected but silent: pick an app, or tap to make the phone resume whatever played last.
            // Not connected: tap to try right now (e.g. sharing was just turned on on the phone).
            Box(Modifier.matchParentSize().clickable(enabled = (connected && apps.isEmpty()) || canRetry,
                onClick = if (connected) onPlayPause else onReconnect)) {
                NothingPlaying(phoneState, phoneName, choosing = connected && apps.isNotEmpty())
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
        if (apps.size > 1) {
            Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                apps.take(4).forEach { app ->
                    val current = app.name == track.sourceApp
                    MediaAppIcon(app, 44.dp, current) { if (!current) onPlayApp(app.packageName) }
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
                RoundButton(Icons.Default.SkipPrevious, "Previous", 56.dp, Color(0x33FFFFFF), Color.White, onPrevious)
                RoundButton(if (track.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", 72.dp, ElectricCyan, OnCyan, onPlayPause)
                RoundButton(Icons.Default.SkipNext, "Next", 56.dp, Color(0x33FFFFFF), Color.White, onNext)
            }
        }
    }
}

/** One-row player used when a notification or reply needs the space. */
@Composable
fun MiniMediaCard(track: MusicTrack, art: Bitmap?, onPlayPause: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    DashCard(modifier.fillMaxWidth().testTag("media_card_mini")) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant), contentAlignment = Alignment.Center) {
                if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                else Icon(Icons.Default.MusicNote, null, tint = TextMuted)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundButton(if (track.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", 48.dp, ElectricCyan, OnCyan, onPlayPause)
            Spacer(Modifier.width(6.dp))
            RoundButton(Icons.Default.SkipNext, "Next", 48.dp, CockpitSurfaceVariant, TextPrimary, onNext)
        }
    }
}

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
private fun BoxScope.NothingPlaying(state: CompanionConnectionState, phoneName: String, choosing: Boolean) {
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
            when (state) {
                CompanionConnectionState.CONNECTED -> if (choosing) "Nothing playing on $phoneName\nPick what to play" else "Nothing playing on $phoneName\nTap to play"
                CompanionConnectionState.CONNECTING -> "Connecting to $phoneName…"
                CompanionConnectionState.NO_PHONE_SELECTED -> "No phone linked.\nPick your phone in Settings → Phone."
                CompanionConnectionState.NO_PERMISSION -> "Allow Bluetooth access\nto link your phone."
                CompanionConnectionState.BLUETOOTH_OFF -> "Bluetooth is off"
                CompanionConnectionState.DISCONNECTED -> "Phone not connected.\nTap to connect now."
                CompanionConnectionState.UNREACHABLE -> "Can't reach $phoneName over Bluetooth.\nTap to try again, or turn Bluetooth off and on here."
                CompanionConnectionState.DEMO -> "Nothing playing"
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
    modifier: Modifier = Modifier
) {
    // A ringing call always comes first and gets its own full card.
    notifications.firstOrNull { it.isIncomingCall }?.let { call ->
        CallCard(call, onAction = { i -> onAction(call, i) }, onDismiss = { onSwipe(call) }, modifier = modifier)
        return
    }
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
                if (notifications.size > 1) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(NeonAmber).clickable { index = (index + 1) % notifications.size }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("${index + 1}/${notifications.size}  ›", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                n.message, color = TextPrimary, fontSize = 17.sp, lineHeight = 22.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (n.isIncomingCall && n.actions.isNotEmpty()) {
                    n.actions.take(2).forEachIndexed { i, title ->
                        val decline = title.contains("decline", true) || title.contains("avvis", true) || title.contains("hang", true)
                        PillButton(if (decline) Icons.Default.CallEnd else Icons.Default.Call, title,
                            if (decline) WarningRed else EcoGreen, Color.Black, Modifier.weight(1f)) { onAction(n, i) }
                    }
                } else {
                    if (n.canReply) PillButton(Icons.Default.Mic, "Reply", ElectricCyan, OnCyan, Modifier.weight(1f).testTag("notification_reply_btn")) { onReply(n) }
                    PillButton(Icons.Default.Close, "Dismiss", CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f).testTag("notification_dismiss_btn")) { onDismiss(n) }
                }
            }
        }
    }
}

/**
 * Incoming call. With a contact photo the photo fills the card (like cover art) with the name on
 * top; without one, big initials. Decline / Answer are the only buttons.
 */
@Composable
fun CallCard(call: NotificationItem, onAction: (Int) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "ring").animateColor(
        EcoGreen, EcoGreen.copy(alpha = 0.25f), infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "border"
    )
    val declineIndex = call.actions.indexOfFirst { isDecline(it) }.takeIf { it >= 0 }
    val answerIndex = call.actions.indices.firstOrNull { it != declineIndex }
    Box(
        modifier.fillMaxWidth().clip(CardShape).background(CockpitSurface).border(3.dp, pulse, CardShape).testTag("call_card")
    ) {
        if (call.avatar != null) {
            Image(call.avatar.asImageBitmap(), call.senderName, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF2000000))))
        } else {
            Box(Modifier.matchParentSize().padding(bottom = 140.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.heightIn(max = 140.dp).fillMaxHeight(0.85f).aspectRatio(1f).clip(CircleShape).background(CockpitSurfaceVariant), contentAlignment = Alignment.Center) {
                    Text(call.initials, color = EcoGreen, fontSize = 52.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneInTalk, null, tint = EcoGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("${call.message} · ${call.appName}", color = Color(0xFFD0D7E0), fontSize = 13.sp, maxLines = 1)
            }
            Text(call.senderName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(Icons.Default.CallEnd, call.actions.getOrNull(declineIndex ?: -1) ?: "Decline", WarningRed, Color.White,
                    Modifier.weight(1f).height(64.dp)) { if (declineIndex != null) onAction(declineIndex) else onDismiss() }
                if (answerIndex != null) PillButton(Icons.Default.Call, call.actions[answerIndex], EcoGreen, Color.Black,
                    Modifier.weight(1f).height(64.dp)) { onAction(answerIndex) }
            }
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

private fun isDecline(title: String) = listOf("decline", "avvis", "hang", "legg på", "reject").any { title.contains(it, ignoreCase = true) }

/** Android Auto-style spoken reply: listen on the phone, show what was heard, send on confirm. */
@Composable
fun VoiceReplyCard(reply: VoiceReply, onSend: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    DashCard(modifier.fillMaxWidth().testTag("voice_reply_card"), border = ElectricCyan) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Reply to ${reply.senderName}", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (reply.state) {
                    VoiceState.LISTENING -> ListeningPulse("Speak now…")
                    VoiceState.PARTIAL -> Text("“${reply.text}…”", color = TextPrimary, fontSize = 20.sp, lineHeight = 26.sp)
                    VoiceState.RESULT -> Text("“${reply.text}”", color = TextPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    VoiceState.ERROR -> Text(reply.text.ifEmpty { "Something went wrong" }, color = NeonAmber, fontSize = 18.sp)
                    VoiceState.SENT -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = EcoGreen, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Sent", color = EcoGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (reply.state) {
                    VoiceState.RESULT -> {
                        PillButton(Icons.Default.Send, "Send", EcoGreen, Color.Black, Modifier.weight(1f).testTag("voice_send_btn"), onSend)
                        RoundButton(Icons.Default.Refresh, "Say again", 56.dp, CockpitSurfaceVariant, TextPrimary, onRetry)
                        RoundButton(Icons.Default.Close, "Cancel", 56.dp, CockpitSurfaceVariant, TextPrimary, onCancel)
                    }
                    VoiceState.ERROR -> {
                        PillButton(Icons.Default.Mic, "Try again", ElectricCyan, OnCyan, Modifier.weight(1f), onRetry)
                        PillButton(Icons.Default.Close, "Cancel", CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f), onCancel)
                    }
                    VoiceState.SENT -> Unit
                    else -> PillButton(Icons.Default.Close, "Cancel", CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f), onCancel)
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
private fun Avatar(n: NotificationItem, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(CockpitSurfaceVariant), contentAlignment = Alignment.Center) {
        if (n.avatar != null) Image(n.avatar.asImageBitmap(), n.senderName, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Text(n.initials, color = ElectricCyan, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Black)
    }
}

private fun ago(ms: Long): String {
    val min = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "now"
        min < 60 -> "$min min"
        else -> "${min / 60} h"
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
