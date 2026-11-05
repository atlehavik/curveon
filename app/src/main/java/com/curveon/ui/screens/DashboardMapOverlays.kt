package com.curveon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.model.GeoPoint
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.RoutePreview
import com.curveon.ui.components.*
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch

/** What lies over the map: the trip bar, the speed, the road card, the next turn, the planning bars and sheets, the music. */
@Composable
internal fun BoxScope.MapOverlays(viewModel: DashboardViewModel, state: DashboardScreenState, corners: MapCorners) {
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val demoRiding by viewModel.isDemoRiding.collectAsStateWithLifecycle()
    val demoSignalLost by viewModel.demoSignalLost.collectAsStateWithLifecycle()
    val demoWrongTurn by viewModel.demoWrongTurn.collectAsStateWithLifecycle()
    // The demo ride's tricks, from a tap on its DEMO tag: things that can't be tried at home on a real ride.
    if (state.demoMenu && demoRiding) DemoRideMenu(
        signalLost = demoSignalLost, wrongTurnAhead = demoWrongTurn, navigating = isNavigating,
        onSignalLost = { viewModel.setDemoSignalLost(it); state.demoMenu = false },
        onWrongTurn = { viewModel.demoWrongTurnAhead(); state.demoMenu = false },
        onDismiss = { state.demoMenu = false }
    )
    // Where the map is, so the corners know how much room there is.
    Box(Modifier.matchParentSize().onGloballyPositioned { state.mapBox = it.boundsInRoot() })
    MapCornerCards(viewModel, state, corners)
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    // Picking a place: just the map and the little bar; the rest waits.
    val pickPurpose = state.mapPick
    if (state.sketching) {
        SketchBarOverMap(viewModel, state)
    } else if (pickPurpose != null) {
        MapPickBarOverMap(viewModel, state, preview, pickPurpose, state.chaining(isNavigating))
    } else if (!state.searchGrowIn) { // nor while the search is back up over the map
        MapStatusAndPreview(viewModel, state, corners, preview, isNavigating)
    }
    MapMediaCorner(viewModel, state, corners)
}

/** The trip bar top left (folding out downwards), the speed top right, the road card, and the next turn bottom left. */
@Composable
private fun BoxScope.MapCornerCards(viewModel: DashboardViewModel, state: DashboardScreenState, corners: MapCorners) {
    val density = LocalDensity.current
    val phone = viewModel.phoneCompanion
    val telemetryState = viewModel.telemetry.collectAsStateWithLifecycle()
    val navigationSummary by viewModel.navigationSummary.collectAsStateWithLifecycle()
    val fuelHint by viewModel.fuelHint.collectAsStateWithLifecycle()
    val cameraAlert by viewModel.cameraAlert.collectAsStateWithLifecycle()
    val showAltitude by viewModel.showAltitude.collectAsStateWithLifecycle()
    val showClock by viewModel.showClock.collectAsStateWithLifecycle()
    val showBattery by viewModel.showBattery.collectAsStateWithLifecycle()
    val showPhoneBattery by viewModel.showPhoneBattery.collectAsStateWithLifecycle()
    val speedSign by viewModel.speedSign.collectAsStateWithLifecycle()
    val roadCardSize by viewModel.roadCardSize.collectAsStateWithLifecycle()
    val phoneStatusNow by phone.phoneStatus.collectAsStateWithLifecycle()
    val thisDeviceIsPhone by phone.thisDevice.collectAsStateWithLifecycle()
    val phoneState by phone.connectionState.collectAsStateWithLifecycle()
    val roadHere by viewModel.currentRoad.collectAsStateWithLifecycle()
    val freeGiveWay by viewModel.freeGiveWay.collectAsStateWithLifecycle()
    val giveWayAlert by viewModel.giveWayAlert.collectAsStateWithLifecycle()
    val currentTurnInstruction by viewModel.currentTurnInstruction.collectAsStateWithLifecycle()
    // The road card: the road's number and priority sign, and a give way or stop just ahead (on the route
    // when navigating, else on the road ridden): the one place for the junction's sign and its distance.
    val giveWayAhead = giveWayAlert ?: freeGiveWay
    val currentRoad = roadHere ?: giveWayAhead?.let { com.curveon.data.road.CurrentRoad(null, false) }
    val mapWidth = corners.mapWidth
    val speedSize = state.speedSize
    if (corners.showTripBar) navigationSummary?.let {
        NavigationSummaryBar(
            it, onStop = { viewModel.stopNavigation() },
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
                .widthIn(max = (mapWidth - 14.dp - 14.dp - 8.dp - speedSize.width).coerceAtLeast(0.dp))
                .onSizeChanged { state.tripBarWidth = with(density) { it.width.toDp() }; state.tripBarHeight = with(density) { it.height.toDp() } },
            foldedHeight = speedSize.height,
            fuel = fuelHint,
            onFuel = { fuelHint?.let { f -> viewModel.addStop(GeoPoint(f.point.lat, f.point.lon), f.name) } },
            onAddStop = { state.searchPurpose = SearchPurpose.STOP },
            onRemoveStop = { viewModel.removeStop(it) },
            onOpen = { state.showRouteMenu = true }
        )
    }
    MapSpeedCluster({ telemetryState.value }, cameraAlert, Modifier.align(Alignment.TopEnd).padding(14.dp)
        .onSizeChanged { state.speedSize = with(density) { androidx.compose.ui.unit.DpSize(it.width.toDp(), it.height.toDp()) } }, showAltitude = showAltitude, showClock = showClock, showBattery = showBattery, signSize = speedSign.dp.dp,
        // The pocket phone's battery: only with a companion phone connected (on "This device" it's the battery above).
        phoneBattery = phoneStatusNow?.takeIf { !thisDeviceIsPhone && phoneState == CompanionConnectionState.CONNECTED || phoneState == CompanionConnectionState.DEMO }
            ?.let { it.batteryPercent to it.charging }, showPhoneBattery = showPhoneBattery)
    // The road's number and priority sign in a card of their own: top left, under the arrival box (or the heat button).
    currentRoad?.let { road ->
        CurrentRoadCard(road, giveWay = giveWayAhead, scale = roadCardSize.scale, showName = state.roadNameShown,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = corners.roadCardTop)
                .widthIn(max = (mapWidth - 14.dp - 14.dp - 8.dp - speedSize.width).coerceAtLeast(0.dp))
                .onGloballyPositioned { state.roadCardBounds = it.boundsInRoot() }
                .clickable(enabled = road.name != null) { state.roadNameShown = !state.roadNameShown })
    }
    // The next turn bottom left, as narrow as it can be.
    if (corners.showTurn) {
        TurnByTurnBanner(currentTurnInstruction!!, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
            .onSizeChanged { state.turnCardWidth = with(density) { it.width.toDp() } }, maxWidth = corners.turnWidth)
    }
}

