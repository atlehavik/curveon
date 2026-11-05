@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.curveon.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.groups.GroupManager
import com.curveon.data.groups.GroupRider
import com.curveon.phone.QrCode
import com.curveon.protocol.Message
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay

/** The icons a rider can pick for their marker; every device draws the same, and only the id travels. */
internal val GroupIcons: Map<String, ImageVector> = linkedMapOf(
    "helmet" to Icons.Default.SportsMotorsports,
    "bike" to Icons.Default.TwoWheeler,
    "mountain" to Icons.Default.Terrain,
    "flag" to Icons.Default.Flag,
    "bolt" to Icons.Default.Bolt,
    "coffee" to Icons.Default.LocalCafe,
)

/** Colours to pick from, bright enough on both the day and the night map. */
internal val GroupColours = listOf(0xFF00E5FF, 0xFFFF5252, 0xFF69F0AE, 0xFFFFD740, 0xFFE040FB, 0xFFFF9100).map { it.toInt() }

/**
 * The Group ride tab: every group the rider is in as a card, with the switch that picks which one gets the rider's position
 * (one at a time), a new group and scanning a code. A card opens the group: who is there, add friends or invite by code or link,
 * my other device, SOS, leave. (Your own name and picture, and the settings, are on the Me tab; friends are on the Friends tab.)
 */
@Composable
fun GroupTab(manager: GroupManager, modifier: Modifier = Modifier) {
    val groups by manager.groups.collectAsStateWithLifecycle()
    val activeId by manager.activeId.collectAsStateWithLifecycle()
    val sharing by manager.sharing.collectAsStateWithLifecycle()
    val connection by manager.connection.collectAsStateWithLifecycle()
    val sos by manager.sos.collectAsStateWithLifecycle()
    // The group on show; a group just made or joined opens by itself once the phone has it.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var openNext by rememberSaveable { mutableStateOf(false) }
    var showInvite by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val known = remember { mutableStateOf(groups.map { it.id }.toSet()) }
    LaunchedEffect(groups) {
        val fresh = groups.map { it.id }.filter { it !in known.value }
        if (openNext && fresh.isNotEmpty()) { openId = fresh.first(); openNext = false }
        known.value = groups.map { it.id }.toSet()
        if (openId != null && groups.none { it.id == openId }) openId = null // left, or gone from the other device
    }
    val open = groups.firstOrNull { it.id == openId }
    if (open != null) {
        GroupDetail(manager, open, showInvite, { showInvite = it }, onBack = { openId = null; showInvite = false }, modifier)
        return
    }

    // Turning a group on rides with it (and turns the one before off); turning it off pauses the rider in it: no one sees anyone.
    fun share(g: Message.GroupInfo, on: Boolean) {
        if (on && g.id != activeId) manager.select(g.id)
        manager.setSharing(on)
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        sos.values.firstOrNull { it.mine }?.let { MySosCard(it, manager) }
        if (groups.isEmpty()) Text(stringResource(R.string.group_start_hint), color = TextSecondary, fontSize = 14.sp)
        else {
            FriendsSectionLabel(stringResource(R.string.group_switch))
            groups.forEach { g ->
                val isActive = g.id == activeId
                GroupCard(g, isActive, if (isActive) connection?.status else null, sharing, onOpen = { openId = g.id; showInvite = false }, onShare = { share(g, it) })
            }
            if (groups.size > 1) Text(stringResource(R.string.group_one_at_a_time), color = TextMuted, fontSize = 13.sp)
        }

        FriendsSectionLabel(stringResource(R.string.group_new))
        OutlinedTextField(
            name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("group_name"),
            label = { Text(stringResource(R.string.group_name_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { manager.create(name.trim().ifEmpty { null }); name = ""; openNext = true; showInvite = true },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan), modifier = Modifier.testTag("group_start")) {
                Text(stringResource(R.string.group_start), color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
            }
            // Joining a friend's group, or making this device the same rider as my other one, from the QR code on the other screen.
            if (com.curveon.qrscan.hasCamera()) OutlinedButton(onClick = { scanning = true }, modifier = Modifier.testTag("group_scan")) {
                Icon(Icons.Default.QrCodeScanner, null, tint = ElectricCyan)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.group_scan), color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
        }
        Text(stringResource(R.string.group_join_hint), color = TextMuted, fontSize = 13.sp)
    }
    if (scanning) com.curveon.qrscan.QrScanDialog(onInvite = { link -> scanning = false; openNext = true; manager.join(link) }, onDismiss = { scanning = false })
}

/** One group in the list: its name, how it is doing, and the switch for sharing the position with it. Tapping it opens it. */
@Composable
private fun GroupCard(g: Message.GroupInfo, active: Boolean, status: Message.GroupStatus?, sharing: Boolean, onOpen: () -> Unit, onShare: (Boolean) -> Unit) {
    val on = active && sharing && status != Message.GroupStatus.REMOVED
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (on) ElectricCyan.copy(alpha = 0.18f) else CockpitSurfaceVariant)
            .clickable(onClick = onOpen).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp).testTag("group_card"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Groups, null, tint = if (on) ElectricCyan else TextSecondary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(g.name ?: stringResource(R.string.group_unnamed), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            val (text, colour) = when {
                !active -> stringResource(R.string.group_card_idle) to TextMuted
                status == Message.GroupStatus.REMOVED -> stringResource(R.string.group_status_removed) to WarningRed
                status == null || status == Message.GroupStatus.OFFLINE -> stringResource(R.string.group_status_offline) to NeonAmber
                !sharing -> stringResource(R.string.group_card_paused) to NeonAmber
                else -> pluralStringResource(R.plurals.group_card_sharing, g.members, g.members) to EcoGreen
            }
            Text(text, color = colour, fontSize = 13.sp)
        }
        if (!(active && status == Message.GroupStatus.REMOVED)) CockpitSwitch(on, onShare, Modifier.testTag("group_card_share"))
        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
    }
}

