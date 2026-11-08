package com.curveon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.model.GeoPoint
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.RoutePreview
import com.curveon.ui.components.*
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch

/**
 * Where the cards sit over the map: the trip bar top left, the speed top right, the next turn
 * bottom left and the music (or a call) bottom right, one to a corner. The map's buttons keep to
 * the right edge, between the speed and the music, and nothing covers them.
 */
@Immutable
internal data class MapCorners(
    val mapWidth: Dp,
    /** Planning, picking and drawing have the bottom (and the bar's middle); the trip bar and the turn wait. */
    val showTripBar: Boolean,
    val showTurn: Boolean,
    val showMedia: Boolean,
    /** The music icon beside the turn card (else above it). */
    val sideBySide: Boolean,
    val roadCardTop: Dp,
    val buttonsTopInset: Dp,
    val buttonsBottomInset: Dp,
    val rightCardEnd: Dp,
    val turnWidth: Dp,
    /** How far up from the bottom the cards reach: the rider is lifted to keep clear of them. */
    val bottomClearance: Dp,
    /** What the status chips (and a wrong turn) must stay above on the left. */
    val leftClearance: Dp,
)

@Composable
private fun rememberMapCorners(
    state: DashboardScreenState,
    preview: RoutePreview?,
    hasTurn: Boolean,
    hasTripSummary: Boolean,
    isFullScreenMap: Boolean,
    showMapMedia: Boolean,
    callLeavesMusic: Boolean,
    hasTrack: Boolean,
): MapCorners {
    val density = LocalDensity.current
    val mapWidth = state.mapBox?.width?.let { with(density) { it.toDp() } } ?: 0.dp
    val mapHeight = state.mapBox?.height?.let { with(density) { it.toDp() } } ?: 0.dp
    val showTripBar = preview == null && !state.sketching && state.mapPick == null && !state.searchGrowIn
    val showTurn = hasTurn && showTripBar
    // A ringing call is a big card over everything (see [CallLayer]); an answered one is a floating icon
    // that leaves the music where it is.
    val showMedia = isFullScreenMap && showMapMedia && callLeavesMusic && preview == null && state.mapPick == null && !state.sketching && hasTrack
    // The music is a small cover icon until tapped, then the player as it was; the turn card and the map's
    // buttons make room for whichever is showing.
    val mediaHeight = if (state.mediaOpen) BOTTOM_CARD_HEIGHT else MEDIA_ICON_SIZE
    // Beside the turn card when there is room for the icon, else above it. Opening the player never moves
    // it: it only gets as wide as the room beside the turn card allows.
    val sideBySide = mapWidth - 14.dp - 8.dp - MEDIA_ICON_SIZE - MAP_BUTTONS_RESERVE >= BOTTOM_TURN_MIN_WIDTH
    val bottomRightHeight = mediaHeight + if (showTurn && !sideBySide) BOTTOM_CARD_HEIGHT + 8.dp else 0.dp
    // Top left: the arrival box (the trip bar) first, as high as it is right now (folded or folded out), and the road
    // card under it; with no trip bar the gravel heat button is first. The card is moved down, so they never meet.
    val tripBarShown = showTripBar && hasTripSummary
    val roadCardTop = 14.dp + when { tripBarShown -> state.tripBarHeight + 8.dp; state.heatButtonShown -> 52.dp + 8.dp; else -> 0.dp }
    val buttonsTopInset = 14.dp + state.speedSize.height + 8.dp
    // The music keeps the corner if the buttons still fit above it; otherwise it moves left of them.
    val buttonsFitAboveMedia = mapHeight - buttonsTopInset - (bottomRightHeight + 14.dp + 8.dp) >= MAP_BUTTONS_HEIGHT
    val buttonsBottomInset = if (showMedia && buttonsFitAboveMedia) bottomRightHeight + 14.dp + 8.dp else 0.dp
    val rightCardEnd = if (showMedia && !buttonsFitAboveMedia) MAP_BUTTONS_RESERVE else 14.dp
    val turnWidth = (if (sideBySide && showMedia) minOf(BOTTOM_TURN_WIDTH, mapWidth - 14.dp - 8.dp - MEDIA_ICON_SIZE - MAP_BUTTONS_RESERVE)
        else minOf(BOTTOM_TURN_WIDTH, mapWidth - 14.dp - MAP_BUTTONS_RESERVE)).coerceAtLeast(0.dp)
    val bottomClearance = maxOf(if (showTurn) BOTTOM_CARD_HEIGHT + 14.dp else 0.dp, if (showMedia) bottomRightHeight + 14.dp else 0.dp)
    val leftClearance = if (showTurn) BOTTOM_CARD_HEIGHT + 14.dp else 0.dp
    return MapCorners(mapWidth, showTripBar, showTurn, showMedia, sideBySide, roadCardTop, buttonsTopInset,
        buttonsBottomInset, rightCardEnd, turnWidth, bottomClearance, leftClearance)
}

