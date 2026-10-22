package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.groups.GroupManager
import com.curveon.data.groups.GroupRider
import com.curveon.protocol.Message
import com.curveon.ui.theme.*

/** The pages of the friends dialog (the main menu's friends button). */
private enum class FriendsTab(val label: Int, val icon: ImageVector) {
    ME(R.string.friends_tab_me, Icons.Default.Person),
    FRIENDS(R.string.friends_tab_friends, Icons.Default.Diversity3),
    GROUP(R.string.friends_tab_group, Icons.Default.Groups)
}

/**
 * Friends: **Me** (your name and picture, and the settings), **My friends** (your code, scanning a friend's, the list) and
 * **Group ride** (start or leave groups, add friends to one, see who is there, SOS).
 */
@Composable
fun FriendsDialog(manager: GroupManager, onDismiss: () -> Unit) {
    val activeId by manager.activeId.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (activeId != null) FriendsTab.GROUP else FriendsTab.FRIENDS) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { manager.requestGroups(); manager.requestFriends() }
    LaunchedEffect(Unit) {
        manager.errors.collect { code ->
            android.widget.Toast.makeText(context, context.getString(groupErrorText(code)), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // The code for a friend is for one friend: it is gone from memory as soon as the dialog closes.
    DisposableEffect(Unit) { onDispose { manager.forgetFriendInvite() } }
    PagedCockpitDialog(
        title = stringResource(R.string.friends_title), icon = Icons.Default.Diversity3, accent = ElectricCyan, onDismiss = onDismiss, tag = "friends_dialog",
        pages = FriendsTab.entries, selected = tab, onSelect = { tab = it }, pageLabel = { stringResource(it.label) }, pageIcon = { it.icon },
        tabsWidth = 190.dp
    ) { current ->
        when (current) {
            FriendsTab.ME -> MeTab(manager)
            FriendsTab.FRIENDS -> FriendsTabContent(manager)
            FriendsTab.GROUP -> GroupTab(manager, Modifier.fillMaxSize())
        }
    }
}

/** Me: name, colour, icon and picture as the others see them, and where groups are made and how often the position goes out. */
@Composable
private fun MeTab(manager: GroupManager) {
    val profile by manager.profile.collectAsStateWithLifecycle()
    val settings by manager.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val p = profile
        if (p == null) Text(stringResource(R.string.friends_no_phone), color = TextSecondary, fontSize = 14.sp)
        else {
            val photo = remember(p.photoJpegBase64) {
                p.photoJpegBase64?.let { runCatching { val b = android.util.Base64.decode(it, android.util.Base64.DEFAULT); android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
            }
            val me = remember(p, photo) { GroupRider("me", p.nickname, p.colour, p.icon, photo, p.photoJpegBase64) }
            ProfileEditor(me, alwaysOpen = true) { nick, colour, icon, picture -> manager.setProfile(nick, colour, icon, picture) }
        }
        MyDevicesSection(manager)
        settings?.let { s -> GroupSettingsSection(s, manager::setDataSaver) }
    }
}

/**
 * My devices: link the Curveon on this device with the one on another (a phone and a tablet) once, and your friends, groups and name
 * are the same on both. One of them shows a code and the other scans it; after that they keep in step by themselves.
 */
@Composable
private fun MyDevicesSection(manager: GroupManager) {
    val status by manager.selfStatus.collectAsStateWithLifecycle()
    val link by manager.selfLink.collectAsStateWithLifecycle()
    var showCode by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { manager.forgetSelfLink() } }
    LaunchedEffect(status.linked) { if (status.linked) { showCode = false; manager.forgetSelfLink() } }
    FriendsSectionLabel(stringResource(R.string.devices_title))
    Text(stringResource(if (status.linked) R.string.devices_linked else R.string.devices_hint), color = TextSecondary, fontSize = 14.sp)
    if (status.linked) {
        OutlinedButton(onClick = { manager.unlinkSelf() }, modifier = Modifier.testTag("devices_unlink")) {
            Text(stringResource(R.string.devices_unlink), color = WarningRed, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(onClick = {
            showCode = !showCode
            manager.forgetSelfLink()
            if (showCode) manager.requestSelfLink()
        }, modifier = Modifier.testTag("devices_show")) {
            Icon(Icons.Default.QrCode2, null, tint = ElectricCyan)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.devices_show), color = ElectricCyan, fontWeight = FontWeight.Bold)
        }
        if (showCode) QrBlock(link, R.string.devices_code_hint, R.string.devices_show)
        if (com.curveon.qrscan.hasCamera()) OutlinedButton(onClick = { scanning = true }, modifier = Modifier.testTag("devices_scan")) {
            Icon(Icons.Default.QrCodeScanner, null, tint = ElectricCyan)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.devices_scan), color = ElectricCyan, fontWeight = FontWeight.Bold)
        }
    }
    if (scanning) com.curveon.qrscan.QrScanDialog(onInvite = { l -> scanning = false; manager.join(l) }, onDismiss = { scanning = false })
}

