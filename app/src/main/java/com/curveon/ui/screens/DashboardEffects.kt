package com.curveon.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.bluetooth.TabletRoutes
import com.curveon.data.model.GeoPoint
import com.curveon.data.routing.NavigationGoal
import com.curveon.ui.components.LocalRemote
import com.curveon.ui.components.RemoteCall
import com.curveon.ui.components.RemoteDashboard
import com.curveon.ui.components.callDeclineIndex
import com.curveon.ui.components.callOtherIndex
import com.curveon.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay

/**
 * What the dashboard does by itself while it is shown: timers that put things away, answers to what comes in, the
 * map going full-screen for picking, the remote's wishes. Always in the screen (not in its portrait or landscape
 * layout), so that turning the phone starts none of them again.
 */
@Composable
internal fun DashboardEffects(viewModel: DashboardViewModel, state: DashboardScreenState, portrait: Boolean) {
    TimerEffects(viewModel, state)
    IncomingEffects(viewModel, state)
    PlanningEffects(viewModel, state, portrait)
    RemoteEffects(viewModel, state)
    RidingEffects(viewModel, state)
}

/** Banners, toasts and questions that go away by themselves. */
@Composable
private fun TimerEffects(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val phone = viewModel.phoneCompanion
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    // A message that arrives over the full-screen map: a banner for a few seconds.
    val bannerKey = newestMessage(notifications)?.let { bannerKey(it) }
    LaunchedEffect(bannerKey) { if (bannerKey != null) { delay(MESSAGE_BANNER_MS); state.bannerHidden = bannerKey } }
    LaunchedEffect(state.roadNameShown) { if (state.roadNameShown) { delay(6_000); state.roadNameShown = false } }
    val resumeOffer by viewModel.resumeOffer.collectAsStateWithLifecycle()
    // Nothing pressed: it is a new ride, and the question goes away.
    LaunchedEffect(resumeOffer) { if (resumeOffer != null) { delay(30_000); viewModel.startNewRide() } }
    LaunchedEffect(state.showSettings) { if (!state.showSettings) { state.settingsOnEngine = false; state.settingsOnHelp = false } }
    // The welcome tour: once on the first start (when the screen has settled), and from Settings → Help.
    // The very first one starts with the choice of language.
    LaunchedEffect(Unit) { if (!viewModel.tourSeen) { delay(900); state.tourIsFirst = true; state.showTour = true } }
    val toast = state.toast
    // Long enough to read: a message that says what to do stays up longer than "Saved".
    LaunchedEffect(toast) { toast?.let { delay((2500L + (it.length - 60).coerceAtLeast(0) * 40L).coerceAtMost(7000L)); state.toast = null } }
    LaunchedEffect(state.reachedStop) { if (state.reachedStop != null) { delay(8000); state.reachedStop = null } }
    val track by phone.currentTrack.collectAsStateWithLifecycle()
    // The music on the full map folds back to its icon after a while.
    LaunchedEffect(state.mediaOpen, track?.isPlaying, track?.title) { if (state.mediaOpen) { delay(10_000); state.mediaOpen = false } }
}