/** Drawing a loop's or an adventure's area: the bar at the bottom. */
@Composable
private fun BoxScope.SketchBarOverMap(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val weatherOn by viewModel.weather.enabled.collectAsStateWithLifecycle()
    val sketchWeather by viewModel.weather.sketch.collectAsStateWithLifecycle()
    val adventure = state.adventure
    // Drawing takes a finger; with a remote, OK goes to the bar and its "good weather" choice.
    val sketchBarFocus = rememberRemoteMenuTarget()
    SketchBar(onCancel = { state.sketching = false }, km = state.sketchKm?.let { k -> state.sketchMaxKm?.let { minOf(it, k) } ?: k }, startName = state.roundtripStart?.second,
        maxKm = state.sketchMaxKm, onMaxKm = { state.sketchMaxKm = it }, surface = state.sketchSurface, onSurface = { state.sketchSurface = it },
        gravelReachKm = state.sketchGravelReach, onGravelReach = { state.sketchGravelReach = it },
        adventure = adventure != null, adventureMinutes = adventure?.minutes, onAdventureMinutes = { m -> state.adventure = state.adventure?.copy(minutes = m) },
        // Only for a loop, and only with a forecast here to go by.
        onGoodWeather = if (adventure == null && weatherOn && sketchWeather.isNotEmpty()) state::pickGoodWeather else null,
        goodWeatherBusy = state.weatherPicking, goodWeatherFailed = state.weatherPickFailed,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 12.dp, end = 12.dp).focusRequester(sketchBarFocus).focusGroup())
}