/** The map, as big as the layout allows, with what sits over it. */
@Composable
internal fun DashboardMapArea(viewModel: DashboardViewModel, state: DashboardScreenState, modifier: Modifier) {
    val phone = viewModel.phoneCompanion
    val currentTurnInstruction by viewModel.currentTurnInstruction.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val navigationSummary by viewModel.navigationSummary.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val showMapMedia by viewModel.showMapMedia.collectAsStateWithLifecycle()
    val track by phone.currentTrack.collectAsStateWithLifecycle()
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    val ringing = notifications.firstOrNull { it.isIncomingCall }
    val callOngoing = ringing?.isOngoingCall == true
    val corners = rememberMapCorners(
        state, preview, hasTurn = currentTurnInstruction != null, hasTripSummary = navigationSummary != null,
        isFullScreenMap = isFullScreenMap, showMapMedia = showMapMedia, callLeavesMusic = ringing == null || callOngoing,
        hasTrack = track != null
    )
    val overlays: @Composable BoxScope.() -> Unit = { MapOverlays(viewModel, state, corners) }
    Box(modifier.tourTarget(TourTarget.MAP)) {
        DashboardMap(viewModel, state, corners, overlays)
        MapBanners(viewModel, state)
    }
}

@Composable
private fun DashboardMap(
    viewModel: DashboardViewModel,
    state: DashboardScreenState,
    corners: MapCorners,
    overlays: @Composable BoxScope.() -> Unit,
) {
    // The GPS fix (about once a second) changes these: they are read only here (and in the speed), so a fix redraws
    // the map and not the whole screen.
    val telemetryState = viewModel.telemetry.collectAsStateWithLifecycle()
    val currentLocationState = viewModel.currentLocation.collectAsStateWithLifecycle()
    val riderFixState = viewModel.riderFix.collectAsStateWithLifecycle()
    val currentTurnInstruction by viewModel.currentTurnInstruction.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val demoRiding by viewModel.isDemoRiding.collectAsStateWithLifecycle()
    val mapDark by viewModel.mapDark.collectAsStateWithLifecycle()
    val roadContrast by viewModel.roadContrast.collectAsStateWithLifecycle()
    val mapDetail by viewModel.mapDetail.collectAsStateWithLifecycle()
    val surfaceMode by viewModel.surfaceMode.collectAsStateWithLifecycle()
    val gravelHeatButton by viewModel.gravelHeatButton.collectAsStateWithLifecycle()
    val showRoadInfo by viewModel.showRoadInfo.collectAsStateWithLifecycle()
    val trackHint by viewModel.trackHint.collectAsStateWithLifecycle()
    val trackPois by viewModel.overlayPois.collectAsStateWithLifecycle()
    val layerPois by viewModel.mapPois.collectAsStateWithLifecycle()
    // Waypoints of visible tracks, and the kinds of place the rider keeps on the map.
    val overlayPois = remember(trackPois, layerPois) { trackPois + layerPois }
    val sketchWeather by viewModel.weather.sketch.collectAsStateWithLifecycle()
    val weatherOn by viewModel.weather.enabled.collectAsStateWithLifecycle()
    val mapWeather by viewModel.weather.map.collectAsStateWithLifecycle()
    val planGoal = preview?.goal as? NavigationGoal.Destination
    val editor = rememberRouteEditor(viewModel, state, preview, state.chaining(isNavigating))
    val sketching = state.sketching
    CurveonMapView(
        location = currentLocationState.value,
        riderFix = riderFixState.value, demoRide = demoRiding, onDemoTap = { state.demoMenu = true },
        headingDeg = telemetryState.value.headingDeg,
        speedKmh = telemetryState.value.speedKmh,
        speedLimitKmh = telemetryState.value.speedLimitKmh,
        hasGpsFix = telemetryState.value.hasGpsFix,
        gpsLost = telemetryState.value.gpsLost || telemetryState.value.isDeadReckoning,
        routePoints = viewModel.activeRoutePoints.collectAsStateWithLifecycle().value,
        routeSurfaces = viewModel.activeRouteSurfaces.collectAsStateWithLifecycle().value,
        giveWaySigns = viewModel.mapGiveWays.collectAsStateWithLifecycle().value,
        speedSigns = viewModel.mapSpeedSigns.collectAsStateWithLifecycle().value,
        useMph = telemetryState.value.useMph,
        groundPreference = viewModel.groundPreference.collectAsStateWithLifecycle().value,
        currentTurnInstruction = currentTurnInstruction,
        isFullScreen = isFullScreenMap,
        bottomClearance = corners.bottomClearance,
        buttonsTopInset = corners.buttonsTopInset,
        onHeatButtonShown = { state.heatButtonShown = it },
        buttonsBottomInset = corners.buttonsBottomInset,
        dark = mapDark,
        roadShade = if (mapDark) roadContrast.nightRoad to roadContrast.nightPath else roadContrast.road to roadContrast.path,
        previewRoutes = rememberMapPreviewRoutes(state, preview, editor != null),
        markers = rememberMapMarkers(viewModel, state, preview, editor != null),
        groupRiders = rememberGroupRiders(viewModel, state.groupTick),
        editor = editor,
        // The route starts on the nearest road the map joins up: show the way to it.
        approachTo = rememberMapApproach(viewModel, preview),
        onEditRoute = if (planGoal != null && state.mapPick == null) state::editOnMap else null,
        previewSheetMinimized = state.previewMinimized,
        surfaceWays = viewModel::surfaceWays,
        surfaceMode = surfaceMode,
        surfaceZoom = mapDetail.surfaceZoom,
        gravelHeat = viewModel.gravelHeat.takeIf { gravelHeatButton != GravelHeatButton.OFF },
        showGravelHeat = viewModel.showGravelHeat.collectAsStateWithLifecycle().value,
        gravelHeatOnMap = gravelHeatButton == GravelHeatButton.MAP,
        buildings3d = viewModel.buildings3d.collectAsStateWithLifecycle().value,
        showZoom = viewModel.showZoom.collectAsStateWithLifecycle().value,
        townSquares = viewModel.townSquares.collectAsStateWithLifecycle().value,
        planningTrip = preview != null || state.mapPick != null || sketching,
        onShowGravelHeat = viewModel::setShowGravelHeat,
        roadNameProbe = viewModel.roadNameProbe.collectAsStateWithLifecycle().value,
        onRoadName = viewModel::onRoadName,
        onRoadHere = if (showRoadInfo) viewModel::onRoadHere else null,
        onRoadsNear = if (isNavigating) null else viewModel::onRoadsNear,
        // Held on the map: go there or keep it as a favourite (riding or planning: also a stop or the start).
        onLongPress = state::longPress,
        onTap = if (state.mapPick != null) ({ p ->
            if (state.chaining(viewModel.isNavigating.value)) state.chainTap(p) else state.pickPoint(p)
        }) else null,
        pickedPoint = state.picked?.first,
        sketch = rememberMapSketch(preview?.goal),
        onSketchProgress = { stroke ->
            state.sketchKm = if (stroke.size < 3) null
            else ((stroke + stroke.first()).zipWithNext { a, b -> com.curveon.routing.Geo.distance(a.latitude, a.longitude, b.latitude, b.longitude) }.sum() / 1000).toInt()
        },
        onSketch = if (sketching) state::finishSketch else null,
        weatherSpots = { if (state.sketching) sketchWeather else mapWeather },
        // On the full-screen map, with weather to be had (internet here, or the phone): the map hides it while riding.
        weatherMapOn = viewModel.weatherMap.collectAsStateWithLifecycle().value,
        onWeatherMap = if (isFullScreenMap && weatherOn && !sketching && viewModel.phoneCompanion.canFetchWeather) viewModel::setWeatherMap else null,
        onWeatherButtonShown = viewModel::setWeatherMapShown,
        gpxTracks = viewModel.overlayTracks.collectAsStateWithLifecycle().value,
        gpxPois = overlayPois,
        trackHint = trackHint.takeIf { preview == null && !isNavigating },
        gpxOnRoute = viewModel.gpxOnRoute.collectAsStateWithLifecycle().value,
        onPoiClick = { state.shownPoi = it },
        onGroupRiderClick = { state.shownRiders = it },
        onViewport = viewModel::setViewport,
        autoZoom = viewModel.autoZoom.collectAsStateWithLifecycle().value,
        mapVersion = viewModel.mapVersion.collectAsStateWithLifecycle().value,
        overlays = overlays
    )
}

