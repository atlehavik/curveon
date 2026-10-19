package com.curveon.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.groups.GroupManager
import com.curveon.data.groups.GroupRider
import com.curveon.phone.QrCode
import com.curveon.protocol.Message
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The icons a rider can pick for their marker; every device draws the same, and only the id travels. */
private val GroupIcons: Map<String, ImageVector> = linkedMapOf(
    "helmet" to Icons.Default.SportsMotorsports,
    "bike" to Icons.Default.TwoWheeler,
    "mountain" to Icons.Default.Terrain,
    "flag" to Icons.Default.Flag,
    "bolt" to Icons.Default.Bolt,
    "coffee" to Icons.Default.LocalCafe,
)

/** Colours to pick from, bright enough on both the day and the night map. */
private val GroupColours = listOf(0xFF00E5FF, 0xFFFF5252, 0xFF69F0AE, 0xFFFFD740, 0xFFE040FB, 0xFFFF9100).map { it.toInt() }

private const val SOS_HOLD_MS = 3_000
private const val SOS_COUNTDOWN_S = 10

/** The group ride dialog, opened from the map button. */
@Composable
fun GroupDialog(manager: GroupManager, onDismiss: () -> Unit) {
    val groups by manager.groups.collectAsStateWithLifecycle()
    val active = groups.firstOrNull { it.id == manager.activeId.value }
    CockpitDialog(
        title = active?.name ?: stringResource(R.string.group_title), icon = Icons.Default.Groups, accent = ElectricCyan,
        onDismiss = onDismiss, tag = "group_dialog"
    ) { GroupContent(manager, Modifier.fillMaxSize()) }
}

