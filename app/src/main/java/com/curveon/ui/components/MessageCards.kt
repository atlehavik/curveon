package com.curveon.ui.components

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
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.bluetooth.VoiceReply
import androidx.compose.ui.res.stringResource
import com.curveon.R
import com.curveon.data.model.NotificationItem
import com.curveon.protocol.Message.VoiceState
import com.curveon.ui.theme.*

private val OnCyan = Color(0xFF00363D)

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
internal fun Modifier.flipToSwitch(onNext: () -> Unit, onPrevious: () -> Unit): Modifier = composed {
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
