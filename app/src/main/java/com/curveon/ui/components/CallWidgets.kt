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
import androidx.compose.ui.graphics.Brush
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
 * A ringing call: the one thing to decide about right now, so it is big and may cover anything. The
 * contact's photo fills the card (like cover art) with the name on top, or big initials without one.
 * Decline and Answer are the only buttons. Once answered it turns into [FloatingCallBubble].
 */
@Composable
fun CallCard(call: NotificationItem, onAction: (Int) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "ring").animateColor(
        EcoGreen, EcoGreen.copy(alpha = 0.25f), infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "border"
    )
    val buttons = callButtons(call, onAction, onDismiss)
    CallPhotoCard(call, "${call.message} · ${call.appName}", pulse, "call_card", modifier) {
        PillButton(Icons.Default.CallEnd, buttons.declineLabel, WarningRed, Color.White, Modifier.weight(1f).height(64.dp), buttons.onDecline)
        if (buttons.onOther != null) PillButton(Icons.Default.Call, buttons.otherLabel.orEmpty(), EcoGreen, Color.Black,
            Modifier.weight(1f).height(64.dp), buttons.onOther)
    }
}

/** The big call card: the contact's photo (or big initials), [status] and the name on top, [buttons] below. */
@Composable
private fun CallPhotoCard(
    call: NotificationItem, status: String, border: Color, tag: String, modifier: Modifier,
    buttons: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.clip(shape).background(CockpitSurface).border(3.dp, border, shape).testTag(tag)) {
        if (call.avatar != null) {
            Image(call.avatar.asImageBitmap(), call.senderName, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF2000000))))
        } else {
            // The initials fill what the name and buttons below leave; on a low card they are left out.
            BoxWithConstraints(Modifier.matchParentSize().padding(bottom = 176.dp), contentAlignment = Alignment.Center) {
                val room = maxHeight
                if (room >= 48.dp) Box(Modifier.heightIn(max = 140.dp).fillMaxHeight(0.85f).aspectRatio(1f).clip(CircleShape).background(CockpitSurfaceVariant), contentAlignment = Alignment.Center) {
                    Text(call.initials, color = EcoGreen, fontSize = ((room.value * 0.85f).coerceAtMost(140f) * 0.37f).sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneInTalk, null, tint = EcoGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(status, color = Color(0xFFD0D7E0), fontSize = 13.sp, maxLines = 1)
            }
            Text(call.senderName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
        }
    }
}

/** Least height an answered call needs for the big card (name, status and the buttons); below that it stays a row. */
private val BigOngoingCallHeight = 170.dp

/**
 * An answered call in the dashboard's card column (the map isn't full screen, so there is room for
 * it there instead of an icon over the map). [alone]: nothing else in the column wants the height
 * (no message), so [modifier] gives the call the free height and it is as big as a ringing call,
 * with Hang up and Speaker as big buttons; beside a message, or when there isn't the height, it is a
 * one-row card.
 */
@Composable
fun OngoingCallCard(call: NotificationItem, onAction: (Int) -> Unit, onDismiss: () -> Unit, alone: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (alone && maxHeight >= BigOngoingCallHeight) {
            val buttons = callButtons(call, onAction, onDismiss)
            CallPhotoCard(call, stringResource(R.string.call_in_progress), EcoGreen, "call_card_ongoing", Modifier.fillMaxSize()) {
                PillButton(Icons.Default.CallEnd, buttons.declineLabel, WarningRed, Color.White, Modifier.weight(1f).height(64.dp), buttons.onDecline)
                if (buttons.onOther != null) PillButton(Icons.Default.VolumeUp, buttons.otherLabel.orEmpty(), CockpitSurfaceVariant, Color.White,
                    Modifier.weight(1f).height(64.dp), buttons.onOther)
            }
        } else OngoingCallRow(call, onAction, onDismiss)
    }
}

/** The answered call as one row: the caller, and Hang up and Speaker as round buttons. */
@Composable
private fun OngoingCallRow(call: NotificationItem, onAction: (Int) -> Unit, onDismiss: () -> Unit) {
    val buttons = callButtons(call, onAction, onDismiss)
    DashCard(Modifier.fillMaxWidth().testTag("call_card_ongoing"), border = EcoGreen) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CallAvatar(call, 56.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneInTalk, null, tint = EcoGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.call_in_progress), color = Color(0xFFD0D7E0), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(call.senderName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CallButtonsRow(call, buttons)
        }
    }
}

/**
 * An answered call: never in the way, so just a green phone you can drag anywhere and leave there
 * (remembered). A tap opens the name with Hang up and Speaker for a few seconds. Fill the screen
 * area with [modifier]; it only takes touches where the icon is.
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
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val minX = margin
        val maxX = (w - sizePx - margin).coerceAtLeast(minX)
        val minY = margin
        val maxY = (h - sizePx - margin).coerceAtLeast(minY)
        val x = remember { Animatable((prefs.getFloat("fx", 0.02f) * w).coerceIn(minX, maxX)) }
        val y = remember { Animatable((prefs.getFloat("fy", 0.3f) * h).coerceIn(minY, maxY)) }
        LaunchedEffect(w, h) {
            x.snapTo(x.value.coerceIn(minX, maxX))
            y.snapTo(y.value.coerceIn(minY, maxY))
        }
        val scope = rememberCoroutineScope()
        var open by remember { mutableStateOf(false) }
        LaunchedEffect(open) { if (open) { delay(6000); open = false } }

        if (open) {
            val pillW = with(density) { minOf(300.dp.toPx(), w - 2 * margin) }
            val pillX = (if (x.value > w / 2) x.value + sizePx - pillW else x.value).coerceIn(margin, (w - pillW - margin).coerceAtLeast(margin))
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
                                    x.snapTo((x.value + drag.x).coerceIn(minX, maxX))
                                    y.snapTo((y.value + drag.y).coerceIn(minY, maxY))
                                }
                            },
                            onDragEnd = {
                                prefs.edit().putFloat("fx", if (w > 0) x.value / w else 0.02f).putFloat("fy", if (h > 0) y.value / h else 0.3f).apply()
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