/**
 * Editing on the map: the trip's places as handles. Point by point shows the trip's points the same way, so they can
 * be dragged while building it (and the map stays where the rider is tapping instead of fitting the whole trip each time).
 */
@Composable
private fun rememberRouteEditor(viewModel: DashboardViewModel, state: DashboardScreenState, preview: RoutePreview?, chaining: Boolean): RouteEditor? {
    val planGoal = preview?.goal as? NavigationGoal.Destination
    val selectedCandidate = preview?.candidates?.getOrNull(preview.selected)
    val marking = state.marking
    val routeLocked = state.routeLocked
    return if (!chaining || planGoal == null) null else remember(planGoal, selectedCandidate, marking, routeLocked) {
        var number = 0
        val handles = buildList {
            planGoal.start?.let { add(RouteHandle(GeoPoint(it.point.lat, it.point.lon), HandleKind.START)) }
            planGoal.stops.forEachIndexed { i, st ->
                add(RouteHandle(GeoPoint(st.point.lat, st.point.lon), if (st.via) HandleKind.VIA else HandleKind.STOP, i, if (st.via) "" else "${++number}"))
            }
            add(RouteHandle(GeoPoint(planGoal.destination.lat, planGoal.destination.lon), HandleKind.DESTINATION))
        }
        // A tap or a long press on a point: its menu (move, remove, …).
        val openMenu: (RouteHandle) -> Unit = { h ->
            when (h.kind) {
                HandleKind.STOP, HandleKind.VIA -> state.editStopMenu = h.stopIndex
                else -> state.endMenu = h.kind
            }
        }
        val scope = state.scope
        RouteEditor(
            handles = handles,
            busy = selectedCandidate != null && selectedCandidate.route == null && selectedCandidate.error == null,
            onMoveHandle = { h, at ->
                scope.launch {
                    val name = viewModel.describePoint(at)
                    when (h.kind) {
                        HandleKind.START -> viewModel.setPreviewStart(at, name)
                        HandleKind.DESTINATION -> viewModel.setPreviewDestination(at, name)
                        HandleKind.STOP -> viewModel.replacePreviewStop(h.stopIndex, at, name)
                        HandleKind.VIA -> viewModel.replacePreviewStop(h.stopIndex, at, name, via = true)
                    }
                }
            },
            onInsert = { i, at -> scope.launch { viewModel.insertPreviewStop(i, at, viewModel.describePoint(at), via = true) } },
            onTapHandle = openMenu,
            marking = marking,
            locked = routeLocked,
            onLongPressHandle = openMenu,
            onTapLine = state::chainTap,
            onMarkSection = { fromIndex, from, toIndex, to ->
                state.marking = false
                scope.launch {
                    viewModel.pinPreviewSection(fromIndex, from, viewModel.describePoint(from), toIndex, to, viewModel.describePoint(to))
                }
            }
        )
    }
}