/** My friends: show your code (or send it as a link), scan a friend's code, and the friends you have. */
@Composable
private fun FriendsTabContent(manager: GroupManager) {
    val friends by manager.friends.collectAsStateWithLifecycle()
    val shareOn by manager.friendSharing.collectAsStateWithLifecycle()
    val friendPositions by manager.friendPositions.collectAsStateWithLifecycle()
    val invite by manager.friendInvite.collectAsStateWithLifecycle()
    var showCode by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    // A code that has not come in a few seconds is not coming: say so instead of spinning for ever.
    var codeFailed by remember { mutableStateOf(false) }
    LaunchedEffect(showCode, invite) {
        codeFailed = false
        if (showCode && invite == null) { kotlinx.coroutines.delay(6_000); codeFailed = true }
    }
    var confirmRemove by remember { mutableStateOf<Message.FriendInfo?>(null) }
    val context = LocalContext.current

    // A new friend appears while the code is on show: that code has done its job.
    var known by remember { mutableIntStateOf(friends.size) }
    LaunchedEffect(friends.size) {
        if (friends.size > known && showCode) {
            showCode = false
            manager.forgetFriendInvite()
            android.widget.Toast.makeText(context, context.getString(R.string.friends_new_friend, friends.last().nickname), android.widget.Toast.LENGTH_LONG).show()
        }
        known = friends.size
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.friends_hint), color = TextSecondary, fontSize = 14.sp)
        OutlinedButton(onClick = {
            showCode = !showCode
            codeFailed = false
            manager.forgetFriendInvite()
            if (showCode) manager.requestFriendInvite()
        }, modifier = Modifier.testTag("friends_my_code")) {
            Icon(Icons.Default.QrCode2, null, tint = ElectricCyan)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.friends_my_code), color = ElectricCyan, fontWeight = FontWeight.Bold)
        }
        if (showCode && codeFailed) Text(stringResource(R.string.friends_code_failed), color = WarningRed, fontSize = 14.sp, modifier = Modifier.testTag("friends_code_failed"))
        else if (showCode) QrBlock(invite, R.string.friends_code_hint, R.string.friends_my_code, shareLink = true, shareText = R.string.friends_share_text)
        if (com.curveon.qrscan.hasCamera()) OutlinedButton(onClick = { scanning = true }, modifier = Modifier.testTag("friends_scan")) {
            Icon(Icons.Default.QrCodeScanner, null, tint = ElectricCyan)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.group_scan), color = ElectricCyan, fontWeight = FontWeight.Bold)
        }

        FriendsSectionLabel(stringResource(R.string.friends_list))
        if (friends.isEmpty()) Text(stringResource(R.string.friends_none), color = TextMuted, fontSize = 14.sp)
        else {
            // Outside any group: the friends who may always see where the rider is. Off until turned on, and then only the chosen.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.friends_share_title), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.friends_share_hint), color = TextMuted, fontSize = 12.sp)
                }
                CockpitSwitch(shareOn, { manager.setFriendSharing(it) }, Modifier.testTag("friends_share"))
            }
        }
        friends.forEach { f ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FriendAvatar(f, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.nickname, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (f.sharesWithMe) Text(
                            stringResource(R.string.friends_shares_with_you, friendSeen(friendPositions[f.id])), color = EcoGreen, fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = { confirmRemove = f }) { Icon(Icons.Default.PersonRemove, stringResource(R.string.friends_remove), tint = TextSecondary) }
                }
                if (shareOn) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.friends_sees_me), color = TextSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    CockpitSwitch(f.seesMe, { manager.setFriendSharing(true, f.id, it) }, Modifier.testTag("friends_sees_me"))
                }
            }
        }
    }

    confirmRemove?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { HideSystemBars(); Text(stringResource(R.string.friends_remove_title, f.nickname)) },
            text = { Text(stringResource(R.string.friends_remove_text)) },
            confirmButton = { TextButton(onClick = { manager.removeFriend(f.id); confirmRemove = null }) { Text(stringResource(R.string.friends_remove), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (scanning) com.curveon.qrscan.QrScanDialog(onInvite = { link -> scanning = false; manager.join(link) }, onDismiss = { scanning = false })
}

/** When a friend who shares with the rider was last heard: "now", "3 min ago", "2 h ago", or "not yet" before the first position. */
@Composable
private fun friendSeen(p: Message.GroupPositionInfo?): String {
    p ?: return stringResource(R.string.friends_not_seen_yet)
    val min = ((System.currentTimeMillis() - p.timeMs) / 60_000).toInt()
    return when {
        min < 1 -> stringResource(R.string.group_seen_now)
        min < 120 -> stringResource(R.string.group_seen_min, min)
        else -> stringResource(R.string.friends_seen_hours, min / 60)
    }
}

/** A friend asks you to a ride: join or ignore. Shown over everything, one at a time. */
@Composable
fun GroupInviteOfferDialog(manager: GroupManager) {
    val offers by manager.offers.collectAsStateWithLifecycle()
    val o = offers.firstOrNull() ?: return
    Dialog(onDismissRequest = { manager.answerOffer(o.offerId, false) }) {
        HideSystemBars()
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CockpitSurface).padding(18.dp).testTag("group_offer"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FriendAvatar(Message.FriendInfo(o.offerId, o.fromNickname, o.fromColour, null, o.fromPhotoJpegBase64), 48.dp)
                Text(
                    if (o.groupName != null) stringResource(R.string.offer_title, o.fromNickname, o.groupName!!) else stringResource(R.string.offer_title_unnamed, o.fromNickname),
                    color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { manager.answerOffer(o.offerId, true) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan), modifier = Modifier.weight(1f).height(52.dp).testTag("offer_join")
                ) { Text(stringResource(R.string.offer_join), color = Color(0xFF00363D), fontWeight = FontWeight.Black) }
                TextButton(onClick = { manager.answerOffer(o.offerId, false) }, modifier = Modifier.height(52.dp).testTag("offer_ignore")) {
                    Text(stringResource(R.string.offer_ignore), color = TextSecondary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
