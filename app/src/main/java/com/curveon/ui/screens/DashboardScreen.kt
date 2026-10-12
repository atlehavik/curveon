package com.curveon.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.model.GeoPoint
import com.curveon.data.model.GroundPreference
import com.curveon.ui.components.*
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android Auto-inspired cockpit: a slim rail on the left, the map, and an adaptive column with
 * speed, then either the newest message (or a spoken reply in progress) plus a mini player, or
 * – when nothing needs attention – the music card with full-bleed cover art.
 */
@Composable
fun DashboardScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val riderFix by viewModel.riderFix.collectAsStateWithLifecycle()
    val routePoints by viewModel.activeRoutePoints.collectAsStateWithLifecycle()
    val groundPreference by viewModel.groundPreference.collectAsStateWithLifecycle()
    val currentTurnInstruction by viewModel.currentTurnInstruction.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val navigationSummary by viewModel.navigationSummary.collectAsStateWithLifecycle()
    val routingStatus by viewModel.routingStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val phone = viewModel.phoneCompanion
    val track by phone.currentTrack.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val albumArt by phone.albumArt.collectAsStateWithLifecycle()
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    val voiceReply by phone.voiceReply.collectAsStateWithLifecycle()
    val phoneState by phone.connectionState.collectAsStateWithLifecycle()
    val phoneName by phone.connectedDeviceName.collectAsStateWithLifecycle()
    val mediaApps by phone.mediaApps.collectAsStateWithLifecycle()
    /** The call, ringing or answered. */
    val ringing = notifications.firstOrNull { it.isIncomingCall }
    val callOngoing = ringing?.isOngoingCall == true

    val offlineMapStatus by viewModel.offlineMapStatus.collectAsStateWithLifecycle()
    val routingDataStatus by viewModel.routingDataStatus.collectAsStateWithLifecycle()
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()
    val savedGpxTracks by viewModel.savedGpxTracks.collectAsStateWithLifecycle()
    val gpxGroups by viewModel.gpxGroups.collectAsStateWithLifecycle()
    val overlayTracks by viewModel.overlayTracks.collectAsStateWithLifecycle()
    val trackPois by viewModel.overlayPois.collectAsStateWithLifecycle()
    val layerPois by viewModel.mapPois.collectAsStateWithLifecycle()
    val mapLayers by viewModel.mapLayers.collectAsStateWithLifecycle()
    // Waypoints of visible tracks, and the kinds of place the rider keeps on the map.
    val overlayPois = remember(trackPois, layerPois) { trackPois + layerPois }
    val trackHint by viewModel.trackHint.collectAsStateWithLifecycle()
    val poiCategories by viewModel.poiCategories.collectAsStateWithLifecycle()
    val autoFollow by viewModel.autoFollowTracks.collectAsStateWithLifecycle()
    val mapAutoZoom by viewModel.autoZoom.collectAsStateWithLifecycle()
    val showSurfaces by viewModel.showSurfaces.collectAsStateWithLifecycle()
    val showAltitude by viewModel.showAltitude.collectAsStateWithLifecycle()
    val mapVersion by viewModel.mapVersion.collectAsStateWithLifecycle()
    var shownPoi by remember { mutableStateOf<com.curveon.data.gpx.GpxPoi?>(null) }
    val gpxMessage by viewModel.gpxMessage.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val markers by viewModel.routeMarkers.collectAsStateWithLifecycle()
    val roadNameProbe by viewModel.roadNameProbe.collectAsStateWithLifecycle()
    val mapDark by viewModel.mapDark.collectAsStateWithLifecycle()
    val mapTheme by viewModel.mapTheme.collectAsStateWithLifecycle()
    val fuelHint by viewModel.fuelHint.collectAsStateWithLifecycle()
    val cameraAlert by viewModel.cameraAlert.collectAsStateWithLifecycle()
    val obdState by viewModel.obd.state.collectAsStateWithLifecycle()
    val obdValues by viewModel.obdValues.collectAsStateWithLifecycle()
    val savedRoutes by viewModel.savedRoutes.collectAsStateWithLifecycle()
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    val currentRide by viewModel.currentRide.collectAsStateWithLifecycle()
    val autoSaveRidesGroup by viewModel.autoSaveRidesGroup.collectAsStateWithLifecycle()
    val wrongTurn by viewModel.wrongTurn.collectAsStateWithLifecycle()
    val navState by viewModel.navigation.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val importMapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importOfflineMap(uri)
    }
    val importGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importGpx(uri)
    }

    /** The open search and what it is for; null when closed. */
    var searchPurpose by remember { mutableStateOf<SearchPurpose?>(null) }
    /** "Pick on map": what the search was for while the rider taps the map; null when not picking. */
    var mapPick by remember { mutableStateOf<SearchPurpose?>(null) }
    var picked by remember { mutableStateOf<Pair<GeoPoint, String?>?>(null) }
    /**
     * Point by point from "+ Stop" on a trip that already has an end: whether the points tapped are
     * stops on the way (true) or ride the trip on past its end (false); null = not asked yet.
     */
    var alongTheWay by remember { mutableStateOf<Boolean?>(null) }
    /** A new trip set to start elsewhere in "Where to?", before the destination; null = from here. */
    var planStart by remember { mutableStateOf<Pair<GeoPoint, String>?>(null) }
    /** The start search was opened from "Where to?": its pick goes back there. */
    var startFirst by remember { mutableStateOf(false) }
    /** The search reopens growing out of the pick bar. */
    var searchGrowIn by remember { mutableStateOf(false) }
    /** Editing on the map: the next drag along the line marks the part of the route to change. */
    var marking by remember { mutableStateOf(false) }
    // The route preview folded down to a bar so more of the route shows; open again for each new plan.
    var previewMinimized by remember { mutableStateOf(false) }
    LaunchedEffect(preview == null) { if (preview == null) previewMinimized = false }
    /** Stop menu opened from its handle while editing (index into the trip's stops). */
    var editStopMenu by remember { mutableStateOf<Int?>(null) }
    /** The start or destination held on the map: its menu (remove it). */
    var endMenu by remember { mutableStateOf<HandleKind?>(null) }
    /** Where the next stop from the search goes; null = wherever it adds least detour. */
    var stopTarget by remember { mutableStateOf<StopTarget?>(null) }
    var showRouteMenu by remember { mutableStateOf(false) }
    /** How high the next turn (and media) card at the map's bottom reaches: the rider is kept clear of it. */
    /** Suggested name while the save dialog is open. */
    var saveName by remember { mutableStateOf<String?>(null) }
    val askSaveName: () -> Unit = { scope.launch { saveName = viewModel.suggestedRouteName() } }
    /** Where the roundtrip starts when set elsewhere in "Where to?"; null = from here. */
    var roundtripStart by remember { mutableStateOf<Pair<GeoPoint, String>?>(null) }
    /** Drawing the roundtrip's area on the map. */
    var sketching by remember { mutableStateOf(false) }
    /** About how long the loop round the area drawn so far gets (its outline); null before drawing. */
    var sketchKm by remember { mutableStateOf<Int?>(null) }
    /** Chosen in the drawing bar: how long the loop may be (null = round the whole area), and its surface. */
    var sketchMaxKm by remember { mutableStateOf<Int?>(null) }
    var sketchSurface by remember { mutableStateOf(viewModel.lastSurface()) }
    var sketchGravelReach by remember { mutableStateOf(viewModel.lastGravelReach()) }
    LaunchedEffect(sketching) { if (sketching) sketchKm = null }
    var showGpx by remember { mutableStateOf(false) }
    // Saveable: changing the language restarts the screen, and the rider should stay in Settings.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    /** Settings opened from the engine panel: start on its page. */
    var settingsOnEngine by remember { mutableStateOf(false) }
    LaunchedEffect(showSettings) { if (!showSettings) settingsOnEngine = false }
    var tracksTab by rememberSaveable { mutableStateOf(TracksTab.GPX) }
    // The welcome tour: once on the first start (when the screen has settled), and from Settings → Help.
    val tourTargets = remember { TourTargets() }
    var showTour by rememberSaveable { mutableStateOf(false) }
    // The very first one starts with the choice of language.
    var tourIsFirst by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!viewModel.tourSeen) { delay(900); tourIsFirst = true; showTour = true } }
    /** The phone app's open tab; null when closed. */
    var phoneTab by remember { mutableStateOf<PhoneTab?>(null) }
    /** A place picked while riding or planning: a stop, the destination, or (planning) the start? */
    var pendingPlace by remember { mutableStateOf<Pair<GeoPoint, String>?>(null) }
    /** The road at the place held on the map (name, surface, speed limit), once looked up. */
    var roadInfo by remember { mutableStateOf<com.curveon.data.routing.RoadInfo?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2500); toast = null } }
    LaunchedEffect(phone) { phone.phoneErrors.collect { toast = it } }
    // Fresh call log each time the phone opens (the contacts come along).
    LaunchedEffect(phoneTab != null) { if (phoneTab != null) phone.refreshPhoneBook() }

    // Picking a place: while riding or planning a trip, ask what it is; otherwise go straight to the preview.
    val planning = preview?.goal is com.curveon.data.routing.NavigationGoal.Destination
    val pickPlace: (GeoPoint, String) -> Unit = { point, name ->
        if (isNavigating || planning) { roadInfo = null; pendingPlace = point to name }
        else viewModel.previewDestination(point, name, planStart?.first, planStart?.second ?: "")
    }

    // The search result (or picked map point) for each purpose.
    val usePlace: (SearchPurpose, GeoPoint, String) -> Unit = { purpose, point, name ->
        when (purpose) {
            SearchPurpose.DESTINATION -> pickPlace(point, name)
            SearchPurpose.STOP -> when (val t = stopTarget) {
                null -> viewModel.addStop(point, name)
                is StopTarget.Before -> viewModel.insertPreviewStop(t.index, point, name)
                is StopTarget.Instead -> viewModel.replacePreviewStop(t.index, point, name)
            }
            SearchPurpose.START -> if (startFirst) {
                // Back to "Where to?" (out of the map if picked there) to choose the destination.
                planStart = point to name
                startFirst = false
                searchGrowIn = mapPick != null
                searchPurpose = SearchPurpose.DESTINATION
            } else viewModel.setPreviewStart(point, name)
        }
    }
    val startMapPick: (SearchPurpose) -> Unit = { purpose ->
        searchGrowIn = false
        searchPurpose = null
        picked = null
        alongTheWay = null
        marking = false
        mapPick = purpose
    }
    val closeSearch: () -> Unit = {
        searchPurpose = null
        searchGrowIn = false
        stopTarget = null
        planStart = null
        startFirst = false
    }
    /**
     * Picking a destination or stops on the map is also where the trip is edited on the map: each tap
     * adds a point, the line and points can be dragged, a part of the route marked, points removed.
     * A stop put before or instead of another one, a stop added while riding, or the start: one place.
     */
    val chaining = !isNavigating && stopTarget == null && (mapPick == SearchPurpose.DESTINATION || mapPick == SearchPurpose.STOP)
    val planPointByPoint: (GeoPoint, String) -> Unit = { p, name ->
        when {
            alongTheWay == true -> viewModel.addStop(p, name)
            // The trip's end was a place it was planned to: it stays a stop.
            alongTheWay == false -> viewModel.planPointByPoint(p, name, keepEndAsStop = true)
            else -> viewModel.planPointByPoint(p, name, planStart?.first, planStart?.second ?: "")
        }
    }
    /** A tap point by point: adding stops to a planned trip, the first one asks where it goes. */
    val chainTap: (GeoPoint) -> Unit = { p ->
        if (mapPick == SearchPurpose.STOP && planning && alongTheWay == null) {
            picked = p to null
            scope.launch { val name = viewModel.describePoint(p); if (picked?.first == p) picked = p to name }
        } else {
            picked = null
            scope.launch { planPointByPoint(p, viewModel.describePoint(p)) }
        }
    }
    val endMapPick: () -> Unit = {
        mapPick = null
        picked = null
        alongTheWay = null
        marking = false
        stopTarget = null
        startFirst = false
        if (searchPurpose == null) planStart = null // not on the way back to "Where to?"
    }
    // Picking on (and editing the trip on) the map, or the search back up from the pick bar: the map
    // goes full-screen, and the dashboard comes back afterwards if that's where the rider was.
    val mapMode = mapPick != null || searchGrowIn || sketching
    var dashboardBefore by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(mapMode) {
        if (mapMode) {
            if (dashboardBefore == null) dashboardBefore = !isFullScreenMap
            viewModel.setFullScreenMap(true)
        } else dashboardBefore?.let { if (it) viewModel.setFullScreenMap(false); dashboardBefore = null }
    }
    val planGoal = preview?.goal as? com.curveon.data.routing.NavigationGoal.Destination
    LaunchedEffect(planGoal == null) { if (planGoal == null) editStopMenu = null }
    /** Edit the planned trip on the map: the same as picking stops there. */
    val editOnMap: () -> Unit = { startMapPick(SearchPurpose.STOP) }
    androidx.activity.compose.BackHandler(enabled = mapPick != null) { endMapPick() }
    androidx.activity.compose.BackHandler(enabled = sketching) { sketching = false }

    // Editing on the map: the trip's places as handles, and the last route found, kept on screen
    // (with the dashed guess) while the next one is worked out.
    val selectedLine = remember(preview?.selectedRoute) { preview?.selectedRoute?.points?.map { GeoPoint(it.lat, it.lon) } }
    var editLine by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    LaunchedEffect(selectedLine) { if (selectedLine != null) editLine = selectedLine }
    val selectedCandidate = preview?.candidates?.getOrNull(preview!!.selected)
    // Point by point shows the trip's points the same way, so they can be dragged while building it
    // (and the map stays where the rider is tapping instead of fitting the whole trip each time).
    val editor = if (!chaining || planGoal == null) null else remember(planGoal, selectedCandidate, marking) {
        var number = 0
        val handles = buildList {
            planGoal.start?.let { add(RouteHandle(GeoPoint(it.point.lat, it.point.lon), HandleKind.START)) }
            planGoal.stops.forEachIndexed { i, st ->
                add(RouteHandle(GeoPoint(st.point.lat, st.point.lon), if (st.via) HandleKind.VIA else HandleKind.STOP, i, if (st.via) "" else "${++number}"))
            }
            add(RouteHandle(GeoPoint(planGoal.destination.lat, planGoal.destination.lon), HandleKind.DESTINATION))
        }
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
            // A tap or a long press on a point: its menu (move, remove, …).
            onTapHandle = { h ->
                when (h.kind) {
                    HandleKind.STOP, HandleKind.VIA -> editStopMenu = h.stopIndex
                    else -> endMenu = h.kind
                }
            },
            marking = marking,
            onLongPressHandle = { h ->
                when (h.kind) {
                    HandleKind.STOP, HandleKind.VIA -> editStopMenu = h.stopIndex
                    else -> endMenu = h.kind
                }
            },
            onTapLine = if (chaining) chainTap else null,
            onMarkSection = { fromIndex, from, toIndex, to ->
                marking = false
                scope.launch {
                    viewModel.pinPreviewSection(fromIndex, from, viewModel.describePoint(from), toIndex, to, viewModel.describePoint(to))
                }
            }
        )
    }

    // Where the cards sit over the map: the trip bar top left, the speed top right, the next turn
    // bottom left and the music (or a call) bottom right, one to a corner. The map's buttons keep to
    // the right edge, between the speed and the music, and nothing covers them.
    val density = LocalDensity.current
    var mapBox by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var speedSize by remember { mutableStateOf(androidx.compose.ui.unit.DpSize.Zero) }
    val mapWidth = mapBox?.width?.let { with(density) { it.toDp() } } ?: 0.dp
    val mapHeight = mapBox?.height?.let { with(density) { it.toDp() } } ?: 0.dp
    // Planning, picking and drawing have the bottom (and the bar's middle); the trip bar and the turn wait.
    val showTripBar = preview == null && !sketching && mapPick == null && !searchGrowIn
    val turn = currentTurnInstruction
    val showTurn = turn != null && showTripBar
    // A ringing call is a card in the bottom right corner; once answered it is a floating icon.
    val ringingCall = isFullScreenMap && ringing != null && !callOngoing
    val showMedia = isFullScreenMap && ringing == null && preview == null && mapPick == null && !sketching && track != null
    // The music beside the turn card when there is room, else above it.
    val sideBySide = mapWidth - 14.dp - 8.dp - SIDE_COLUMN_WIDTH - MAP_BUTTONS_RESERVE >= BOTTOM_TURN_MIN_WIDTH
    val bottomRightHeight = BOTTOM_CARD_HEIGHT + if (showTurn && !sideBySide) BOTTOM_CARD_HEIGHT + 8.dp else 0.dp
    val buttonsTopInset = 14.dp + speedSize.height + 8.dp
    // The music keeps the corner if the buttons still fit above it; otherwise it moves left of them.
    val buttonsFitAboveMedia = mapHeight - buttonsTopInset - (bottomRightHeight + 14.dp + 8.dp) >= MAP_BUTTONS_HEIGHT
    val buttonsBottomInset = if ((showMedia || ringingCall) && buttonsFitAboveMedia) bottomRightHeight + 14.dp + 8.dp else 0.dp
    val rightCardEnd = if ((showMedia || ringingCall) && !buttonsFitAboveMedia) MAP_BUTTONS_RESERVE else 14.dp
    val turnWidth = (if (sideBySide && (showMedia || ringingCall)) minOf(BOTTOM_TURN_WIDTH, mapWidth - 14.dp - 8.dp - SIDE_COLUMN_WIDTH - MAP_BUTTONS_RESERVE)
        else minOf(BOTTOM_TURN_WIDTH, mapWidth - 14.dp - MAP_BUTTONS_RESERVE)).coerceAtLeast(0.dp)
    /** How far up from the bottom the cards reach: the rider is lifted to keep clear of them. */
    val bottomClearance = maxOf(if (showTurn) BOTTOM_CARD_HEIGHT + 14.dp else 0.dp, if (showMedia || ringingCall) bottomRightHeight + 14.dp else 0.dp)
    /** What the status chips (and a wrong turn) must stay above on the left. */
    val leftClearance = if (showTurn) BOTTOM_CARD_HEIGHT + 14.dp else 0.dp

    val mapOverlays: @Composable BoxScope.() -> Unit = {
        // Where the map is, so the corners know how much room there is.
        Box(Modifier.matchParentSize().onGloballyPositioned { mapBox = it.boundsInRoot() })
        // The trip bar top left (folding out downwards), the speed top right.
        if (showTripBar) navigationSummary?.let {
            NavigationSummaryBar(
                it, onStop = { viewModel.stopNavigation() },
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
                    .widthIn(max = (mapWidth - 14.dp - 14.dp - 8.dp - speedSize.width).coerceAtLeast(0.dp)),
                foldedHeight = speedSize.height,
                fuel = fuelHint,
                onFuel = { fuelHint?.let { f -> viewModel.addStop(GeoPoint(f.point.lat, f.point.lon), f.name) } },
                onAddStop = { searchPurpose = SearchPurpose.STOP },
                onOpen = { showRouteMenu = true }
            )
        }
        MapSpeedCluster(telemetry, cameraAlert, Modifier.align(Alignment.TopEnd).padding(14.dp).tourTarget(TourTarget.SPEED)
            .onSizeChanged { speedSize = with(density) { androidx.compose.ui.unit.DpSize(it.width.toDp(), it.height.toDp()) } }, showAltitude = showAltitude)
        // The next turn bottom left, as high as the music beside it.
        if (showTurn) {
            TurnByTurnBanner(turn!!, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp).width(turnWidth).height(BOTTOM_CARD_HEIGHT))
        }
        // Picking a place: just the map and the little bar; the rest waits.
        val pickPurpose = mapPick
        val tripSummary = preview?.selectedRoute?.let { r ->
            val min = (r.durationSeconds / 60).toInt()
            "%d:%02d · ".format(min / 60, min % 60) + stringResource(R.string.distance_km_whole, r.distanceMeters / 1000)
        }
        if (sketching) {
            SketchBar(onCancel = { sketching = false }, km = sketchKm?.let { k -> sketchMaxKm?.let { minOf(it, k) } ?: k }, startName = roundtripStart?.second,
                maxKm = sketchMaxKm, onMaxKm = { sketchMaxKm = it }, surface = sketchSurface, onSurface = { sketchSurface = it },
                gravelReachKm = sketchGravelReach, onGravelReach = { sketchGravelReach = it }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 12.dp, end = 12.dp))
        } else if (pickPurpose != null) {
            MapPickBar(
                purpose = pickPurpose,
                picked = picked?.let { it.second ?: "…" },
                onConfirm = { picked?.let { (p, n) -> if (n != null) { usePlace(pickPurpose, p, n); endMapPick() } } },
                onBackToSearch = { mapPick = null; picked = null; searchGrowIn = true; searchPurpose = pickPurpose },
                onCancel = endMapPick,
                pointByPoint = chaining,
                asking = picked?.takeIf { chaining }?.let { it.second ?: "…" },
                onAlongTheWay = { picked?.let { (p, n) -> alongTheWay = true; picked = null; planPointByPoint(p, n ?: "") } },
                onNewEnd = { picked?.let { (p, n) -> alongTheWay = false; picked = null; planPointByPoint(p, n ?: "") } },
                tripSummary = tripSummary.takeIf { planGoal != null },
                busy = preview?.calculating == true || preview?.fetching != null,
                canUndo = preview?.canUndo == true,
                onUndo = viewModel::undoPreviewEdit,
                error = selectedCandidate?.error.takeIf { planGoal != null },
                marking = marking.takeIf { planGoal != null },
                onMark = { marking = !marking },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
            )
        } else if (!searchGrowIn) { // nor while the search is back up over the map
            Column(
                // In full-screen the bottom right has the media pill, so keep this left, above the turn card.
                modifier = (if (isFullScreenMap) Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp + leftClearance)
                else Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp + leftClearance)),
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
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp)
                )
            }
            preview?.let { p ->
                RoutePreviewSheet(
                    preview = p,
                    onSelect = viewModel::selectCandidate,
                    onSurface = viewModel::setPreviewSurface,
                    onToggleFerries = viewModel::togglePreviewAvoidFerries,
                    onMotorways = viewModel::setPreviewMotorways,
                    onRemoveStop = viewModel::removePreviewStop,
                    onShuffle = viewModel::shuffleRoundtrip,
                    onAddStop = { searchPurpose = SearchPurpose.STOP },
                    onMoveStop = viewModel::movePreviewStop,
                    onEditStart = { searchPurpose = SearchPurpose.START },
                onChangeStop = { i -> stopTarget = StopTarget.Instead(i); searchPurpose = SearchPurpose.STOP },
                onAddStopBefore = { i -> stopTarget = StopTarget.Before(i); searchPurpose = SearchPurpose.STOP },
                onEditOnMap = editOnMap,
                onLoopMax = viewModel::setLoopMax,
                onGravelReach = viewModel::setPreviewGravelReach,
                onRedraw = {
                    // Same start, max and surface as the loop on screen.
                    (preview?.goal as? com.curveon.data.routing.NavigationGoal.Destination)?.loop?.let { loop ->
                        roundtripStart = loop.start?.let { GeoPoint(it.point.lat, it.point.lon) to it.name }
                        sketchMaxKm = loop.maxKm
                        sketchSurface = loop.options.surface
                        sketchGravelReach = loop.options.gravelReachKm
                    }
                    sketching = true
                },
                    onReverse = viewModel::reversePreviewTrack,
                    onSave = askSaveName,
                    onGo = viewModel::startPreview,
                    onClose = viewModel::cancelPreview,
                    minimized = previewMinimized,
                    onMinimizedChange = { previewMinimized = it },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 640.dp).fillMaxWidth()
                )
            }
        }
        // In full-screen map a ringing call is a slim card in the bottom right corner (an answered one
        // floats, see [callBubble]); otherwise keep music reachable, like Android Auto's media pill –
        // except while a route preview needs the bottom (its Go button).
        if (isFullScreenMap) {
            if (ringingCall) {
                CallCard(ringing!!, onAction = { i -> phone.invokeAction(ringing.id, i) }, onDismiss = { phone.hideNotification(ringing.id) },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(start = 14.dp, end = rightCardEnd, bottom = 14.dp + if (showTurn && !sideBySide) BOTTOM_CARD_HEIGHT + 8.dp else 0.dp)
                        .width(minOf(SIDE_COLUMN_WIDTH, (mapWidth - 14.dp - rightCardEnd).coerceAtLeast(0.dp))))
            } else if (showMedia) track?.let { t ->
                MiniMediaCard(t, albumArt, { phone.togglePlayPause() }, { phone.skipNext() },
                    Modifier.align(Alignment.BottomEnd)
                        .padding(start = 14.dp, end = rightCardEnd, bottom = 14.dp + if (showTurn && !sideBySide) BOTTOM_CARD_HEIGHT + 8.dp else 0.dp)
                        .width(minOf(SIDE_COLUMN_WIDTH, (mapWidth - 14.dp - rightCardEnd).coerceAtLeast(0.dp))).height(BOTTOM_CARD_HEIGHT))
            }
        }
    }

    // The map, as big as the layout allows.
    val mapArea: @Composable (Modifier) -> Unit = { mapModifier -> Box(mapModifier.tourTarget(TourTarget.MAP)) {
        CurveonMapView(
            location = currentLocation,
            riderFix = riderFix,
            headingDeg = telemetry.headingDeg,
            speedKmh = telemetry.speedKmh,
            speedLimitKmh = telemetry.speedLimitKmh,
            hasGpsFix = telemetry.hasGpsFix,
            routePoints = routePoints,
            groundPreference = groundPreference,
            currentTurnInstruction = currentTurnInstruction,
            isFullScreen = isFullScreenMap,
            bottomClearance = bottomClearance,
            buttonsTopInset = buttonsTopInset,
            buttonsBottomInset = buttonsBottomInset,
            dark = mapDark,
            previewRoutes = if (editor != null) listOf(MapRoute(editLine, true)).filter { it.points.size >= 2 }
            else preview?.candidates?.mapIndexedNotNull { i, c ->
                c.route?.let { r ->
                    val selected = i == preview!!.selected
                    MapRoute(r.points.map { GeoPoint(it.lat, it.lon) }, selected,
                        surfaces = if (selected) r.points.map { p -> if (p.highway == "track") SURFACE_TRACK else if (p.unpaved) SURFACE_GRAVEL else SURFACE_PAVED } else null)
                }
            }.orEmpty(),
            markers = if (editor != null) emptyList() else markers,
            editor = editor,
            // The route starts on the nearest road the map joins up: show the way to it.
            approachTo = if (preview != null) preview?.selectedRoute?.points?.firstOrNull()?.takeIf { planGoal?.start == null }?.let { GeoPoint(it.lat, it.lon) }
                else navState.approach?.let { GeoPoint(it.lat, it.lon) },
            onEditRoute = if (planGoal != null && mapPick == null) editOnMap else null,
            previewSheetMinimized = previewMinimized,
            surfaceWays = viewModel::surfaceWays,
            showSurfaces = showSurfaces,
            roadNameProbe = roadNameProbe,
            onRoadName = viewModel::onRoadName,
            // Held on the map: go there or keep it as a favourite (riding or planning: also a stop or the start).
            onLongPress = { p, roadName ->
                roadInfo = null
                scope.launch { pendingPlace = p to viewModel.describePoint(p) }
                scope.launch { val r = viewModel.roadAt(p, roadName); if (pendingPlace?.first == p || pendingPlace == null) roadInfo = r }
            },
            onTap = if (mapPick != null) ({ p ->
                if (chaining) chainTap(p) else {
                    picked = p to null
                    scope.launch { val name = viewModel.describePoint(p); if (picked?.first == p) picked = p to name }
                }
            }) else null,
            pickedPoint = picked?.first,
            sketch = when (val g = preview?.goal) {
                is com.curveon.data.routing.NavigationGoal.Roundtrip -> g.area
                is com.curveon.data.routing.NavigationGoal.Destination -> g.loop?.area
                else -> null
            }?.map { GeoPoint(it.lat, it.lon) }.orEmpty(),
            onSketchProgress = { stroke ->
                sketchKm = if (stroke.size < 3) null
                else ((stroke + stroke.first()).zipWithNext { a, b -> com.curveon.routing.Geo.distance(a.latitude, a.longitude, b.latitude, b.longitude) }.sum() / 1000).toInt()
            },
            onSketch = if (sketching) ({ stroke ->
                sketching = false
                viewModel.previewAreaRoundtrip(stroke, roundtripStart?.first, roundtripStart?.second ?: "", sketchMaxKm, sketchSurface, sketchGravelReach)
            }) else null,
            gpxTracks = overlayTracks,
            gpxPois = overlayPois,
            trackHint = trackHint.takeIf { preview == null && !isNavigating },
            onPoiClick = { shownPoi = it },
            onViewport = viewModel::setViewport,
            autoZoom = mapAutoZoom,
            mapVersion = mapVersion,
            overlays = mapOverlays
        )
        toast?.let {
            Text(it, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(RoundedCornerShape(12.dp))
                    .background(CockpitSurface).border(1.dp, ElectricCyan, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp))
        }
    } }
    // Messages or music (one of them gets the free height), and engine data.
    val cards: @Composable ColumnScope.() -> Unit = {

        // A call is a slim card on top (a message or music goes on below it).
        ringing?.let { call ->
            CallCard(call, onAction = { i -> phone.invokeAction(call.id, i) }, onDismiss = { phone.hideNotification(call.id) })
        }
        val messages = notifications.filterNot { it.isIncomingCall }
        val attention = voiceReply != null || messages.isNotEmpty()
        when {
            voiceReply != null -> VoiceReplyCard(
                reply = voiceReply!!,
                onSend = { phone.confirmVoiceReply() },
                onRetry = { phone.startVoiceReply(voiceReply!!.notificationKey) },
                onCancel = { phone.cancelVoiceReply() },
                modifier = Modifier.weight(1f)
            )
            messages.isNotEmpty() -> NotificationCard(
                notifications = messages,
                onReply = { phone.startVoiceReply(it.id) },
                onDismiss = { phone.dismissNotification(it.id) },
                onSwipe = { phone.hideNotification(it.id) },
                onAction = { n, i -> phone.invokeAction(n.id, i) },
                modifier = Modifier.weight(1f)
            )
        }

        // A message shares the column with a one-row player.
        if (attention) {
            if (ringing == null) track?.let { MiniMediaCard(it, albumArt, { phone.togglePlayPause() }, { phone.skipNext() }) }
        } else {
            FullMediaCard(
                track = track, art = albumArt, phoneState = phoneState, phoneName = phoneName, apps = mediaApps,
                onPlayPause = { phone.togglePlayPause() }, onNext = { phone.skipNext() }, onPrevious = { phone.skipPrevious() },
                onPlayApp = phone::playApp, onReconnect = phone::retryNow,
                modifier = Modifier.weight(1f)
            )
        }
    }
    // The engine panel (Settings → Engine), where the speed used to be.
    val showEngine = telemetry.isObdEnabled && obdValues.isNotEmpty()
    val enginePanel: @Composable (Modifier) -> Unit = { m ->
        EnginePanel(obdState, obdValues, onOpenSettings = { settingsOnEngine = true; showSettings = true }, modifier = m)
    }
    // Portrait (a phone held upright): speed on top, then the map, the cards below it, the rail along the bottom.
    val portrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    // Turning the phone: the view last used in that orientation (not while the map is taken over for picking).
    LaunchedEffect(portrait) { viewModel.setOrientation(portrait, restore = !mapMode) }
    // The rail's screens: the open one is lit; pressing it again closes it (like its close button),
    // pressing another one swaps it in. A pick on the map or a drawing in progress ends first.
    val activePanel = when {
        searchPurpose != null -> RailPanel.SEARCH
        showGpx -> RailPanel.GPX
        phoneTab != null -> RailPanel.PHONE
        showSettings -> RailPanel.SETTINGS
        else -> null
    }
    val pressRail: (RailPanel) -> Unit = { panel ->
        if (mapPick != null) endMapPick()
        sketching = false
        closeSearch()
        showGpx = false
        phoneTab = null
        showSettings = false
        if (panel != activePanel) when (panel) {
            RailPanel.SEARCH -> searchPurpose = SearchPurpose.DESTINATION
            RailPanel.GPX -> showGpx = true
            RailPanel.PHONE -> phoneTab = PhoneTab.FAVORITES
            RailPanel.SETTINGS -> showSettings = true
        }
    }
    val rail: @Composable () -> Unit = {
        Rail(
            vertical = !portrait,
            isFullScreenMap = isFullScreenMap,
            unread = notifications.size,
            active = activePanel,
            onPress = pressRail,
            onToggleMap = { viewModel.toggleFullScreenMap() }
        )
    }

    // The rail's screens (Where to?, GPX, Phone, Settings) are laid out over the whole map area, not
    // in windows of their own, so the rail stays in sight: pressing the open one closes it, another
    // one swaps it in.
    val panels: @Composable () -> Unit = {
      CompositionLocalProvider(LocalToast provides toast) {
        phoneTab?.let { tab ->
            val contacts by phone.contacts.collectAsStateWithLifecycle()
            val recents by phone.recentCalls.collectAsStateWithLifecycle()
            val photos by phone.photos.collectAsStateWithLifecycle()
            val contactsAllowed by phone.contactsAllowed.collectAsStateWithLifecycle()
            val callLogAllowed by phone.callLogAllowed.collectAsStateWithLifecycle()
            PhoneDialog(
                state = PhonePanelState(
                    contacts, recents, photos, contactsAllowed, callLogAllowed,
                    connected = phoneState == CompanionConnectionState.CONNECTED || phoneState == CompanionConnectionState.DEMO,
                    offlineText = when {
                        phoneName.isEmpty() -> stringResource(R.string.phone_none_linked)
                        phoneState == CompanionConnectionState.CONNECTING -> stringResource(R.string.phone_connecting_to, phoneName)
                        else -> stringResource(R.string.phone_not_connected_named, phoneName)
                    }
                ),
                tab = tab,
                onTab = { phoneTab = it },
                onCall = { number, name ->
                    if (phone.placeCall(number)) { toast = context.getString(R.string.toast_calling, name); phoneTab = null }
                    else toast = context.getString(R.string.toast_phone_not_connected)
                },
                onNeedPhoto = phone::requestPhoto,
                onReconnect = phone::retryNow,
                onClose = { phoneTab = null }
            )
        }

        searchPurpose?.let { purpose -> key(purpose) {
            DestinationSearchDialog(
                searchStatus = remember { viewModel.searchStatus() },
                onSearch = { viewModel.searchPlaces(it) },
                onPick = { r ->
                    if (purpose == SearchPurpose.START && r.kind == KIND_HERE) {
                        if (startFirst) { planStart = null; startFirst = false; searchPurpose = SearchPurpose.DESTINATION }
                        else viewModel.setPreviewStart(null, "")
                    } else usePlace(purpose, GeoPoint(r.lat, r.lon), r.name)
                    if (searchPurpose == purpose) closeSearch()
                },
                // Leaving the start search opened from "Where to?" goes back there.
                onDismiss = { if (startFirst) { startFirst = false; searchPurpose = SearchPurpose.DESTINATION } else closeSearch() },
                savedRoutes = savedRoutes,
                recents = remember(purpose) { viewModel.recents() },
                favorites = favorites,
                onDeleteFavorite = viewModel::deleteFavorite,
                onPickSaved = { closeSearch(); viewModel.previewSaved(it) },
                onDeleteSaved = { viewModel.deleteSavedRoute(it) },
                onNearest = { viewModel.nearest(it) },
                purpose = purpose,
                onPickOnMap = { startMapPick(purpose) },
                growIn = searchGrowIn,
                startName = planStart?.second,
                onChangeStart = if (purpose == SearchPurpose.DESTINATION && !isNavigating && !planning) {
                    { startFirst = true; searchGrowIn = false; searchPurpose = SearchPurpose.START }
                } else null,
                // A loop only as a new trip: not while one is planned or ridden.
                onRoundtrip = if (purpose == SearchPurpose.DESTINATION && !isNavigating && preview == null) {
                    { roundtripStart = planStart; closeSearch(); sketching = true }
                } else null
            )
        } }

        if (showGpx) HostDialog(onDismiss = { showGpx = false }) {
          val gpxPage: @Composable (TracksTab) -> Unit = { which ->
            val tabs = @Composable {
                DialogTabs(TracksTab.entries, which, { stringResource(it.label) }, { it.icon }, { tracksTab = it }, width = 170.dp)
            }
            if (which == TracksTab.RIDES) RideLogDialog(
                current = currentRide,
                rides = rides,
                onRideAgain = { showGpx = false; viewModel.rideAgain(it) },
                gpxGroups = gpxGroups,
                onSaveToGpx = { r, groupId, newGroup -> viewModel.saveRideToGpx(r, groupId, newGroup); toast = context.getString(R.string.toast_saved_to_gpx) },
                autoSaveGroup = autoSaveRidesGroup,
                onAutoSave = { id, name -> viewModel.setAutoSaveRides(id, name) },
                onDelete = { viewModel.deleteRides(it) },
                onDismiss = { showGpx = false },
                sideBar = tabs
            ) else GpxManagerDialog(
                tracks = savedGpxTracks,
                groups = gpxGroups,
                message = gpxMessage,
                poiCategories = poiCategories,
                autoFollow = autoFollow,
                actions = remember(viewModel) {
                    GpxActions(
                        onImport = { importGpxLauncher.launch(arrayOf("*/*")) },
                        onToggleOnMap = { t, on -> viewModel.setTrackOnMap(t, on) },
                        onColor = { t, c -> viewModel.setTrackColor(t, c) },
                        onReverse = { t, r -> viewModel.setTrackReversed(t, r) },
                        onTogglePois = { t, on -> viewModel.setTrackPois(t, on) },
                        onRide = { t -> showGpx = false; viewModel.previewGpx(t, reverse = false, ground = GroundPreference.MIXED) },
                        onDelete = { viewModel.deleteGpx(it) },
                        onRenameTrack = { t, name -> viewModel.renameTrack(t, name) },
                        onMove = { t, id, name -> viewModel.moveTrack(t, id, name) },
                        onGroupVisibility = { viewModel.toggleGroupVisibility(it) },
                        onGroupDirection = { viewModel.toggleGroupDirection(it) },
                        onGroupColor = { g, c -> viewModel.setGroupColor(g, c) },
                        onRenameGroup = { g, name -> viewModel.renameGroup(g, name) },
                        onDeleteGroup = { g, keep -> viewModel.deleteGroup(g, keep) },
                        onExport = { g, t, uri -> viewModel.exportGpx(g, t, uri) }
                    )
                },
                onTogglePoiCategory = viewModel::togglePoiCategory,
                onAutoFollow = viewModel::setAutoFollow,
                onDismiss = { showGpx = false },
                sideBar = tabs
            )
          }
          // Upright on a phone the two pages can be swiped between, tabs and pages following each other.
          if (isNarrowScreen()) {
            val pager = rememberPagerState(initialPage = tracksTab.ordinal) { TracksTab.entries.size }
            LaunchedEffect(tracksTab) { if (pager.currentPage != tracksTab.ordinal) pager.animateScrollToPage(tracksTab.ordinal) }
            LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { tracksTab = TracksTab.entries[it] } }
            HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 8.dp) { gpxPage(TracksTab.entries[it]) }
          } else gpxPage(tracksTab)
        }

        if (showSettings) {
            val installedMaps by viewModel.installedMaps.collectAsStateWithLifecycle()
            val mapDownload by viewModel.mapDownload.collectAsStateWithLifecycle()
            val mapQueue by viewModel.mapQueue.collectAsStateWithLifecycle()
            var showMapCatalog by remember { mutableStateOf(false) }
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val autoZoom by viewModel.autoZoom.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { viewModel.refreshMapStatus() }
            SettingsDialog(
                state = SettingsState(
                    language = remember { com.curveon.AppLanguage.current(context) },
                    useMph = telemetry.useMph, mapTheme = mapTheme, screenLock = viewModel.screenLock.collectAsStateWithLifecycle().value,
                    obdEnabled = telemetry.isObdEnabled,
                    obd = obdState, obdDevice = viewModel.obdDevice.collectAsStateWithLifecycle().value, obdValues = obdValues,
                    pairedDevices = viewModel.obd::pairedDevices,
                    autoZoom = autoZoom, showSurfaces = showSurfaces, showAltitude = showAltitude, autoFollowTracks = autoFollow,
                    offRoute = viewModel.offRouteSettings.collectAsStateWithLifecycle().value,
                    isNavigating = navigationSummary != null, demoMode = demoMode,
                    maps = installedMaps, mapDownload = mapDownload, mapQueue = mapQueue,
                    mapLayers = mapLayers,
                    mapName = { id -> viewModel.mapCatalog.byId[id]?.let { if (it.parent.isEmpty()) it.displayName(locale) else it.name } ?: id },
                    routingDataStatus = routingDataStatus, searchStatus = remember { viewModel.searchStatus() },
                    speedLimitsStatus = remember { viewModel.speedLimitsStatus() }
                ),
                actions = SettingsActions(
                    onLanguage = { com.curveon.AppLanguage.set(context, it) },
                    onUnits = viewModel::setUseMph,
                    onMapTheme = viewModel::setMapTheme,
                    onScreenLock = viewModel::setScreenLock,
                    onObd = viewModel::setObd,
                    onObdDevice = viewModel::setObdDevice,
                    onObdValue = viewModel::toggleObdValue,
                    onAutoZoom = viewModel::setAutoZoom,
                    onShowSurfaces = viewModel::setShowSurfaces,
                    onShowAltitude = viewModel::setShowAltitude,
                    onAutoFollow = viewModel::setAutoFollow,
                    onOffRoute = viewModel::setOffRouteSettings,
                    onDemoMode = viewModel::setDemoMode,
                    onDownloadMap = viewModel::downloadMap,
                    onCancelMapDownload = viewModel::cancelMapDownload,
                    onDeleteMap = viewModel::deleteMap,
                    onImportMap = { importMapLauncher.launch(arrayOf("*/*")) },
                    onBrowseMaps = { showMapCatalog = true },
                    onTour = { showSettings = false; tourIsFirst = false; showTour = true },
                    onMapLayer = viewModel::setMapLayer
                ),
                phone = phone,
                openEngine = settingsOnEngine,
                onDismiss = { showSettings = false }
            )
            if (showMapCatalog) {
                MapCatalogDialog(
                    catalog = viewModel.mapCatalog, installed = installedMaps, download = mapDownload, queue = mapQueue,
                    freeBytes = remember(installedMaps, mapDownload is com.curveon.data.map.MapDownloadState.Running) { viewModel.freeStorageBytes() },
                    downloadBytes = remember(installedMaps) { viewModel.mapDownloadSizer() },
                    startContinent = remember { viewModel.currentContinent() },
                    onDownload = viewModel::downloadMap, onCancel = viewModel::cancelMapDownload,
                    onDismiss = { showMapCatalog = false }
                )
            }
        }
      }
    }
    val panelLayer: @Composable BoxScope.() -> Unit = {
        val open = searchPurpose != null || showGpx || phoneTab != null || showSettings
        // The cover takes every touch, so nothing reaches the map underneath.
        if (open) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } }) {
            Box(Modifier.fillMaxSize().imePadding().padding(8.dp)) {
                CompositionLocalProvider(LocalInPanel provides true) { panels() }
            }
        }
    }

    // An answered call on the full map floats over everything (even an open screen), so it can be ended.
    val callBubble: @Composable BoxScope.() -> Unit = {
        if (isFullScreenMap && callOngoing) FloatingCallBubble(
            ringing!!, onAction = { i -> phone.invokeAction(ringing.id, i) }, onDismiss = { phone.hideNotification(ringing.id) },
            modifier = Modifier.matchParentSize().padding(8.dp)
        )
    }

    val rootModifier = Modifier
        .fillMaxSize()
        .background(CockpitBackground)
        .statusBarsPadding()
        .navigationBarsPadding()
        // A phone's camera hole sits on one edge: keep the rail and cards clear of it, whichever
        // way round the phone is held.
        .windowInsetsPadding(WindowInsets.displayCutout)
        .testTag("dashboard_screen_root")
    CompositionLocalProvider(LocalTourTargets provides tourTargets) { Box(modifier.fillMaxSize()) {
    if (portrait) {
        Column(rootModifier) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The map from the top (the speed is on it); music and the engine panel share the bottom.
                mapArea(Modifier.weight(1f).fillMaxWidth())
                // Planning a trip needs the map: the preview sheet covers much of it.
                if (!isFullScreenMap && preview == null) Row(
                    Modifier.fillMaxWidth().fillMaxHeight(PORTRAIT_CARDS_SHARE).tourTarget(TourTarget.CARDS),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), content = cards)
                    if (showEngine) enginePanel(Modifier.weight(1f).fillMaxHeight())
                }
            }
            panelLayer()
            callBubble()
            }
            rail()
        }
    } else {
        Row(rootModifier) {
            rail()
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mapArea(Modifier.weight(1f).fillMaxHeight())
                    if (!isFullScreenMap) Column(
                        Modifier.width(SIDE_COLUMN_WIDTH).fillMaxHeight().tourTarget(TourTarget.CARDS),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (showEngine) enginePanel(Modifier)
                        cards()
                    }
                }
                panelLayer()
                callBubble()
            }
        }
    }
    if (showTour) WelcomeTour(tourTargets, firstStart = tourIsFirst, onFinish = { showTour = false; tourIsFirst = false; viewModel.markTourSeen() })
    } }

    // ------------------------------------------------------------------ dialogs

    CompositionLocalProvider(LocalToast provides toast) {


        val favoriteSaved = stringResource(R.string.favorite_saved)
        pendingPlace?.let { (point, name) ->
            PlaceActionDialog(
                name = name,
                road = roadInfo,
                onAddStop = if (isNavigating || planning) ({ viewModel.addStop(point, name); pendingPlace = null }) else null,
                onGoHere = {
                    if (planning) viewModel.setPreviewDestination(point, name) else viewModel.previewDestination(point, name)
                    pendingPlace = null
                },
                onStartHere = if (planning) ({ viewModel.setPreviewStart(point, name); pendingPlace = null }) else null,
                onFavorite = { n, home ->
                    viewModel.saveFavorite(point, n, home)
                    pendingPlace = null
                    toast = favoriteSaved
                },
                onDismiss = { pendingPlace = null }
            )
        }

        editStopMenu?.let { i ->
            val stops = planGoal?.stops.orEmpty()
            val st = stops.getOrNull(i)
            if (st == null) editStopMenu = null
            else StopMenuDialog(
                name = st.name,
                canMoveEarlier = !st.via && i > 0, canMoveLater = !st.via && i < stops.lastIndex,
                onMove = { d -> editStopMenu = null; viewModel.movePreviewStop(i, d) },
                onRemove = { editStopMenu = null; viewModel.removePreviewStop(i) },
                removeLabel = stringResource(if (st.via) R.string.remove_point else R.string.preview_remove_stop),
                onDismiss = { editStopMenu = null },
                // From the map: the search takes over (and the point goes in where asked, then back to the trip).
                onChangePlace = { editStopMenu = null; mapPick = null; picked = null; stopTarget = StopTarget.Instead(i); searchPurpose = SearchPurpose.STOP },
                onAddBefore = { editStopMenu = null; mapPick = null; picked = null; stopTarget = StopTarget.Before(i); searchPurpose = SearchPurpose.STOP }
            )
        }
        endMenu?.let { kind ->
            val g = planGoal
            if (g == null) endMenu = null
            else StopMenuDialog(
                name = if (kind == HandleKind.START) g.start?.name.orEmpty() else g.name,
                canMoveEarlier = false, canMoveLater = false, onMove = {},
                onRemove = {
                    endMenu = null
                    if (kind == HandleKind.START) viewModel.setPreviewStart(null, "") else viewModel.removePreviewDestination()
                },
                onDismiss = { endMenu = null },
                removeLabel = stringResource(R.string.remove_point),
                onChangePlace = {
                    endMenu = null; mapPick = null; picked = null
                    searchPurpose = if (kind == HandleKind.START) SearchPurpose.START else SearchPurpose.DESTINATION
                }
            )
        }

        saveName?.let { suggested ->
            val (toRoutes, toGpx, gpxGroup) = viewModel.saveRouteTargets
            SaveRouteDialog(
                initialName = suggested,
                initial = RouteSaveTargets(toRoutes, toGpx, gpxGroup),
                gpxGroups = gpxGroups,
                onSave = { name, t ->
                    if (t.toRoutes) viewModel.saveRoute(name)
                    if (t.toGpx) viewModel.saveRouteToGpx(name, t.gpxGroup, t.newGroup)
                    viewModel.rememberSaveRouteTargets(t.toRoutes, t.toGpx, t.gpxGroup)
                    saveName = null
                    toast = context.getString(when {
                        t.toRoutes && t.toGpx -> R.string.toast_saved_to_both
                        t.toGpx -> R.string.toast_saved_to_gpx
                        else -> R.string.toast_saved_to_where_to
                    })
                },
                onDismiss = { saveName = null }
            )
        }

        if (showRouteMenu && isNavigating) {
            val along = navState.progress?.distanceAlongRoute ?: 0.0
            val realStops = navState.stopsAlong.withIndex().filter { !it.value.first.via }
            RouteMenuDialog(
                title = navState.goal?.title ?: "",
                // Points the route was dragged through aren't stops to the rider.
                stops = realStops.map { (_, sa) -> sa.first.name to ((sa.second - along) / 1000).coerceAtLeast(0.0) },
                onRemoveStop = { i -> showRouteMenu = false; realStops.getOrNull(i)?.let { viewModel.removeStop(it.index) } },
                onAddStop = { showRouteMenu = false; searchPurpose = SearchPurpose.STOP },
                onSave = { showRouteMenu = false; askSaveName() },
                onEnd = { showRouteMenu = false; viewModel.stopNavigation() },
                onDismiss = { showRouteMenu = false }
            )
        }


        shownPoi?.let { poi ->
            PoiDialog(
                poi, from = currentLocation,
                onGo = { shownPoi = null; pickPlace(GeoPoint(poi.lat, poi.lon), poi.displayName(context)) },
                onCall = { number -> if (phone.placeCall(number)) toast = context.getString(R.string.toast_calling, poi.displayName(context)) else toast = context.getString(R.string.toast_connect_phone_to_call) },
                onDismiss = { shownPoi = null }
            )
        }



    }

}