/** Picking a place on the map (or planning point by point): the little bar at the bottom, and its help the first times. */
@Composable
private fun BoxScope.MapPickBarOverMap(
    viewModel: DashboardViewModel,
    state: DashboardScreenState,
    preview: RoutePreview?,
    pickPurpose: SearchPurpose,
    chaining: Boolean,
) {
    val planGoal = preview?.goal as? NavigationGoal.Destination
    val selectedCandidate = preview?.candidates?.getOrNull(preview.selected)
    val tripSummary = preview?.selectedRoute?.let { r ->
        val min = (r.durationSeconds / 60).toInt()
        "%d:%02d · ".format(min / 60, min % 60) + stringResource(R.string.distance_km_whole, r.distanceMeters / 1000)
    }
    val picked = state.picked
    val pickBarFocus = rememberRemoteMenuTarget()
    MapPickBar(
        purpose = pickPurpose,
        picked = picked?.let { it.second ?: "…" },
        onConfirm = { state.picked?.let { (p, n) -> if (n != null) { state.usePlace(pickPurpose, p, n); state.endMapPick() } } },
        onBackToSearch = { state.mapPick = null; state.picked = null; state.searchGrowIn = true; state.searchPurpose = pickPurpose },
        onCancel = state::endMapPick,
        pointByPoint = chaining,
        asking = picked?.takeIf { chaining }?.let { it.second ?: "…" },
        onAlongTheWay = { state.picked?.let { (p, n) -> state.alongTheWay = true; state.picked = null; state.planPointByPoint(p, n ?: "") } },
        onNewEnd = { state.picked?.let { (p, n) -> state.alongTheWay = false; state.picked = null; state.planPointByPoint(p, n ?: "") } },
        onShapingPoint = { state.picked?.let { (p, _) -> state.picked = null; state.addShapingPoint(p) } },
        onDismissAsk = { state.picked = null },
        tripSummary = tripSummary.takeIf { planGoal != null },
        busy = preview?.calculating == true || preview?.fetching != null,
        canUndo = preview?.canUndo == true,
        onUndo = viewModel::undoPreviewEdit,
        error = selectedCandidate?.error.takeIf { planGoal != null },
        marking = state.marking.takeIf { planGoal != null },
        onMark = { state.marking = !state.marking },
        locked = state.routeLocked.takeIf { planGoal != null },
        onLock = { state.routeLocked = !state.routeLocked },
        onConfirmShapingPoint = state.stopTarget?.takeIf { pickPurpose == SearchPurpose.STOP }?.let { t -> {
            state.picked?.let { (p, n) ->
                if (n != null) {
                    when (t) {
                        is StopTarget.Before -> viewModel.insertPreviewStop(t.index, p, n, via = true)
                        is StopTarget.Instead -> viewModel.replacePreviewStop(t.index, p, n, via = true)
                    }
                    state.endMapPick()
                }
            }
        } },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).focusRequester(pickBarFocus).focusGroup()
    )
    // How it works, the first times: over the bar, until "Don't show again".
    if (chaining && planGoal != null && state.showPbpHelp) PointByPointHelp(
        onOk = { state.showPbpHelp = false },
        onNever = { state.showPbpHelp = false; viewModel.hidePointByPointHelp() },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp, start = 12.dp, end = 12.dp)
    )
}

/** Not picking nor drawing: the routing status and track chip, a wrong turn, and the trip being planned. */
@Composable
private fun BoxScope.MapStatusAndPreview(
    viewModel: DashboardViewModel,
    state: DashboardScreenState,
    corners: MapCorners,
    preview: RoutePreview?,
    isNavigating: Boolean,
) {
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val routingStatus by viewModel.routingStatus.collectAsStateWithLifecycle()
    val trackHint by viewModel.trackHint.collectAsStateWithLifecycle()
    val wrongTurn by viewModel.wrongTurn.collectAsStateWithLifecycle()
    Column(
        // In full-screen the bottom right has the media pill, so keep this left, above the turn card.
        modifier = (if (isFullScreenMap) Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp + corners.leftClearance)
        else Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp + corners.leftClearance)),
        horizontalAlignment = if (isFullScreenMap) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RoutingStatusCard(routingStatus, onDismiss = { viewModel.dismissRoutingError() })
        if (preview == null && !isNavigating) trackHint?.let { h ->
            TrackChip(h, onClick = { viewModel.followOverlayTrack(h.trackId, h.onTrack && h.reverse) })
        }
    }
    if (preview == null) wrongTurn?.let { wt ->
        WrongTurnCard(
            wt,
            onTurnBack = viewModel::turnBack,
            onCarryOn = { keep -> if (wt.isTrip) viewModel.newRouteFromHere(keep) else viewModel.rejoinAhead() },
            // Below the turn banner, over the middle of the map.
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp).remoteSheet()
        )
    }
    preview?.let { p -> PreviewSheetOverMap(viewModel, state, p) }
}