/**
 * What the map is given of the trip being planned, worked out when the plan changes and not on every
 * GPS fix: a long route is hundreds of thousands of points, and rebuilding and comparing them
 * each second kept the phone busy (and the garbage collector running) for nothing.
 */
@Composable
private fun rememberMapPreviewRoutes(state: DashboardScreenState, plan: RoutePreview?, editing: Boolean): List<MapRoute> {
    val editLine = state.editLine
    return remember(editing, editLine, plan?.candidates, plan?.selected, plan?.suggestions, plan?.suggestion) {
        if (editing) listOf(MapRoute(editLine, true)).filter { it.points.size >= 2 }
        else plan?.candidates?.mapIndexedNotNull { i, c ->
            c.route?.let { r ->
                val selected = i == plan.selected
                MapRoute(r.points.map { GeoPoint(it.lat, it.lon) }, selected,
                    surfaces = if (selected) r.points.map { p -> if (p.highway == "track") SURFACE_TRACK else if (p.unpaved) SURFACE_GRAVEL else SURFACE_PAVED } else null)
            }
        }.orEmpty() +
            // The other adventures found, grey beside the one shown, to compare and switch to.
            plan?.suggestions.orEmpty().filterIndexed { i, _ -> i != plan?.suggestion }
                .map { r -> MapRoute(r.points.map { GeoPoint(it.lat, it.lon) }, false) }
    }
}