/** What comes in from the phone, the web planner, the group and the app itself: a toast, a banner, a dialog. */
@Composable
private fun IncomingEffects(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val context = LocalContext.current
    val phone = viewModel.phoneCompanion
    LaunchedEffect(phone) { phone.phoneErrors.collect { state.toast = it } }
    // Trips from the web planner: Planned → From the web, and a word when new ones came in.
    val webPlan by viewModel.webPlan.collectAsStateWithLifecycle()
    LaunchedEffect(webPlan.report) {
        if (webPlan.report == 0L || webPlan.report == state.webPlanShownReport) return@LaunchedEffect
        state.webPlanShownReport = webPlan.report
        val added = webPlan.added ?: 0
        if (added + webPlan.changed + webPlan.deleted + webPlan.tracks > 0) state.toast = listOfNotNull(
            added.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.web_plan_new, it, it) },
            webPlan.tracks.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.web_plan_tracks, it, it) },
            webPlan.changed.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.web_plan_changed, it, it) },
            webPlan.deleted.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.web_plan_deleted, it, it) },
        ).joinToString(" · ")
    }
    // Trips for the tablet, sent from Curveon on the phone through Curveon Companion: only offered when it is installed.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        state.companionInstalled = TabletRoutes.companionInstalled(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.routeFromPhoneUnreadable.collect { state.toast = context.getString(R.string.toast_route_from_phone_unreadable, it) }
    }
    val sharedChanged by viewModel.sharedTrips.justChanged.collectAsStateWithLifecycle()
    LaunchedEffect(sharedChanged) {
        sharedChanged?.let {
            state.toast = context.getString(R.string.toast_shared_trip_changed, it.fromNickname ?: "", it.doc.name)
            viewModel.sharedTrips.clearJustChanged()
        }
    }
    LaunchedEffect(Unit) { viewModel.stopReached.collect { state.reachedStop = it } }
    LaunchedEffect(Unit) { viewModel.updateAnnounced.collect { state.toast = context.getString(R.string.toast_update_available, it) } }
    LaunchedEffect(Unit) { viewModel.takeNewChanges().takeIf { it.isNotEmpty() }?.let { delay(1200); state.whatsNew = it } }
    // Fresh call log each time the phone opens (the contacts come along).
    LaunchedEffect(state.phoneTab != null) { if (state.phoneTab != null) phone.refreshPhoneBook() }
    // A GPX file opened from another app: straight to its list.
    val showGpxPanel by viewModel.showGpxPanel.collectAsStateWithLifecycle()
    LaunchedEffect(showGpxPanel) {
        if (!showGpxPanel) return@LaunchedEffect
        if (state.activePanel != RailPanel.GPX) state.pressRail(RailPanel.GPX)
        state.tracksTab = TracksTab.GPX
        viewModel.gpxPanelShown()
    }
}

/** Planning, picking and drawing on the map, and the view (map or dashboard) that goes with them. */
@Composable
private fun PlanningEffects(viewModel: DashboardViewModel, state: DashboardScreenState, portrait: Boolean) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val navState by viewModel.navigation.collectAsStateWithLifecycle()
    val weatherOn by viewModel.weather.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(preview == null) { if (preview == null) state.previewMinimized = false }
    LaunchedEffect(state.sketching) { if (state.sketching) state.sketchKm = null else state.adventure = null }
    // While drawing: the weather round where the loop starts, on the map, for a loop about as long as chosen.
    LaunchedEffect(state.sketching, state.sketchMaxKm, state.roundtripStart, weatherOn) {
        state.weatherPickFailed = false
        if (state.sketching && weatherOn) viewModel.weather.showSketchWeather(state.sketchMaxKm, state.roundtripStart?.first?.let { com.curveon.routing.LatLon(it.latitude, it.longitude) })
        else viewModel.weather.clearSketchWeather()
    }
    LaunchedEffect(navState.preview == null) { if (navState.preview == null) state.editingRoute = false }
    // Picking on (and editing the trip on) the map, or the search back up from the pick bar: the map
    // goes full-screen, and the dashboard comes back afterwards if that's where the rider was.
    val mapMode = state.mapMode
    LaunchedEffect(mapMode) {
        if (mapMode) {
            if (state.dashboardBefore == null) state.dashboardBefore = !isFullScreenMap
            viewModel.setFullScreenMap(true)
        } else state.dashboardBefore?.let { if (it) viewModel.setFullScreenMap(false); state.dashboardBefore = null }
    }
    // Writing an SMS or a reply from the full map: its card lives on the dashboard, so the dashboard comes
    // while it is written, and the full map goes back once it is sent or cancelled.
    val voiceReply by viewModel.phoneCompanion.voiceReply.collectAsStateWithLifecycle()
    val replying = voiceReply != null
    LaunchedEffect(replying) {
        if (replying) {
            if (isFullScreenMap && !mapMode) { state.mapBeforeReply = true; viewModel.setFullScreenMap(false) }
        } else if (state.mapBeforeReply) {
            state.mapBeforeReply = false
            viewModel.setFullScreenMap(true)
        }
    }
    val planGoal = preview?.goal as? NavigationGoal.Destination
    LaunchedEffect(planGoal == null) { if (planGoal == null) state.editStopMenu = null }
    androidx.activity.compose.BackHandler(enabled = state.mapPick != null) { state.endMapPick() }
    androidx.activity.compose.BackHandler(enabled = state.sketching) { state.sketching = false }
    // Editing on the map: the last route found, kept on screen (with the dashed guess) while the next one is worked out.
    val selectedLine = remember(preview?.selectedRoute) { preview?.selectedRoute?.points?.map { GeoPoint(it.lat, it.lon) } }
    LaunchedEffect(selectedLine) { if (selectedLine != null) state.editLine = selectedLine }
    // Point by point shows its help each time it starts on a planned trip.
    val planningOnMap = state.chaining(isNavigating) && planGoal != null
    LaunchedEffect(planningOnMap) { state.showPbpHelp = planningOnMap && !viewModel.pointByPointHelpHidden }
    // Portrait: see above. Turning the phone: the view last used in that orientation (not while the map is taken over for picking).
    LaunchedEffect(portrait) { viewModel.setOrientation(portrait, restore = !mapMode) }
}