/**
 * ~1/3 of the 7" tablet's 960 dp width. Narrower made cover art and messages too small to read
 * at a glance; the map still gets ~55% of the screen and goes full width with one tap.
 */
private val SIDE_COLUMN_WIDTH = 300.dp
/** The turn card at the bottom left and the media player at the bottom right are this high. */
private val BOTTOM_CARD_HEIGHT = 72.dp
private val BOTTOM_TURN_WIDTH = 320.dp
private val BOTTOM_TURN_MIN_WIDTH = 220.dp
/** The map's button column (CurveonMapView): four 52 dp buttons, 10 dp apart. */
private val MAP_BUTTONS_HEIGHT = 52.dp * 4 + 10.dp * 3
/** From the map's right edge: the buttons, their margin and a gap; cards on the left stay clear of it. */
private val MAP_BUTTONS_RESERVE = 14.dp + 52.dp + 8.dp

/** Upright, the speed and music cards take this much of the height under the map. */
private const val PORTRAIT_CARDS_SHARE = 0.34f

/** Room for the full-map speed and limit sign in the top-right corner (about 66 dp tall). */
private val FULL_MAP_SPEED_CLEARANCE = 80.dp

/** The screens the rail opens over the map. */
private enum class RailPanel { SEARCH, GPX, PHONE, SETTINGS }