@Composable
private fun rememberMapMarkers(viewModel: DashboardViewModel, state: DashboardScreenState, plan: RoutePreview?, editing: Boolean): List<MapPin> {
    val markers by viewModel.routeMarkers.collectAsStateWithLifecycle()
    val mapPick = state.mapPick
    val planStart = state.planStart
    return remember(editing, mapPick != null, plan == null, planStart, markers) {
        if (editing) emptyList()
        // Starting point by point somewhere else: its start shows until the first leg is planned.
        else if (mapPick != null && plan == null) markers + listOfNotNull(planStart?.let { (p, _) ->
            MapPin(p, PinKind.START, "A") })
        else markers
    }
}

@Composable
private fun rememberMapApproach(viewModel: DashboardViewModel, plan: RoutePreview?): GeoPoint? {
    val navState by viewModel.navigation.collectAsStateWithLifecycle()
    val planGoal = plan?.goal as? NavigationGoal.Destination
    return remember(plan?.candidates, plan?.selected, planGoal?.start == null, navState.approach) {
        // The route starts on the nearest road the map joins up: show the way to it.
        if (plan != null) plan.selectedRoute?.points?.firstOrNull()?.takeIf { planGoal?.start == null }?.let { GeoPoint(it.lat, it.lon) }
        else navState.approach?.let { GeoPoint(it.lat, it.lon) }
    }
}

@Composable
private fun rememberMapSketch(previewGoal: NavigationGoal?): List<GeoPoint> = remember(previewGoal) {
    when (val g = previewGoal) {
        is NavigationGoal.Roundtrip -> g.area
        is NavigationGoal.Adventure -> g.area
        is NavigationGoal.Destination -> g.loop?.area ?: g.adventure?.area
        else -> null
    }?.map { GeoPoint(it.lat, it.lon) }.orEmpty()
}

/** Group ride: the other riders on the map, and friends who share where they are. */
@Composable
private fun rememberGroupRiders(viewModel: DashboardViewModel, groupTick: Int): List<MapGroupRider> {
    val groups = viewModel.phoneCompanion.groups
    val groupPositions by groups.positions.collectAsStateWithLifecycle()
    val groupMembers by groups.members.collectAsStateWithLifecycle()
    val friendPositions by groups.friendPositions.collectAsStateWithLifecycle()
    val friendRiders by groups.friendRiders.collectAsStateWithLifecycle()
    return remember(groupPositions, groupMembers, friendPositions, friendRiders, groupTick) {
        val now = System.currentTimeMillis()
        // A rider who has stopped sharing is off the map (they said so), not just faded.
        val riders = groupPositions.values.filterNot { it.paused }.map { p ->
            val m = groupMembers[p.id]
            MapGroupRider(
                p.id, GeoPoint(p.lat, p.lon), m?.nickname.orEmpty(), m?.colour ?: 0xFF00E5FF.toInt(), now - p.timeMs > 120_000, m?.photo
            )
        }
        // Friends who share where they are, outside the group: seen once a minute or so, so dimmed only after ten. A friend
        // riding in the group too is shown once, from the group.
        val inGroup = groupMembers.values.map { it.nickname to it.colour }.toSet()
        riders + friendPositions.values.mapNotNull { p ->
            val f = friendRiders[p.id] ?: return@mapNotNull null
            if ((f.nickname to f.colour) in inGroup && groupPositions.isNotEmpty()) return@mapNotNull null
            MapGroupRider("friend:${p.id}", GeoPoint(p.lat, p.lon), f.nickname, f.colour, now - p.timeMs > 600_000, f.photo)
        }
    }
}