/** The handlebar remote: what it asks of this screen, and the call its OK and Back then answer. */
@Composable
private fun RemoteEffects(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val phone = viewModel.phoneCompanion
    val remote = LocalRemote.current
    DisposableEffect(remote) {
        val actions = object : RemoteDashboard {
            override fun whereTo(speak: Boolean) {
                state.speakOnOpen = speak
                if (state.activePanel != RailPanel.SEARCH) state.pressRail(RailPanel.SEARCH)
            }
            override fun mapOrDashboard() { state.activePanel?.let(state::pressRail); viewModel.toggleFullScreenMap() }
            override fun nextTrack() = phone.skipNext()
            override fun previousTrack() = phone.skipPrevious()
            override fun playPause() = phone.togglePlayPause()
            override val mapCovered get() = state.activePanel != null
        }
        remote?.dashboard = actions
        onDispose { if (remote?.dashboard === actions) remote.dashboard = null }
    }
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    val ringing = notifications.firstOrNull { it.isIncomingCall }
    val callOngoing = ringing?.isOngoingCall == true
    DisposableEffect(remote, ringing, callOngoing) {
        remote?.call = ringing?.let { call ->
            RemoteCall(
                ringing = !callOngoing,
                answer = { callOtherIndex(call)?.let { phone.invokeAction(call.id, it) } },
                decline = { callDeclineIndex(call)?.let { phone.invokeAction(call.id, it) } ?: phone.hideNotification(call.id) }
            )
        }
        onDispose { remote?.call = null }
    }
}

/** While riding: the group's riders dimmed when not heard from, and a ride recorded while standing still. */
@Composable
private fun RidingEffects(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val groups = viewModel.phoneCompanion.groups
    val groupActive by groups.activeId.collectAsStateWithLifecycle()
    val friendPositions by groups.friendPositions.collectAsStateWithLifecycle()
    // A rider not heard from for two minutes is dimmed, even if nothing else changes on screen.
    LaunchedEffect(groupActive != null || friendPositions.isNotEmpty()) {
        while (groupActive != null || friendPositions.isNotEmpty()) { delay(30_000); state.groupTick++ }
    }
    // A ride is being recorded while the bike stands still (a few seconds, so not at every red light):
    // a red dot on the GPX button and the Rides page, as a reminder that it goes on until the long stop.
    val telemetryState = viewModel.telemetry.collectAsStateWithLifecycle()
    val speedBelowStanding by remember { derivedStateOf { telemetryState.value.speedKmh < 3f } }
    val currentRide by viewModel.currentRide.collectAsStateWithLifecycle()
    val standing = currentRide != null && speedBelowStanding
    LaunchedEffect(standing) { if (standing) { delay(5_000); state.recordingResting = true } else state.recordingResting = false }
}