/** One group opened: who is there and everything to do with it. A group not in use says how to use it and can still invite. */
@Composable
private fun GroupDetail(
    manager: GroupManager, group: Message.GroupInfo, showInvite: Boolean, setShowInvite: (Boolean) -> Unit, onBack: () -> Unit, modifier: Modifier
) {
    val activeId by manager.activeId.collectAsStateWithLifecycle()
    val sharing by manager.sharing.collectAsStateWithLifecycle()
    val connection by manager.connection.collectAsStateWithLifecycle()
    val members by manager.members.collectAsStateWithLifecycle()
    val myId by manager.myId.collectAsStateWithLifecycle()
    val positions by manager.positions.collectAsStateWithLifecycle()
    val invite by manager.invite.collectAsStateWithLifecycle()
    val sos by manager.sos.collectAsStateWithLifecycle()
    // An invite asked for (or made with a new group) for this group only: the one on show belongs to it.
    LaunchedEffect(group.id) { if (showInvite) manager.requestInvite(group.id) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<GroupRider?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var sosNoPosition by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val active = group.id == activeId
    val status = if (active) connection?.status ?: Message.GroupStatus.OFFLINE else null
    val groupName = group.name ?: stringResource(R.string.group_unnamed)

    // A countdown to sending the SOS, with a way out.
    LaunchedEffect(countdown != null) {
        while (true) {
            val now = countdown ?: break
            delay(1000)
            if (countdown == null) break // cancelled meanwhile
            if (now <= 1) { countdown = null; if (!manager.sosStart(lastKnownPosition(context))) sosNoPosition = true; break } else countdown = now - 1
        }
    }
    if (sosNoPosition) AlertDialog(
        onDismissRequest = { sosNoPosition = false },
        icon = { Icon(Icons.Default.LocationOff, null, tint = WarningRed) },
        title = { HideSystemBars(); Text(stringResource(R.string.sos_no_position_title)) },
        text = { Text(stringResource(R.string.sos_no_position_text), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { sosNoPosition = false }) { Text(stringResource(R.string.ok)) } }
    )

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        sos.values.firstOrNull { it.mine }?.let { MySosCard(it, manager) }
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("group_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.group_switch), color = ElectricCyan, fontWeight = FontWeight.Bold)
        }
        Text(groupName, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)

        if (status != null) {
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
            // No contact: why, and what to do about it.
            if (status == Message.GroupStatus.OFFLINE) Text(
                stringResource(if (hasInternet(context)) R.string.group_offline_trying else R.string.group_offline_no_internet),
                color = TextSecondary, fontSize = 13.sp
            )
        }
        val removed = status == Message.GroupStatus.REMOVED

        // My other device (tablet or phone) is the one sending my position: say so, and let me take over.
        if (connection?.otherDeviceActive == true && active && sharing && !removed) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.group_other_device_active), color = TextPrimary, fontSize = 14.sp)
            Button(onClick = { manager.useThisDevice() }, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan), modifier = Modifier.testTag("group_use_this_device")) {
                Text(stringResource(R.string.group_use_this_device), color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
            }
        }

        if (!removed) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.group_share_position), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            CockpitSwitch(active && sharing, { on -> if (on && !active) manager.select(group.id); manager.setSharing(on) }, Modifier.testTag("group_share"))
        }

        if (active && !sharing && !removed) Text(stringResource(R.string.group_paused_hint), color = TextSecondary, fontSize = 14.sp)
        else if (active) {
            FriendsSectionLabel(stringResource(R.string.group_riders))
            members.values.sortedBy { if (it.id == myId) 0 else 1 }.forEach { r ->
                RiderRow(
                    r, r.id == myId, positions[r.id], onRemove = if (group.admin && r.id != myId) ({ confirmRemove = r }) else null,
                    // Only the active group is connected: that is where a nudge can go.
                    nudge = if (r.id != myId && activeId == group.id) manager to group.id else null,
                    // Someone who is not a friend yet can be asked from here (the manager knows who is a friend).
                    befriend = manager.takeIf { r.id != myId && activeId == group.id && !it.isFriend(r) }
                )
            }
        } else Text(stringResource(R.string.group_not_active), color = TextSecondary, fontSize = 14.sp)

        if (!removed) {
            AddFriendsSection(manager, group.id)
            OutlinedButton(onClick = {
                setShowInvite(!showInvite)
                if (!showInvite) manager.requestInvite(group.id)
            }, modifier = Modifier.testTag("group_invite")) {
                Icon(Icons.Default.QrCode2, null, tint = ElectricCyan)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.group_invite), color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
            if (showInvite) QrBlock(invite, R.string.group_invite_hint, R.string.group_invite, shareLink = true)
        }

        if (active && sharing && !removed) SosButton(onTrigger = { countdown = SOS_COUNTDOWN_S })

        TextButton(onClick = { confirmLeave = true }, modifier = Modifier.testTag("group_leave")) {
            Text(stringResource(R.string.group_leave), color = WarningRed, fontWeight = FontWeight.Bold)
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { HideSystemBars(); Text(stringResource(R.string.group_leave_title, groupName)) },
        text = { Text(stringResource(R.string.group_leave_text)) },
        confirmButton = { TextButton(onClick = { manager.leave(group.id); confirmLeave = false; onBack() }) { Text(stringResource(R.string.group_leave), color = WarningRed) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } }
    )
    confirmRemove?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { HideSystemBars(); Text(stringResource(R.string.group_remove_title, r.nickname)) },
            text = { Text(stringResource(R.string.group_remove_text, r.nickname)) },
            confirmButton = { TextButton(onClick = { manager.removeRider(group.id, r.id); confirmRemove = null }) { Text(stringResource(R.string.group_remove), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    countdown?.let { left -> SosCountdown(left, onCancel = { countdown = null }) }
}

@Composable
internal fun FriendsSectionLabel(text: String) {
    Text(text.uppercase(), color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

internal fun groupErrorText(code: Message.GroupErrorCode) = when (code) {
    Message.GroupErrorCode.INVALID_INVITE -> R.string.group_error_invalid_invite
    Message.GroupErrorCode.EXPIRED_INVITE -> R.string.group_error_expired_invite
    Message.GroupErrorCode.NOT_ADMIN -> R.string.group_error_not_admin
    Message.GroupErrorCode.NO_SUCH_GROUP -> R.string.group_error_other
    Message.GroupErrorCode.ALREADY_MEMBER -> R.string.group_error_already_member
    Message.GroupErrorCode.NO_RELAY -> R.string.group_error_no_relay
    Message.GroupErrorCode.NOT_LINKED -> R.string.group_error_other
}

/** Add friends to the group: pick some of them and each gets an invite to answer (through the private channel between you). */
@Composable
private fun AddFriendsSection(manager: GroupManager, groupId: String) {
    val friends by manager.friends.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val context = LocalContext.current
    OutlinedButton(onClick = { open = !open }, modifier = Modifier.testTag("group_add_friends")) {
        Icon(Icons.Default.PersonAdd, null, tint = ElectricCyan)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.group_add_friends), color = ElectricCyan, fontWeight = FontWeight.Bold)
    }
    if (open) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (friends.isEmpty()) Text(stringResource(R.string.group_add_friends_none), color = TextSecondary, fontSize = 14.sp)
        else {
            friends.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { chosen = if (f.id in chosen) chosen - f.id else chosen + f.id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FriendAvatar(f, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(f.nickname, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Checkbox(f.id in chosen, { chosen = if (it) chosen + f.id else chosen - f.id })
                }
            }
            Button(
                onClick = {
                    manager.addFriendsToGroup(groupId, chosen.toList())
                    android.widget.Toast.makeText(context, context.getString(R.string.group_add_friends_sent), android.widget.Toast.LENGTH_SHORT).show()
                    chosen = emptySet(); open = false
                },
                enabled = chosen.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan), modifier = Modifier.testTag("group_add_friends_send")
            ) { Text(stringResource(R.string.group_add_friends_send), color = Color(0xFF00363D), fontWeight = FontWeight.Bold) }
        }
    }
}

/** Data saver. The relay is always Curveon's, so there is nothing else to set. */
@Composable
internal fun GroupSettingsSection(settings: Message.GroupSettings, onDataSaver: (Boolean) -> Unit) {
    FriendsSectionLabel(stringResource(R.string.group_settings))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.group_data_saver), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.group_data_saver_hint), color = TextMuted, fontSize = 12.sp)
        }
        CockpitSwitch(settings.dataSaver, onDataSaver)
    }
}

/** A link as a QR code for a camera, with what to know about it: the invite (anyone with it can join) or the one for my other device. */
@Composable
internal fun QrBlock(uri: String?, hint: Int, description: Int, shareLink: Boolean = false, shareText: Int = R.string.group_invite_text) {
    val bitmap = remember(uri) { uri?.let { QrCode.bitmap(it) } }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bitmap != null) Image(
            bitmap.asImageBitmap(), stringResource(description),
            modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).testTag("group_qr")
        )
        else CircularProgressIndicator(color = ElectricCyan)
        Text(stringResource(hint), color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        // For a friend who is not here: the same invite as a link, through any chat (Android's share sheet).
        if (shareLink && uri != null) OutlinedButton(onClick = {
            context.startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, context.getString(shareText, uri)), null
            ))
        }, modifier = Modifier.testTag("group_share_link")) {
            Icon(Icons.Default.Share, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.group_share_link), color = ElectricCyan, fontWeight = FontWeight.Bold)
        }
    }
}