/** Over the map's top: the questions after a restart, a stop reached, a new message, the location banner and the toast. */
@Composable
private fun BoxScope.MapBanners(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val phone = viewModel.phoneCompanion
    val resumeOffer by viewModel.resumeOffer.collectAsStateWithLifecycle()
    val navResumeOffer by viewModel.navResumeOffer.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val voiceReply by phone.voiceReply.collectAsStateWithLifecycle()
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    // A message that arrives over the full-screen map: a banner for a few seconds (the message stays with the dashboard's).
    val newestMessage = newestMessage(notifications)
    val bannerKey = newestMessage?.let { bannerKey(it) }
    val bannerFresh = newestMessage != null && System.currentTimeMillis() - newestMessage.timestampMs < MESSAGE_BANNER_MS
    val reachedStop = state.reachedStop
    resumeOffer?.let { RideResumeCard(it.title, onResume = viewModel::resumeRide, onNew = viewModel::startNewRide,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp, start = 12.dp, end = 12.dp).remoteSheet(viewModel::startNewRide)) }
    // Waits while the ride's question is up (it goes by itself once riding on), and is not asked over a trip.
    navResumeOffer?.takeIf { resumeOffer == null && preview == null && !isNavigating }?.let {
        RideResumeCard(it.title, onResume = viewModel::resumeNavigation, onNew = viewModel::declineNavigationResume,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp, start = 12.dp, end = 12.dp).remoteSheet(viewModel::declineNavigationResume),
            heading = stringResource(R.string.nav_resume_title), resumeLabel = stringResource(R.string.nav_resume_yes), newLabel = stringResource(R.string.nav_resume_no), tag = "nav_resume")
    }
    reachedStop?.let { StopReachedBanner(it, onDismiss = { state.reachedStop = null }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp)) }
    androidx.compose.animation.AnimatedVisibility(
        visible = isFullScreenMap && voiceReply == null && newestMessage != null && bannerFresh && bannerKey != state.bannerHidden && resumeOffer == null && reachedStop == null,
        enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp, start = 12.dp, end = 12.dp)
    ) {
        newestMessage?.let { m ->
            MapMessageBanner(m, onRead = { viewModel.readAloud(m.senderName, m.message); state.bannerHidden = bannerKey },
                onReply = { phone.startVoiceReply(m.id); state.bannerHidden = bannerKey }, onClose = { state.bannerHidden = bannerKey })
        }
    }
    // Top left, beside the speed: clear of the preview sheet and the cards, however low the screen.
    LocationBanner(onAllowed = viewModel::startGps, modifier = Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 12.dp, end = 120.dp))
    state.toast?.let {
        Text(it, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(RoundedCornerShape(12.dp))
                .background(CockpitSurface).border(1.dp, ElectricCyan, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** How long a message that arrives over the full-screen map stays up as a banner. */
internal const val MESSAGE_BANNER_MS = 10_000L

/** The newest message (not a call): the one a banner over the full-screen map shows. */
internal fun newestMessage(notifications: List<com.curveon.data.model.NotificationItem>) =
    notifications.filterNot { it.isIncomingCall }.maxByOrNull { it.timestampMs }

internal fun bannerKey(m: com.curveon.data.model.NotificationItem) = m.id + m.timestampMs

/**
 * ~1/3 of the 7" tablet's 960 dp width. Narrower made cover art and messages too small to read
 * at a glance; the map still gets ~55% of the screen and goes full width with one tap.
 */
internal val SIDE_COLUMN_WIDTH = 300.dp
/** The turn card at the bottom left and the media player at the bottom right are this high. */
internal val BOTTOM_CARD_HEIGHT = 72.dp
private val BOTTOM_TURN_WIDTH = 320.dp
private val BOTTOM_TURN_MIN_WIDTH = 190.dp
/** The map's button column (CurveonMapView): four 52 dp buttons, 10 dp apart. */
private val MAP_BUTTONS_HEIGHT = 52.dp * 4 + 10.dp * 3
/** From the map's right edge: the buttons, their margin and a gap; cards on the left stay clear of it. */
private val MAP_BUTTONS_RESERVE = 14.dp + 52.dp + 8.dp
