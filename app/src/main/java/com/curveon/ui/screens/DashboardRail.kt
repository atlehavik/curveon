package com.curveon.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.ui.components.*
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.DashboardViewModel

/** The rail with what its buttons show: unread messages, a new version, an SOS, sharing, a ride recorded while standing. */
@Composable
internal fun DashboardRail(viewModel: DashboardViewModel, state: DashboardScreenState, portrait: Boolean) {
    val phone = viewModel.phoneCompanion
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    val appUpdate by viewModel.appUpdate.collectAsStateWithLifecycle()
    val groupSos by phone.groups.sos.collectAsStateWithLifecycle()
    Rail(
        flash = state.railFlash,
        vertical = !portrait,
        isFullScreenMap = isFullScreenMap,
        unread = notifications.size,
        updateWaiting = appUpdate is com.curveon.data.update.UpdateState.Available,
        groupAlert = groupSos.values.any { !it.mine },
        sharingPosition = sharingPosition(viewModel),
        recordingResting = state.recordingResting,
        active = state.activePanel,
        onPress = state::pressRail,
        // A screen from the rail is open: it closes (as pressing its own button would), and the view switches too.
        onToggleMap = { state.activePanel?.let(state::pressRail); viewModel.toggleFullScreenMap() }
    )
}

/** A group or a friend gets the rider's position. */
@Composable
private fun sharingPosition(viewModel: DashboardViewModel): Boolean {
    val groups = viewModel.phoneCompanion.groups
    val groupActive by groups.activeId.collectAsStateWithLifecycle()
    val groupSharing by groups.sharing.collectAsStateWithLifecycle()
    val groupConnection by groups.connection.collectAsStateWithLifecycle()
    val friendSharing by groups.friendSharing.collectAsStateWithLifecycle()
    val friendList by groups.friends.collectAsStateWithLifecycle()
    return (groupActive != null && groupSharing && groupConnection?.status == com.curveon.protocol.Message.GroupStatus.CONNECTED) ||
        (friendSharing && friendList.any { it.seesMe })
}

