package com.curveon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.ui.components.*
import com.curveon.ui.viewmodel.DashboardViewModel

/** Messages or music (one of them gets the free height) beside or under the map; a call among them. */
@Composable
internal fun ColumnScope.DashboardSideCards(viewModel: DashboardViewModel, portrait: Boolean, showEngine: Boolean) {
    val context = LocalContext.current
    val phone = viewModel.phoneCompanion
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val track by phone.currentTrack.collectAsStateWithLifecycle()
    val albumArt by phone.albumArt.collectAsStateWithLifecycle()
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    val voiceReply by phone.voiceReply.collectAsStateWithLifecycle()
    val phoneState by phone.connectionState.collectAsStateWithLifecycle()
    val phoneName by phone.connectedDeviceName.collectAsStateWithLifecycle()
    val mediaApps by phone.mediaApps.collectAsStateWithLifecycle()
    val phoneStatusNow by phone.phoneStatus.collectAsStateWithLifecycle()
    val thisDeviceIsPhone by phone.thisDevice.collectAsStateWithLifecycle()
    /** The call, ringing or answered. */
    val ringing = notifications.firstOrNull { it.isIncomingCall }
    val callOngoing = ringing?.isOngoingCall == true
    val messages = notifications.filterNot { it.isIncomingCall }
    // A message needs height (text, Reply, Dismiss); the call and the music only need icons. In a
    // phone held upright, with the column to itself, they share one row above the message; so they do
    // on a low phone on its side, where three rows leave the message no room for its text.
    val iconRow = (portrait || LocalConfiguration.current.screenHeightDp < LOW_LANDSCAPE_HEIGHT) && !showEngine && callOngoing && !isFullScreenMap && track != null && (voiceReply != null || messages.isNotEmpty())
    if (iconRow) ringing?.let { call -> track?.let { t ->
        Row(Modifier.fillMaxWidth().height(CallCardHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OngoingCallIcons(call, { i -> phone.invokeAction(call.id, i) }, { phone.hideNotification(call.id) }, Modifier.weight(1f).fillMaxHeight())
            MiniMediaCard(t, albumArt, { phone.togglePlayPause() }, { phone.skipNext() }, Modifier.weight(1f).fillMaxHeight(), compact = true)
        }
    } }
    // Alone in the column (no message or voice reply) the call gets the room; with one it stays a row.
    if (callOngoing && !isFullScreenMap && !iconRow) ringing?.let { call ->
        val alone = voiceReply == null && messages.isEmpty()
        OngoingCallCard(call, { i -> phone.invokeAction(call.id, i) }, { phone.hideNotification(call.id) },
            alone = alone, modifier = if (alone) Modifier.weight(1f, fill = false) else Modifier)
    }
    // A call, like a message, gets the room: the player shrinks to one row.
    val attention = voiceReply != null || messages.isNotEmpty() || ringing != null
    when {
        voiceReply != null -> VoiceReplyCard(
            reply = voiceReply!!,
            onSend = { phone.confirmVoiceReply() },
            onRetry = { phone.startVoiceReply(voiceReply!!.notificationKey) },
            onCancel = { phone.cancelVoiceReply() },
            modifier = Modifier.weight(1f).remoteSheet(phone::cancelVoiceReply)
        )
        messages.isNotEmpty() -> NotificationCard(
            notifications = messages,
            onReply = { phone.startVoiceReply(it.id) },
            onDismiss = { phone.dismissNotification(it.id) },
            onSwipe = { phone.hideNotification(it.id) },
            onAction = { n, i -> phone.invokeAction(n.id, i) },
            onRead = { viewModel.readAloud(it.senderName, it.message) },
            modifier = Modifier.weight(1f)
        )
    }

    // A message shares the column with a one-row player.
    if (attention) {
        if ((ringing == null || callOngoing) && !iconRow) track?.let { MiniMediaCard(it, albumArt, { phone.togglePlayPause() }, { phone.skipNext() }) }
    } else {
        FullMediaCard(
            track = track, art = albumArt, phoneState = phoneState, phoneName = phoneName, apps = mediaApps,
            onPlayPause = { phone.togglePlayPause() }, onNext = { phone.skipNext() }, onPrevious = { phone.skipPrevious() },
            onPlayApp = phone::playApp, onReconnect = phone::retryNow,
            modifier = Modifier.weight(1f),
            // No notification access on the phone: no music can come, so say why instead of "tap to play".
            blocked = phoneStatusNow?.takeIf { !it.notificationAccess }?.let {
                stringResource(if (thisDeviceIsPhone) R.string.media_this_no_notification_access else R.string.link_no_notification_access)
            },
            onFixBlocked = if (thisDeviceIsPhone) ({
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }) else null
        )
    }
}

/** On its side, a screen lower than this (dp) has no room for the call, a message and the player as three rows. */
private const val LOW_LANDSCAPE_HEIGHT = 400
