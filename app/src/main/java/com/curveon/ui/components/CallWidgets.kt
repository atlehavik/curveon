package com.curveon.ui.components

import android.content.Context
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.model.NotificationItem
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How high a call card is: the same as the music pill and the turn card beside it. */
val CallCardHeight = 72.dp

private fun isDecline(title: String) = listOf("decline", "avvis", "hang", "legg på", "reject").any { title.contains(it, ignoreCase = true) }

/** A call's two buttons: the one that ends or declines it, and the other one (answer, or speaker). */
private class CallButtons(val declineLabel: String, val onDecline: () -> Unit, val otherLabel: String?, val onOther: (() -> Unit)?)

@Composable
private fun callButtons(call: NotificationItem, onAction: (Int) -> Unit, onDismiss: () -> Unit): CallButtons {
    val declineIndex = call.actions.indexOfFirst { isDecline(it) }.takeIf { it >= 0 }
    val otherIndex = call.actions.indices.firstOrNull { it != declineIndex }
    return CallButtons(
        declineLabel = call.actions.getOrNull(declineIndex ?: -1) ?: stringResource(R.string.decline),
        onDecline = { if (declineIndex != null) onAction(declineIndex) else onDismiss() },
        otherLabel = otherIndex?.let { call.actions[it] },
        onOther = otherIndex?.let { i -> { onAction(i) } }
    )
}

/** The caller as a small round picture, or their initials. */
@Composable
private fun CallAvatar(call: NotificationItem, size: androidx.compose.ui.unit.Dp) {
    if (call.avatar != null) Image(call.avatar.asImageBitmap(), call.senderName, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(CockpitSurfaceVariant), contentAlignment = Alignment.Center) {
        Text(call.initials, color = EcoGreen, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Black)
    }
}

/** Decline / hang up (red) and answer / speaker. */
@Composable
private fun CallButtonsRow(call: NotificationItem, buttons: CallButtons) {
    RoundButton(Icons.Default.CallEnd, buttons.declineLabel, 52.dp, WarningRed, Color.White, buttons.onDecline)
    if (buttons.onOther != null) {
        if (call.isOngoingCall) RoundButton(Icons.Default.VolumeUp, buttons.otherLabel.orEmpty(), 52.dp, CockpitSurfaceVariant, Color.White, buttons.onOther)
        else RoundButton(Icons.Default.Call, buttons.otherLabel.orEmpty(), 52.dp, EcoGreen, Color.Black, buttons.onOther)
    }
}

/**
 * A call as one slim row: the caller's small picture and name, and the two buttons. Ringing it pulses;
 * the buttons are Decline / Answer, and once answered Hang up / Speaker.
 */
@Composable
fun CallCard(call: NotificationItem, onAction: (Int) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "ring").animateColor(
        EcoGreen, if (call.isOngoingCall) EcoGreen else EcoGreen.copy(alpha = 0.25f), infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "border"
    )
    val shape = RoundedCornerShape(20.dp)
    val buttons = callButtons(call, onAction, onDismiss)
    Row(
        modifier.fillMaxWidth().height(CallCardHeight).clip(shape).background(CockpitSurface).border(3.dp, pulse, shape)
            .padding(horizontal = 10.dp).testTag("call_card"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CallAvatar(call, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(call.message, color = Color(0xFFD0D7E0), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(call.senderName, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
        }
        CallButtonsRow(call, buttons)
    }
}

/**
 * An answered call on the full map: a green phone you can drag anywhere (it settles against the left
 * edge, or the right one just left of the map's buttons, and is remembered). A tap opens the name
 * with Hang up and Speaker for a few seconds. Fill the map area with [modifier]; it only takes touches
 * where the icon is.
 */
@Composable
fun FloatingCallBubble(call: NotificationItem, onAction: (Int) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("call_bubble", Context.MODE_PRIVATE) }
    val density = LocalDensity.current
    val buttons = callButtons(call, onAction, onDismiss)
    BoxWithConstraints(modifier) {
        val sizePx = with(density) { BUBBLE_SIZE.toPx() }
        val margin = with(density) { 14.dp.toPx() }
        // The right edge stops left of the map's buttons (52 dp wide, 14 dp from the edge).
        val reserve = with(density) { (14.dp + 52.dp + 8.dp).toPx() }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val leftX = margin
        val rightX = (w - reserve - sizePx).coerceAtLeast(leftX)
        val minY = margin
        val maxY = (h - sizePx - margin).coerceAtLeast(minY)
        var right by remember { mutableStateOf(prefs.getBoolean("right", false)) }
        val x = remember { Animatable(if (right) rightX else leftX) }
        val y = remember { Animatable((prefs.getFloat("y", 0.3f) * h).coerceIn(minY, maxY)) }
        LaunchedEffect(w, h) {
            x.snapTo(if (right) rightX else leftX)
            y.snapTo(y.value.coerceIn(minY, maxY))
        }
        val scope = rememberCoroutineScope()
        var open by remember { mutableStateOf(false) }
        LaunchedEffect(open) { if (open) { delay(6000); open = false } }

        if (open) {
            val pillW = with(density) { minOf(300.dp.toPx(), w - 2 * margin) }
            val pillX = (if (right) x.value + sizePx - pillW else x.value).coerceIn(margin, (w - pillW - margin).coerceAtLeast(margin))
            val shape = RoundedCornerShape(36.dp)
            Row(
                Modifier.offset { IntOffset(pillX.roundToInt(), y.value.roundToInt()) }
                    .width(with(density) { pillW.toDp() }).height(BUBBLE_SIZE + 8.dp)
                    .shadow(6.dp, shape).clip(shape).background(CockpitSurface).border(3.dp, EcoGreen, shape)
                    .clickable { open = false }.padding(horizontal = 10.dp).testTag("call_bubble_open"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CallAvatar(call, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(call.message, color = Color(0xFFD0D7E0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(call.senderName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CallButtonsRow(call, buttons)
            }
        } else {
            Box(
                Modifier.offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }
                    .size(BUBBLE_SIZE).shadow(6.dp, CircleShape).clip(CircleShape).background(EcoGreen)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .pointerInput(w, h) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                scope.launch {
                                    x.snapTo((x.value + drag.x).coerceIn(leftX, rightX))
                                    y.snapTo((y.value + drag.y).coerceIn(minY, maxY))
                                }
                            },
                            onDragEnd = {
                                right = x.value > (leftX + rightX) / 2
                                scope.launch { x.animateTo(if (right) rightX else leftX, spring(dampingRatio = 0.7f)) }
                                prefs.edit().putBoolean("right", right).putFloat("y", if (h > 0) y.value / h else 0.3f).apply()
                            }
                        )
                    }
                    .clickable { open = true }
                    .testTag("call_bubble"),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PhoneInTalk, stringResource(R.string.call_in_progress), tint = Color.Black, modifier = Modifier.size(30.dp)) }
        }
    }
}

private val BUBBLE_SIZE = 56.dp