@Composable
private fun Rail(
    /** Buttons that blink now (where something was just saved), and a count that changes for each blink. */
    flash: Pair<Set<RailPanel>, Int>,
    /** Down the left side (landscape), or along the bottom (portrait). */
    vertical: Boolean,
    isFullScreenMap: Boolean,
    unread: Int,
    /** A new version of the app is out: a dot on Settings. */
    updateWaiting: Boolean,
    /** Someone in the group has sent an SOS: a red dot on the group button. */
    groupAlert: Boolean = false,
    /** A group or a friend gets the rider's position: a green dot on the group button, so it is never forgotten. */
    sharingPosition: Boolean = false,
    /** A ride is being recorded while standing still: a red dot on the GPX button. */
    recordingResting: Boolean = false,
    /** The screen that is open: its button is lit. */
    active: RailPanel?,
    onPress: (RailPanel) -> Unit,
    onToggleMap: () -> Unit
) {
    // A phone held sideways is lower than the X7 (about 410 dp against 540): less room round the buttons.
    val compact = vertical && LocalConfiguration.current.screenHeightDp < 480
    // With a remote the rail goes round: past its end is its other end, not whatever is furthest across the screen.
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val toggleFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val search: @Composable () -> Unit = {
        RailButton(Icons.Default.TwoWheeler, stringResource(R.string.where_to), ElectricCyan, ElectricCyan, filled = active == RailPanel.SEARCH, flashKey = if (RailPanel.SEARCH in flash.first) flash.second else 0, tag = "rail_search", target = TourTarget.SEARCH, focus = searchFocus,
            wrap = { if (vertical) up = toggleFocus else right = toggleFocus }, onClick = { onPress(RailPanel.SEARCH) })
    }
    val gpx: @Composable () -> Unit = {
        Box {
            RailButton(Icons.Default.Route, stringResource(R.string.gpx_manager), NeonViolet, NeonViolet, filled = active == RailPanel.GPX, flashKey = if (RailPanel.GPX in flash.first) flash.second else 0, tag = "rail_gpx", target = TourTarget.GPX, onClick = { onPress(RailPanel.GPX) })
            if (recordingResting) RecordingDot(Modifier.align(Alignment.TopEnd).padding(6.dp).testTag("rail_gpx_recording"))
        }
    }
    val phone: @Composable () -> Unit = {
        RailButton(Icons.Default.Phone, stringResource(R.string.phone), EcoGreen, EcoGreen, filled = active == RailPanel.PHONE,
            tag = "rail_phone", target = TourTarget.PHONE, onClick = { onPress(RailPanel.PHONE) })
    }
    // Friends riding together; a dot while someone has sent an SOS.
    val group: @Composable () -> Unit = {
        Box {
            RailButton(Icons.Default.Diversity3, stringResource(R.string.friends_title), NeonAmber, NeonAmber, filled = active == RailPanel.GROUP,
                tag = "rail_group", target = TourTarget.FRIENDS, onClick = { onPress(RailPanel.GROUP) })
            if (groupAlert) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp).clip(CircleShape).background(WarningRed))
            else if (sharingPosition) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(10.dp).clip(CircleShape).background(SharingBlue).testTag("rail_sharing"))
        }
    }
    val settings: @Composable () -> Unit = {
        Box {
            RailButton(Icons.Default.Settings, stringResource(R.string.settings), TextPrimary, TextPrimary, filled = active == RailPanel.SETTINGS, tag = "rail_settings", target = TourTarget.SETTINGS, onClick = { onPress(RailPanel.SETTINGS) })
            if (updateWaiting) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp).clip(CircleShape).background(ElectricCyan))
        }
    }
    // The map toggle shows a mode, not a screen: it only changes its symbol.
    val mapToggle: @Composable () -> Unit = {
        Box {
            RailButton(
                if (isFullScreenMap) Icons.Default.Dashboard else Icons.Default.Map,
                stringResource(if (isFullScreenMap) R.string.dashboard else R.string.full_map),
                ElectricCyan, ElectricCyan,
                tag = "rail_toggle_map", target = TourTarget.MAP_TOGGLE, focus = toggleFocus,
                wrap = { if (vertical) down = searchFocus else left = searchFocus }, onClick = onToggleMap
            )
            if (isFullScreenMap && unread > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(NeonAmber),
                    contentAlignment = Alignment.Center
                ) { Text(unread.toString(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black) }
            }
        }
    }
    if (vertical) Column(
        Modifier.width(72.dp).fillMaxHeight().background(CockpitSurface).padding(vertical = if (compact) 6.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        search(); gpx(); phone(); group(); settings(); mapToggle()
    } else Row(
        Modifier.fillMaxWidth().height(68.dp).background(CockpitSurface).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // The landscape rail turned a quarter: its bottom (the map toggle) ends up at the left.
        mapToggle(); settings(); group(); phone(); gpx(); search()
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    description: String,
    accent: Color,
    tint: Color,
    filled: Boolean = false,
    /** Changes (above 0) each time the button should blink: something was just put where it leads. */
    flashKey: Int = 0,
    tag: String,
    /** What the welcome tour lights up; null for a button the tour does not mention. */
    target: TourTarget?,
    focus: androidx.compose.ui.focus.FocusRequester = remember { androidx.compose.ui.focus.FocusRequester() },
    /** Where the remote's arrows go past this button, at an end of the rail. */
    wrap: (androidx.compose.ui.focus.FocusProperties.() -> Unit)? = null,
    onClick: () -> Unit
) {
    // Smaller on a phone: sideways it is low, upright it is narrow.
    val compact = LocalConfiguration.current.let { minOf(it.screenHeightDp, it.screenWidthDp) < 480 }
    // Three blinks: the button swells and turns amber and back.
    val blink = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(flashKey) {
        if (flashKey > 0) repeat(3) {
            blink.animateTo(1f, androidx.compose.animation.core.tween(260))
            blink.animateTo(0f, androidx.compose.animation.core.tween(260))
        }
    }
    val base = if (filled) accent else CockpitSurfaceVariant
    val remote = com.curveon.ui.components.LocalRemote.current
    if (tag == "rail_search") LaunchedEffect(remote) { if (remote?.lastRail == null) remote?.lastRail = focus }
    Box(
        Modifier
            .size(if (compact) 48.dp else 56.dp)
            .then(if (target != null) Modifier.tourTarget(target) else Modifier)
            .graphicsLayer { scaleX = 1f + 0.25f * blink.value; scaleY = 1f + 0.25f * blink.value }
            .clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.lerp(base, NeonAmber, blink.value))
            .focusRequester(focus)
            .focusProperties { wrap?.invoke(this) }
            // The menu opens on the button the remote was last on (first of all on Where to?).
            .onFocusChanged { if (it.isFocused) remote?.lastRail = focus }
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = if (filled || blink.value > 0.5f) Color(0xFF00363D) else tint, modifier = Modifier.size(28.dp)) }
}