@Composable
private fun Rail(
    /** Down the left side (landscape), or along the bottom (portrait). */
    vertical: Boolean,
    isFullScreenMap: Boolean,
    unread: Int,
    /** The screen that is open: its button is lit. */
    active: RailPanel?,
    onPress: (RailPanel) -> Unit,
    onToggleMap: () -> Unit
) {
    // A phone held sideways is lower than the X7 (about 410 dp against 540): smaller buttons, so
    // the map toggle at the bottom still fits.
    val compact = vertical && LocalConfiguration.current.screenHeightDp < 480
    val search: @Composable () -> Unit = {
        RailButton(Icons.Default.TwoWheeler, stringResource(R.string.where_to), ElectricCyan, ElectricCyan, filled = active == RailPanel.SEARCH, tag = "rail_search", target = TourTarget.SEARCH, onClick = { onPress(RailPanel.SEARCH) })
    }
    val gpx: @Composable () -> Unit = {
        RailButton(Icons.Default.Route, stringResource(R.string.gpx_manager), ElectricCyan, ElectricCyan, filled = active == RailPanel.GPX, tag = "rail_gpx", target = TourTarget.GPX, onClick = { onPress(RailPanel.GPX) })
    }
    val phone: @Composable () -> Unit = {
        RailButton(Icons.Default.Phone, stringResource(R.string.phone), ElectricCyan, EcoGreen, filled = active == RailPanel.PHONE,
            tag = "rail_phone", target = TourTarget.PHONE, onClick = { onPress(RailPanel.PHONE) })
    }
    val settings: @Composable () -> Unit = {
        RailButton(Icons.Default.Settings, stringResource(R.string.settings), ElectricCyan, TextPrimary, filled = active == RailPanel.SETTINGS, tag = "rail_settings", target = TourTarget.SETTINGS, onClick = { onPress(RailPanel.SETTINGS) })
    }
    // The map toggle shows a mode, not a screen: it only changes its symbol.
    val mapToggle: @Composable () -> Unit = {
        Box {
            RailButton(
                if (isFullScreenMap) Icons.Default.Dashboard else Icons.Default.Map,
                stringResource(if (isFullScreenMap) R.string.dashboard else R.string.full_map),
                ElectricCyan, ElectricCyan,
                tag = "rail_toggle_map", target = TourTarget.MAP_TOGGLE, onClick = onToggleMap
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
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
    ) {
        search(); gpx(); phone(); settings()
        Spacer(Modifier.weight(1f))
        mapToggle()
    } else Row(
        Modifier.fillMaxWidth().height(68.dp).background(CockpitSurface).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // The landscape rail turned a quarter: its bottom (the map toggle) ends up at the left.
        mapToggle(); settings(); phone(); gpx(); search()
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    description: String,
    accent: Color,
    tint: Color,
    filled: Boolean = false,
    tag: String,
    target: TourTarget,
    onClick: () -> Unit
) {
    // Smaller on a phone: sideways it is low, upright it is narrow.
    val compact = LocalConfiguration.current.let { minOf(it.screenHeightDp, it.screenWidthDp) < 480 }
    Box(
        Modifier
            .size(if (compact) 48.dp else 56.dp)
            .tourTarget(target)
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) accent else CockpitSurfaceVariant)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = if (filled) Color(0xFF00363D) else tint, modifier = Modifier.size(28.dp)) }
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

/** Where a stop chosen in the search goes in the planned trip: before stop [index], or in its place. */
private sealed interface StopTarget {
    val index: Int
    data class Before(override val index: Int) : StopTarget
    data class Instead(override val index: Int) : StopTarget
}

/** Pages of the GPX & rides dialog (rail button). */
private enum class TracksTab(val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GPX(R.string.gpx_tab, Icons.Default.Route),
    RIDES(R.string.rides, Icons.Default.History)
}