/** The trip being planned: its sheet at the bottom of the map. */
@Composable
private fun BoxScope.PreviewSheetOverMap(viewModel: DashboardViewModel, state: DashboardScreenState, p: RoutePreview) {
    val previewWeather by viewModel.weather.preview.collectAsStateWithLifecycle()
    val sharingGroup by viewModel.phoneCompanion.groups.activeId.collectAsStateWithLifecycle()
    val scope = state.scope
    RoutePreviewSheet(
        preview = p,
        askBeforeClosing = !state.editingRoute,
        onSelect = viewModel::selectCandidate,
        onSurface = viewModel::setPreviewSurface,
        onToggleFerries = viewModel::togglePreviewAvoidFerries,
        onMotorways = viewModel::setPreviewMotorways,
        onRemoveStop = viewModel::removePreviewStop,
        onShuffle = viewModel::shuffleRoundtrip,
        onSelectSuggestion = viewModel::selectAdventureSuggestion,
        onAddStop = { state.searchPurpose = SearchPurpose.STOP },
        onMoveStop = viewModel::movePreviewStop,
        onEditStart = { state.searchPurpose = SearchPurpose.START },
        onChangeStop = { i -> state.stopTarget = StopTarget.Instead(i); state.searchPurpose = SearchPurpose.STOP },
        onAddStopBefore = { i -> state.stopTarget = StopTarget.Before(i); state.searchPurpose = SearchPurpose.STOP },
        onEditOnMap = state::editOnMap,
        onLoopMax = viewModel::setLoopMax,
        onGravelReach = viewModel::setPreviewGravelReach,
        onAdventure = state::startAdventure,
        onAdventureMinutes = viewModel::setAdventureMinutes,
        onRedraw = state::redraw,
        onReverse = viewModel::reversePreviewTrack,
        onTrackToTrip = viewModel::trackToTrip,
        onSave = state::askSaveName,
        onShare = if (sharingGroup != null) { { state.shareTrip() } } else null,
        onSendToTablet = if (state.companionInstalled) { { scope.launch { state.sendToTablet(viewModel.previewForTablet()) } } } else null,
        onShareFriends = if (p.goal is NavigationGoal.Destination) { { state.pickFriends = true } } else null,
        onGo = viewModel::startPreview,
        onClose = viewModel::cancelPreview,
        minimized = state.previewMinimized,
        onMinimizedChange = { state.previewMinimized = it },
        weather = previewWeather,
        onWeatherVia = viewModel::addWeatherVia,
        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 640.dp).fillMaxWidth()
    )
}

/**
 * In full-screen map keep music reachable, like Android Auto's media pill – except while a
 * route preview needs the bottom (its Go button).
 */
@Composable
private fun BoxScope.MapMediaCorner(viewModel: DashboardViewModel, state: DashboardScreenState, corners: MapCorners) {
    val phone = viewModel.phoneCompanion
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val track by phone.currentTrack.collectAsStateWithLifecycle()
    val albumArt by phone.albumArt.collectAsStateWithLifecycle()
    if (isFullScreenMap) {
        if (corners.showMedia) track?.let { t ->
            val showTurn = corners.showTurn
            val sideBySide = corners.sideBySide
            val rightCardEnd = corners.rightCardEnd
            val mediaModifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = rightCardEnd, bottom = 14.dp + if (showTurn && !sideBySide) BOTTOM_CARD_HEIGHT + 8.dp else 0.dp)
            // Beside the turn card it is no wider than the room left of the map's right edge; else the usual width.
            val room = (corners.mapWidth - 14.dp - rightCardEnd - if (showTurn && sideBySide) 14.dp + state.turnCardWidth + 8.dp else 0.dp).coerceAtLeast(MEDIA_ICON_SIZE)
            if (state.mediaOpen) MiniMediaCard(t, albumArt, { phone.togglePlayPause() }, { phone.skipNext() },
                mediaModifier.width(minOf(SIDE_COLUMN_WIDTH, room)).height(BOTTOM_CARD_HEIGHT),
                onCollapse = { state.mediaOpen = false })
            else MediaPillIcon(t, albumArt, { state.mediaOpen = true }, mediaModifier)
        }
    }
}

/**
 * The nearest track shown on the map. On it: "Follow" to navigate along it. Off it: how far away;
 * tap to ride there and then along it.
 */
@Composable
private fun TrackChip(h: com.curveon.data.gpx.TrackHint, onClick: () -> Unit) {
    val color = androidx.compose.ui.graphics.Color(h.color)
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).background(CockpitSurface.copy(alpha = 0.95f))
            .border(2.dp, color, RoundedCornerShape(22.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("track_chip"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Route, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(h.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.widthIn(max = 260.dp), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.width(10.dp))
        Text(
            if (h.onTrack) stringResource(R.string.follow) else if (h.distanceMeters < 1000) stringResource(R.string.distance_m, h.distanceMeters) else stringResource(R.string.distance_km, h.distanceMeters / 1000),
            color = color, fontSize = 14.sp, fontWeight = FontWeight.Black
        )
    }
}
