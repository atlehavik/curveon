package com.curveon.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.groups.GroupManager
import com.curveon.data.groups.GroupRider
import com.curveon.data.groups.nudgeKey
import com.curveon.protocol.Message
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay

/** The marker id of a friend on the map ([MapGroupRider.id]); a rider in the group is their rider id. */
private const val FRIEND_PREFIX = "friend:"

/**
 * What is known about a rider or friend tapped on the map ([mapId], see [MapGroupRider.id]): who, riding or stopped,
 * when last heard, how far away, battery; and "Get attention" for when they may not hear the intercom.
 */
@Composable
fun RiderInfoDialog(manager: GroupManager, mapId: String, myLat: Double?, myLon: Double?, useMph: Boolean, onDismiss: () -> Unit) {
    val friendRiders by manager.friendRiders.collectAsStateWithLifecycle()
    val friendPositions by manager.friendPositions.collectAsStateWithLifecycle()
    val members by manager.members.collectAsStateWithLifecycle()
    val positions by manager.positions.collectAsStateWithLifecycle()
    val activeId by manager.activeId.collectAsStateWithLifecycle()
    val myId by manager.myId.collectAsStateWithLifecycle()
    val friend = mapId.startsWith(FRIEND_PREFIX)
    val id = mapId.removePrefix(FRIEND_PREFIX)
    val rider: GroupRider? = if (friend) friendRiders[id] else members[id]
    val pos = if (friend) friendPositions[id] else positions[id]
    // Gone from the map (left, stopped sharing): nothing to show.
    if (rider == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val channelId = if (friend) id else activeId
    // A minute passing changes "seen 2 min ago" and "stopped 12 min".
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    Dialog(onDismissRequest = onDismiss) {
        HideSystemBars()
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CockpitSurface).padding(18.dp)
                .testTag("rider_info"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RiderAvatar(rider, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(rider.nickname, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    key(tick) { Text(movement(pos, useMph), color = if (pos?.stopped == true) NeonAmber else EcoGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
            key(tick) { pos?.let { Text(stringResource(R.string.rider_info_seen, seenAgo(it.timeMs)), color = TextSecondary, fontSize = 15.sp) } }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (pos != null && myLat != null && myLon != null) {
                    val m = com.curveon.routing.Geo.distance(myLat, myLon, pos.lat, pos.lon).toInt()
                    Text(
                        if (m < 1000) stringResource(R.string.sos_away_m, m) else stringResource(R.string.sos_away_km, m / 1000.0),
                        color = TextSecondary, fontSize = 15.sp
                    )
                }
                pos?.battery?.let { Text(stringResource(R.string.rider_info_battery, it), color = TextSecondary, fontSize = 15.sp) }
            }
            if (channelId != null) NudgeButton(manager, channelId, if (friend) null else id, rider.nickname)
            // Someone in the group who is not a friend yet: ask them, without a code or a link.
            if (!friend && id != myId && !manager.isFriend(rider)) FriendRequestButton(manager, id, rider.nickname)
        }
    }
}

/** "Add as friend" for a rider in the active group, and after it is sent "Request sent to Kari" (not told if they decline). */
@Composable
fun FriendRequestButton(manager: GroupManager, riderId: String, nickname: String) {
    val status by manager.friendRequestStatus.collectAsStateWithLifecycle()
    val state = status[riderId]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state == Message.NudgeState.SENT) {
            Text(stringResource(R.string.friend_request_sent, nickname), color = TextSecondary, fontSize = 15.sp, modifier = Modifier.testTag("friend_request_sent"))
        } else {
            OutlinedButton(
                onClick = { manager.sendFriendRequest(riderId) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("friend_request_send")
            ) {
                Icon(Icons.Default.PersonAdd, null, tint = ElectricCyan)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.friend_request_button), color = ElectricCyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            if (state == Message.NudgeState.FAILED) Text(stringResource(R.string.friend_request_failed), color = WarningRed, fontSize = 13.sp)
        }
    }
}

/** The small "Add as friend" for a row in the list of riders; "Request sent" shows under the name ([FriendRequestRowStatus]). */
@Composable
fun FriendRequestIconButton(manager: GroupManager, riderId: String) {
    val status by manager.friendRequestStatus.collectAsStateWithLifecycle()
    val sent = status[riderId] == Message.NudgeState.SENT
    IconButton(onClick = { manager.sendFriendRequest(riderId) }, enabled = !sent, modifier = Modifier.testTag("friend_request_row")) {
        Icon(if (sent) Icons.Default.Check else Icons.Default.PersonAdd, stringResource(R.string.friend_request_button), tint = if (sent) EcoGreen else ElectricCyan)
    }
}

/** How the rider's friend request to [riderId] went, for under their name in a list (nothing before one is sent). */
@Composable
fun FriendRequestRowStatus(manager: GroupManager, riderId: String, nickname: String) {
    val status by manager.friendRequestStatus.collectAsStateWithLifecycle()
    when (status[riderId]) {
        Message.NudgeState.SENT -> Text(stringResource(R.string.friend_request_sent, nickname), color = TextSecondary, fontSize = 13.sp)
        Message.NudgeState.FAILED -> Text(stringResource(R.string.friend_request_failed), color = WarningRed, fontSize = 13.sp)
        else -> Unit
    }
}

/** Riders standing together on the map, one marker: pick one to see them (and get their attention). */
@Composable
fun RiderPickerDialog(manager: GroupManager, mapIds: List<String>, useMph: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val friendRiders by manager.friendRiders.collectAsStateWithLifecycle()
    val friendPositions by manager.friendPositions.collectAsStateWithLifecycle()
    val members by manager.members.collectAsStateWithLifecycle()
    val positions by manager.positions.collectAsStateWithLifecycle()
    val riders = mapIds.mapNotNull { mapId ->
        val friend = mapId.startsWith(FRIEND_PREFIX)
        val id = mapId.removePrefix(FRIEND_PREFIX)
        val r = (if (friend) friendRiders[id] else members[id]) ?: return@mapNotNull null
        Triple(mapId, r, if (friend) friendPositions[id] else positions[id])
    }
    if (riders.isEmpty()) { LaunchedEffect(Unit) { onDismiss() }; return }
    Dialog(onDismissRequest = onDismiss) {
        HideSystemBars()
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CockpitSurface).padding(18.dp)
                .heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("rider_picker"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.riders_here), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            riders.forEach { (mapId, r, pos) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).clickable { onPick(mapId) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RiderAvatar(r, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(r.nickname, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(movement(pos, useMph), color = TextSecondary, fontSize = 13.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                }
            }
        }
    }
}

/** "Riding, 47 km/h", "Stopped for 12 min" or "No position yet". */
@Composable
private fun movement(p: Message.GroupPositionInfo?, useMph: Boolean): String {
    p ?: return stringResource(R.string.group_no_position).replaceFirstChar { it.uppercase() }
    if (p.paused) return stringResource(R.string.group_rider_paused).replaceFirstChar { it.uppercase() }
    val since = p.stoppedSince
    if (p.stopped && since != null) return stringResource(R.string.rider_info_stopped, ((System.currentTimeMillis() - since) / 60_000).coerceAtLeast(1).toInt())
    val kmh = p.speedMs * 3.6f
    return if (useMph) stringResource(R.string.rider_info_riding_mph, (kmh * 0.621371f).toInt()) else stringResource(R.string.rider_info_riding, kmh.toInt())
}

/** "now", "3 min ago", "2 h ago". */
@Composable
private fun seenAgo(timeMs: Long): String {
    val min = ((System.currentTimeMillis() - timeMs) / 60_000).toInt()
    return when {
        min < 1 -> stringResource(R.string.group_seen_now)
        min < 120 -> stringResource(R.string.group_seen_min, min)
        else -> stringResource(R.string.friends_seen_hours, min / 60)
    }
}

/** "Get attention", and how it went: sent, seen, or not sent. For a friend ([riderId] null) or a rider in the active group. */
@Composable
fun NudgeButton(manager: GroupManager, channelId: String, riderId: String?, nickname: String) {
    val status by manager.nudgeStatus.collectAsStateWithLifecycle()
    val state = status[nudgeKey(channelId, riderId)]
    val wait by manager.nudgeWait.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = { manager.nudge(channelId, riderId) },
            enabled = nudgeKey(channelId, riderId) !in wait,
            colors = ButtonDefaults.buttonColors(containerColor = NeonAmber),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("nudge_send")
        ) {
            Icon(Icons.Default.NotificationsActive, null, tint = Color(0xFF3A2400))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nudge_button), color = Color(0xFF3A2400), fontSize = 17.sp, fontWeight = FontWeight.Black)
        }
        state?.let { NudgeStatusText(it, nickname) }
    }
}