/** What there is to do about group rides: start or pick a group, see who is there, invite, pause, SOS. Also Settings → Group ride. */
@Composable
fun GroupContent(manager: GroupManager, modifier: Modifier = Modifier) {
    val groups by manager.groups.collectAsStateWithLifecycle()
    val activeId by manager.activeId.collectAsStateWithLifecycle()
    val sharing by manager.sharing.collectAsStateWithLifecycle()
    val connection by manager.connection.collectAsStateWithLifecycle()
    val members by manager.members.collectAsStateWithLifecycle()
    val myId by manager.myId.collectAsStateWithLifecycle()
    val positions by manager.positions.collectAsStateWithLifecycle()
    val invite by manager.invite.collectAsStateWithLifecycle()
    val active = groups.firstOrNull { it.id == activeId }
    var name by rememberSaveable { mutableStateOf("") }
    var showInvite by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<GroupRider?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { manager.requestGroups() }
    LaunchedEffect(Unit) {
        manager.errors.collect { code ->
            android.widget.Toast.makeText(context, context.getString(groupErrorText(code)), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // A countdown to sending the SOS, with a way out.
    LaunchedEffect(countdown != null) {
        while (true) {
            val now = countdown ?: break
            delay(1000)
            if (countdown == null) break // cancelled meanwhile
            if (now <= 1) { countdown = null; manager.sosStart(); break } else countdown = now - 1
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (active == null) {
            Text(stringResource(R.string.group_start_hint), color = TextSecondary, fontSize = 14.sp)
            OutlinedTextField(
                name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("group_name"),
                label = { Text(stringResource(R.string.group_name_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences)
            )
            Button(onClick = { manager.create(name.trim().ifEmpty { null }); name = ""; showInvite = true },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan), modifier = Modifier.testTag("group_start")) {
                Text(stringResource(R.string.group_start), color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.group_join_hint), color = TextMuted, fontSize = 13.sp)
        }

        if (groups.size > 1 || (groups.isNotEmpty() && active == null)) {
            SectionLabel(stringResource(R.string.group_switch))
            groups.forEach { g ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (g.id == activeId) ElectricCyan.copy(alpha = 0.18f) else CockpitSurfaceVariant)
                        .clickable { manager.select(g.id) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Groups, null, tint = if (g.id == activeId) ElectricCyan else TextSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text(g.name ?: stringResource(R.string.group_unnamed), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }
        }

        if (active != null) {
            val status = connection?.status ?: Message.GroupStatus.OFFLINE
            Text(
                stringResource(when (status) {
                    Message.GroupStatus.CONNECTED -> R.string.group_status_connected
                    Message.GroupStatus.OFFLINE -> R.string.group_status_offline
                    Message.GroupStatus.PAUSED -> R.string.group_status_paused
                    Message.GroupStatus.REMOVED -> R.string.group_status_removed
                }),
                color = when (status) {
                    Message.GroupStatus.CONNECTED -> EcoGreen
                    Message.GroupStatus.REMOVED -> WarningRed
                    else -> NeonAmber
                },
                fontSize = 14.sp, fontWeight = FontWeight.Bold
            )

            if (status != Message.GroupStatus.REMOVED) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.group_share_position), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                CockpitSwitch(sharing, { manager.setSharing(it) })
            }

            SectionLabel(stringResource(R.string.group_riders))
            members.values.sortedBy { if (it.id == myId) 0 else 1 }.forEach { r ->
                RiderRow(r, r.id == myId, positions[r.id], onRemove = if (active.admin && r.id != myId) ({ confirmRemove = r }) else null)
            }
            members[myId]?.let { me -> ProfileEditor(me) { n, c, i -> manager.setProfile(n, c, i, null) } }

            if (status != Message.GroupStatus.REMOVED) {
                OutlinedButton(onClick = { showInvite = !showInvite; if (showInvite) manager.requestInvite(active.id) }, modifier = Modifier.testTag("group_invite")) {
                    Icon(Icons.Default.QrCode2, null, tint = ElectricCyan)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.group_invite), color = ElectricCyan, fontWeight = FontWeight.Bold)
                }
                if (showInvite) InviteQr(invite)
            }

            if (status != Message.GroupStatus.REMOVED) SosButton(onTrigger = { countdown = SOS_COUNTDOWN_S })

            TextButton(onClick = { confirmLeave = true }) {
                Text(stringResource(R.string.group_leave), color = WarningRed, fontWeight = FontWeight.Bold)
            }
        }

    }

    if (confirmLeave && active != null) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { HideSystemBars(); Text(stringResource(R.string.group_leave_title, active.name ?: stringResource(R.string.group_unnamed))) },
        text = { Text(stringResource(R.string.group_leave_text)) },
        confirmButton = { TextButton(onClick = { manager.leave(active.id); confirmLeave = false }) { Text(stringResource(R.string.group_leave), color = WarningRed) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } }
    )
    confirmRemove?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { HideSystemBars(); Text(stringResource(R.string.group_remove_title, r.nickname)) },
            text = { Text(stringResource(R.string.group_remove_text, r.nickname)) },
            confirmButton = { TextButton(onClick = { activeId?.let { manager.removeRider(it, r.id) }; confirmRemove = null }) { Text(stringResource(R.string.group_remove), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    countdown?.let { left -> SosCountdown(left, onCancel = { countdown = null }) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

private fun groupErrorText(code: Message.GroupErrorCode) = when (code) {
    Message.GroupErrorCode.INVALID_INVITE -> R.string.group_error_invalid_invite
    Message.GroupErrorCode.EXPIRED_INVITE -> R.string.group_error_expired_invite
    Message.GroupErrorCode.NOT_ADMIN -> R.string.group_error_not_admin
    Message.GroupErrorCode.NO_SUCH_GROUP -> R.string.group_error_other
    Message.GroupErrorCode.ALREADY_MEMBER -> R.string.group_error_already_member
    Message.GroupErrorCode.NO_RELAY -> R.string.group_error_no_relay
}

/** The marker's picture: the rider's photo, their icon or their initials, on their colour. */
@Composable
fun RiderAvatar(r: GroupRider, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(r.colour)).border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val photo = r.photo
        val icon = r.icon?.let { GroupIcons[it] }
        when {
            photo != null -> Image(photo.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            icon != null -> Icon(icon, null, tint = Color(0xFF0B0E14), modifier = Modifier.size(size * 0.6f))
            else -> Text(r.nickname.take(2).uppercase(), color = Color(0xFF0B0E14), fontWeight = FontWeight.Black, fontSize = (size.value * 0.4f).sp)
        }
    }
}

@Composable
private fun RiderRow(r: GroupRider, me: Boolean, pos: Message.GroupPositionInfo?, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RiderAvatar(r, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (me) stringResource(R.string.group_rider_you, r.nickname) else r.nickname, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (!me) Text(riderStatus(pos), color = TextSecondary, fontSize = 13.sp)
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.PersonRemove, stringResource(R.string.group_remove), tint = TextSecondary) }
    }
}

/** "now", "3 min ago" or "stopped 12 min", the way the sender reports it. */
@Composable
private fun riderStatus(p: Message.GroupPositionInfo?): String {
    p ?: return stringResource(R.string.group_no_position)
    val now = System.currentTimeMillis()
    if (p.stopped && p.stoppedSince != null) return stringResource(R.string.group_stopped_for, ((now - p.stoppedSince!!) / 60_000).coerceAtLeast(1).toInt())
    val min = ((now - p.timeMs) / 60_000).toInt()
    return if (min < 1) stringResource(R.string.group_seen_now) else stringResource(R.string.group_seen_min, min)
}

/** Nickname, icon and colour; "Save" tells the phone, which tells the group. */
@Composable
private fun ProfileEditor(me: GroupRider, onSave: (String, Int, String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var nick by rememberSaveable(me.nickname) { mutableStateOf(me.nickname) }
    var colour by rememberSaveable(me.colour) { mutableStateOf(me.colour) }
    var icon by rememberSaveable(me.icon) { mutableStateOf(me.icon) }
    TextButton(onClick = { open = !open }) {
        Icon(Icons.Default.Edit, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.group_profile), color = ElectricCyan, fontWeight = FontWeight.Bold)
    }
    if (open) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(nick, { nick = it.take(20) }, singleLine = true, label = { Text(stringResource(R.string.group_nickname)) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            GroupColours.forEach { c ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).border(if (c == colour) 3.dp else 1.dp, if (c == colour) Color.White else CockpitBorder, CircleShape).clickable { colour = c })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            GroupIcons.forEach { (id, vector) ->
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (id == icon) Color(colour) else CockpitSurface)
                        .border(1.dp, CockpitBorder, CircleShape).clickable { icon = if (icon == id) null else id },
                    contentAlignment = Alignment.Center
                ) { Icon(vector, null, tint = if (id == icon) Color(0xFF0B0E14) else TextSecondary, modifier = Modifier.size(22.dp)) }
            }
        }
        Button(onClick = { onSave(nick.trim().ifEmpty { me.nickname }, colour, icon); open = false }, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
            Text(stringResource(R.string.group_save), color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
        }
    }
}

/** The invite as a QR code for a friend's camera. Anyone with it can join, and it says so. */
@Composable
private fun InviteQr(uri: String?) {
    val bitmap = remember(uri) { uri?.let { QrCode.bitmap(it) } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bitmap != null) Image(
            bitmap.asImageBitmap(), stringResource(R.string.group_invite),
            modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).testTag("group_qr")
        )
        else CircularProgressIndicator(color = ElectricCyan)
        Text(stringResource(R.string.group_invite_hint), color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ---- SOS ----

/** Held for three seconds, so a gloved thumb does not set it off by accident. */
@Composable
private fun SosButton(onTrigger: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(holding) {
        if (!holding) { progress = 0f; return@LaunchedEffect }
        val start = System.currentTimeMillis()
        while (holding) {
            progress = ((System.currentTimeMillis() - start) / SOS_HOLD_MS.toFloat()).coerceAtMost(1f)
            delay(30)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(WarningRed.copy(alpha = 0.18f))
                .border(2.dp, WarningRed, RoundedCornerShape(16.dp)).testTag("group_sos_hold")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        holding = true
                        val released = withTimeoutOrNull(SOS_HOLD_MS.toLong()) { waitForUpOrCancellation() }
                        holding = false
                        if (released == null) onTrigger()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress).background(WarningRed.copy(alpha = 0.55f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sos, null, tint = WarningRed, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.sos_hold), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
        Text(stringResource(R.string.sos_hold_hint), color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SosCountdown(left: Int, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        HideSystemBars()
        Column(
            Modifier.fillMaxSize().background(Color(0xFF7A0B00)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.sos_countdown_title), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text("$left", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black, modifier = Modifier.testTag("sos_countdown"))
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.White), modifier = Modifier.height(64.dp).widthIn(min = 240.dp).testTag("sos_countdown_cancel")) {
                Text(stringResource(R.string.cancel), color = Color(0xFF7A0B00), fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/**
 * On top of everything: the rider's own SOS with how far it has got (a bar at the top), and a
 * full-screen alert when someone else sends one. [navigateTo] starts a route to the rider in trouble.
 */
@Composable
fun GroupSosOverlay(manager: GroupManager, myLat: Double?, myLon: Double?, navigateTo: (Double, Double, String) -> Unit) {
    val sos by manager.sos.collectAsStateWithLifecycle()
    val members by manager.members.collectAsStateWithLifecycle()
    val myId by manager.myId.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(setOf<Long>()) }

    Box(Modifier.fillMaxSize()) {
    sos.values.firstOrNull { it.mine }?.let { mine ->
        val seen = mine.acks.size
        val coming = mine.acks.count { it.coming }
        val others = (mine.memberCount - 1).coerceAtLeast(0)
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF7A0B00))
                .border(2.dp, WarningRed, RoundedCornerShape(16.dp)).padding(14.dp).testTag("sos_sent"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(R.string.sos_sent), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(stringResource(R.string.sos_delivery, seen, others), color = Color.White, fontSize = 15.sp)
            if (coming > 0) Text(stringResource(R.string.sos_on_the_way, coming), color = Color(0xFFB9F6CA), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            else if (seen == 0) Text(stringResource(R.string.sos_no_delivery), color = Color(0xFFFFD180), fontSize = 14.sp)
            Button(onClick = { manager.sosCancel() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Text(stringResource(R.string.sos_cancel), color = Color(0xFF7A0B00), fontWeight = FontWeight.Black)
            }
        }
    }

    val incoming = sos.values.firstOrNull { !it.mine && it.timeMs !in dismissed }
    incoming?.let { s ->
        val who = members[s.from]?.nickname ?: stringResource(R.string.group_unnamed)
        val answered = s.acks.firstOrNull { it.riderId == myId }?.coming == true
        SosAlertSound()
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)) {
            HideSystemBars()
            Column(
                Modifier.fillMaxSize().background(Color(0xFFB71C1C)).padding(24.dp).testTag("sos_alert"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Sos, null, tint = Color.White, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.sos_alert_title, who), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                if (myLat != null && myLon != null) {
                    val m = com.curveon.routing.Geo.distance(myLat, myLon, s.lat, s.lon).toInt()
                    Text(
                        if (m < 1000) stringResource(R.string.sos_away_m, m) else stringResource(R.string.sos_away_km, m / 1000.0),
                        color = Color.White, fontSize = 22.sp
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { manager.sosAnswer(s.from, true) }, enabled = !answered,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, disabledContainerColor = Color(0xFF1B5E20)),
                        modifier = Modifier.height(64.dp).testTag("sos_coming")
                    ) {
                        Text(stringResource(if (answered) R.string.sos_you_coming else R.string.sos_coming), color = if (answered) Color.White else Color(0xFFB71C1C), fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = { navigateTo(s.lat, s.lon, who); dismissed = dismissed + s.timeMs },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White), modifier = Modifier.height(64.dp).testTag("sos_navigate")
                    ) { Text(stringResource(R.string.sos_navigate), color = Color(0xFFB71C1C), fontSize = 18.sp, fontWeight = FontWeight.Black) }
                }
                TextButton(onClick = { dismissed = dismissed + s.timeMs }, modifier = Modifier.testTag("sos_dismiss")) {
                    Text(stringResource(R.string.sos_dismiss), color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
    }
}

/** Alarm sound and vibration for as long as the alert is up. */
@Composable
private fun SosAlertSound() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val tone = runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull()
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)) }
        onDispose {
            runCatching { tone?.stop() }
            runCatching { vibrator?.cancel() }
        }
    }
}