/** The small "Get attention" for a row in a list; how it went is [NudgeStatusText] under the name. */
@Composable
fun NudgeIconButton(manager: GroupManager, channelId: String, riderId: String?) {
    val status by manager.nudgeStatus.collectAsStateWithLifecycle()
    val seen = status[nudgeKey(channelId, riderId)] == Message.NudgeState.SEEN
    val waiting = nudgeKey(channelId, riderId) in manager.nudgeWait.collectAsStateWithLifecycle().value
    IconButton(onClick = { manager.nudge(channelId, riderId) }, enabled = !waiting, modifier = Modifier.testTag("nudge_row")) {
        Icon(Icons.Default.NotificationsActive, stringResource(R.string.nudge_button), tint = when { seen -> EcoGreen; waiting -> TextMuted; else -> NeonAmber })
    }
}

/** How the rider's nudge to [nickname] went, for under their name in a list (nothing before one is sent). */
@Composable
fun NudgeRowStatus(manager: GroupManager, channelId: String, riderId: String?, nickname: String) {
    val status by manager.nudgeStatus.collectAsStateWithLifecycle()
    status[nudgeKey(channelId, riderId)]?.let { NudgeStatusText(it, nickname) }
}

@Composable
private fun NudgeStatusText(state: Message.NudgeState, nickname: String) = when (state) {
    Message.NudgeState.SENT -> Text(stringResource(R.string.nudge_sent, nickname), color = TextSecondary, fontSize = 13.sp)
    Message.NudgeState.SEEN -> Text(stringResource(R.string.nudge_seen, nickname), color = EcoGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Message.NudgeState.FAILED -> Text(stringResource(R.string.nudge_failed), color = WarningRed, fontSize = 13.sp)
}

/**
 * Someone tries to reach the rider, who may not hear the intercom: over everything, with a sound and a buzz now and
 * then until "OK, I see it", which tells them. One at a time, oldest first.
 */
@Composable
fun NudgeAlert(manager: GroupManager) {
    val nudges by manager.nudges.collectAsStateWithLifecycle()
    val n = nudges.firstOrNull() ?: return
    val friendRiders by manager.friendRiders.collectAsStateWithLifecycle()
    val members by manager.members.collectAsStateWithLifecycle()
    // The picture as the rider knows it: the rider's in the group, or the friend's; else the one that came with it.
    val sent = remember(n.photoJpegBase64) {
        n.photoJpegBase64?.let { runCatching { val b = android.util.Base64.decode(it, android.util.Base64.DEFAULT); android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
    }
    val who = members[n.fromRiderId] ?: friendRiders[n.channelId] ?: GroupRider(n.fromRiderId, n.nickname, n.colour, null, sent, n.photoJpegBase64)
    val name = n.nickname.ifEmpty { who.nickname }
    NudgeSound(n.nudgeId)
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        HideSystemBars()
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF4A2E00)).padding(18.dp)
                .testTag("nudge_alert"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RiderAvatar(who, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nudge_alert_title, name), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.nudge_alert_text), color = Color(0xFFFFE0B2), fontSize = 15.sp)
                }
            }
            Button(
                onClick = { manager.answerNudge(n) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonAmber),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("nudge_ok")
            ) { Text(stringResource(R.string.nudge_alert_ok), color = Color(0xFF3A2400), fontSize = 17.sp, fontWeight = FontWeight.Black) }
        }
    }
}

/** A notification sound and two buzzes, three times over half a minute: enough to be noticed, not an alarm (that is SOS). */
@Composable
private fun NudgeSound(key: String) {
    val context = LocalContext.current
    LaunchedEffect(key) {
        val tone = runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            }
        }.getOrNull()
        val vibrator = vibrator(context)
        try {
            repeat(3) {
                runCatching { tone?.play() }
                runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1)) }
                delay(10_000)
            }
        } finally {
            runCatching { tone?.stop() }
        }
    }
}

private fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
