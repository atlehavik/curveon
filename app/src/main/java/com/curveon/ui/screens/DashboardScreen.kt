package com.curveon.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.model.GeoPoint
import com.curveon.data.model.GroundPreference
import com.curveon.data.model.RoundtripConfig
import com.curveon.ui.components.*
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Auto-inspired cockpit: a slim rail on the left, the map, and an adaptive column with
 * speed, then either the newest message (or a spoken reply in progress) plus a mini player, or
 * – when nothing needs attention – the music card with full-bleed cover art.
 */
@Composable
fun DashboardScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val routePoints by viewModel.activeRoutePoints.collectAsStateWithLifecycle()
    val groundPreference by viewModel.groundPreference.collectAsStateWithLifecycle()
    val currentTurnInstruction by viewModel.currentTurnInstruction.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val navigationSummary by viewModel.navigationSummary.collectAsStateWithLifecycle()
    val routingStatus by viewModel.routingStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val phone = viewModel.phoneCompanion
    val track by phone.currentTrack.collectAsStateWithLifecycle()
    val albumArt by phone.albumArt.collectAsStateWithLifecycle()
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    val voiceReply by phone.voiceReply.collectAsStateWithLifecycle()
    val phoneState by phone.connectionState.collectAsStateWithLifecycle()
    val phoneName by phone.connectedDeviceName.collectAsStateWithLifecycle()
    val mediaApps by phone.mediaApps.collectAsStateWithLifecycle()
    val ringing = notifications.firstOrNull { it.isIncomingCall }

    val offlineMapStatus by viewModel.offlineMapStatus.collectAsStateWithLifecycle()
    val routingDataStatus by viewModel.routingDataStatus.collectAsStateWithLifecycle()
    val isDemoRiding by viewModel.isDemoRiding.collectAsStateWithLifecycle()
    val savedGpxTracks by viewModel.savedGpxTracks.collectAsStateWithLifecycle()
    val gpxGroups by viewModel.gpxGroups.collectAsStateWithLifecycle()
    val overlayTracks by viewModel.overlayTracks.collectAsStateWithLifecycle()
    val overlayPois by viewModel.overlayPois.collectAsStateWithLifecycle()
    val trackHint by viewModel.trackHint.collectAsStateWithLifecycle()
    val poiCategories by viewModel.poiCategories.collectAsStateWithLifecycle()
    val autoFollow by viewModel.autoFollowTracks.collectAsStateWithLifecycle()
    val mapAutoZoom by viewModel.autoZoom.collectAsStateWithLifecycle()
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
    /** The search reopens growing out of the pick bar. */
    var searchGrowIn by remember { mutableStateOf(false) }
    /** Editing the previewed trip by dragging it on the map. */
    var editing by remember { mutableStateOf(false) }
    // The route preview folded down to a bar so more of the route shows; open again for each new plan.
    var previewMinimized by remember { mutableStateOf(false) }
    LaunchedEffect(preview == null) { if (preview == null) previewMinimized = false }
    /** Stop menu opened from its handle while editing (index into the trip's stops). */
    var editStopMenu by remember { mutableStateOf<Int?>(null) }
    /** Where the next stop from the search goes; null = wherever it adds least detour. */
    var stopTarget by remember { mutableStateOf<StopTarget?>(null) }
    var showRouteMenu by remember { mutableStateOf(false) }
    /** Suggested name while the save dialog is open. */
    var saveName by remember { mutableStateOf<String?>(null) }
    val askSaveName: () -> Unit = { scope.launch { saveName = viewModel.suggestedRouteName() } }
    var showRoundtrip by remember { mutableStateOf(false) }
    var showGpx by remember { mutableStateOf(false) }
    var lastRoundtrip by remember { mutableStateOf(RoundtripConfig(radiusKm = 100, selectedDirections = setOf(com.curveon.data.model.CompassDirection.NORTH), avoidTolls = false)) }
    // Saveable: changing the language restarts the screen, and the rider should stay in Settings.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var tracksTab by rememberSaveable { mutableStateOf(TracksTab.GPX) }
    /** The phone app's open tab; null when closed. */
    var phoneTab by remember { mutableStateOf<PhoneTab?>(null) }
    /** A place picked while riding or planning: a stop, the destination, or (planning) the start? */
    var pendingPlace by remember { mutableStateOf<Pair<GeoPoint, String>?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2500); toast = null } }
    LaunchedEffect(phone) { phone.phoneErrors.collect { toast = it } }
    // Fresh call log each time the phone opens (the contacts come along).
    LaunchedEffect(phoneTab != null) { if (phoneTab != null) phone.refreshPhoneBook() }

    // Picking a place: while riding or planning a trip, ask what it is; otherwise go straight to the preview.
    val planning = preview?.goal is com.curveon.data.routing.NavigationGoal.Destination
    val pickPlace: (GeoPoint, String) -> Unit = { point, name ->
        if (isNavigating || planning) pendingPlace = point to name else viewModel.previewDestination(point, name)
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
            SearchPurpose.START -> viewModel.setPreviewStart(point, name)
        }
    }
    val startMapPick: (SearchPurpose) -> Unit = { purpose ->
        searchGrowIn = false
        searchPurpose = null
        picked = null
        mapPick = purpose
    }
    val closeSearch: () -> Unit = {
        searchPurpose = null
        searchGrowIn = false
        stopTarget = null
    }
    val endMapPick: () -> Unit = {
        mapPick = null
        picked = null
        stopTarget = null
    }
    // Picking on the map, editing the trip on it, or the search back up from the pick bar: the map
    // goes full-screen, and the dashboard comes back afterwards if that's where the rider was.
    val mapMode = mapPick != null || editing || searchGrowIn
    var dashboardBefore by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(mapMode) {
        if (mapMode) {
            if (dashboardBefore == null) dashboardBefore = !isFullScreenMap
            viewModel.setFullScreenMap(true)
        } else dashboardBefore?.let { if (it) viewModel.setFullScreenMap(false); dashboardBefore = null }
    }
    val planGoal = preview?.goal as? com.curveon.data.routing.NavigationGoal.Destination
    LaunchedEffect(planGoal == null) { if (planGoal == null) { editing = false; editStopMenu = null } }
    androidx.activity.compose.BackHandler(enabled = editing && mapPick == null) { editing = false }
    androidx.activity.compose.BackHandler(enabled = mapPick != null) { endMapPick() }

    // Editing on the map: the trip's places as handles, and the last route found, kept on screen
    // (with the dashed guess) while the next one is worked out.
    val selectedLine = remember(preview?.selectedRoute) { preview?.selectedRoute?.points?.map { GeoPoint(it.lat, it.lon) } }
    var editLine by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    LaunchedEffect(selectedLine) { if (selectedLine != null) editLine = selectedLine }
    val selectedCandidate = preview?.candidates?.getOrNull(preview!!.selected)
    val editor = if (!editing || planGoal == null) null else remember(planGoal, selectedCandidate) {
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
            onTapHandle = { h ->
                when (h.kind) {
                    HandleKind.STOP, HandleKind.VIA -> editStopMenu = h.stopIndex
                    HandleKind.START -> searchPurpose = SearchPurpose.START
                    HandleKind.DESTINATION -> searchPurpose = SearchPurpose.DESTINATION
                }
            }
        )
    }

    val mapOverlays: @Composable BoxScope.() -> Unit = {
        // Picking a place: just the map and the little bar; the rest waits.
        val pickPurpose = mapPick
        if (pickPurpose != null) {
            MapPickBar(
                purpose = pickPurpose,
                picked = picked?.let { it.second ?: "…" },
                onConfirm = { picked?.let { (p, n) -> if (n != null) { usePlace(pickPurpose, p, n); endMapPick() } } },
                onBackToSearch = { mapPick = null; picked = null; searchGrowIn = true; searchPurpose = pickPurpose },
                onCancel = endMapPick,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
            )
        } else if (editing) {
            val route = preview?.selectedRoute
            RouteEditBar(
                summary = route?.let { r ->
                    val min = (r.durationSeconds / 60).toInt()
                    "%d:%02d · ".format(min / 60, min % 60) + stringResource(R.string.distance_km_whole, r.distanceMeters / 1000)
                },
                busy = editor?.busy == true,
                error = selectedCandidate?.error,
                canUndo = preview?.canUndo == true,
                onUndo = viewModel::undoPreviewEdit,
                onDone = { editing = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
            )
        } else if (!searchGrowIn) { // nor while the search is back up over the map
            Column(
                // In full-screen the media pill takes the bottom-right corner, so keep trip info left.
                modifier = if (isFullScreenMap) Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 14.dp)
                else Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                horizontalAlignment = if (isFullScreenMap) Alignment.Start else Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RoutingStatusCard(routingStatus, onDismiss = { viewModel.dismissRoutingError() })
                if (preview == null && !isNavigating) trackHint?.let { h ->
                    TrackChip(h, onClick = { viewModel.followOverlayTrack(h.trackId, h.onTrack && h.reverse) })
                }
                if (preview == null) navigationSummary?.let {
                    NavigationSummaryBar(
                        it, onStop = { viewModel.stopNavigation() },
                        fuel = fuelHint,
                        onFuel = { fuelHint?.let { f -> viewModel.addStop(GeoPoint(f.point.lat, f.point.lon), f.name) } },
                        onAddStop = { searchPurpose = SearchPurpose.STOP },
                        onOpen = { showRouteMenu = true }
                    )
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
                    onToggleMotorways = viewModel::togglePreviewAvoidMotorways,
                    onRemoveStop = viewModel::removePreviewStop,
                    onShuffle = viewModel::shuffleRoundtrip,
                    onAddStop = { searchPurpose = SearchPurpose.STOP },
                    onMoveStop = viewModel::movePreviewStop,
                    onEditStart = { searchPurpose = SearchPurpose.START },
                onChangeStop = { i -> stopTarget = StopTarget.Instead(i); searchPurpose = SearchPurpose.STOP },
                onAddStopBefore = { i -> stopTarget = StopTarget.Before(i); searchPurpose = SearchPurpose.STOP },
                onEditOnMap = { editing = true },
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
        // In full-screen map a ringing call takes the corner; otherwise keep music reachable, like
        // Android Auto's media pill – except while a route preview needs the bottom (its Go button).
        if (isFullScreenMap) {
            if (ringing != null) {
                CallCard(ringing, onAction = { i -> phone.invokeAction(ringing.id, i) }, onDismiss = { phone.hideNotification(ringing.id) },
                    // Never over the speed (top-right): the limit matters during a call too.
                    modifier = Modifier.align(Alignment.BottomEnd).padding(top = FULL_MAP_SPEED_CLEARANCE).padding(14.dp)
                        .width(SIDE_COLUMN_WIDTH).heightIn(max = 360.dp).fillMaxHeight())
            } else if (preview == null && mapPick == null) track?.let { t ->
                MiniMediaCard(t, albumArt, { phone.togglePlayPause() }, { phone.skipNext() },
                    Modifier.align(Alignment.BottomEnd).padding(14.dp).width(300.dp))
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("dashboard_screen_root")
    ) {
        Rail(
            isFullScreenMap = isFullScreenMap,
            phoneState = phoneState,
            unread = notifications.size,
            onSearch = { searchPurpose = SearchPurpose.DESTINATION },
            onRoundtrip = { showRoundtrip = true },
            onGpx = { showGpx = true },
            phoneOpen = phoneTab != null,
            onPhone = { phoneTab = if (phoneTab == null) PhoneTab.FAVORITES else null },
            onSettings = { showSettings = true },
            onToggleMap = { viewModel.toggleFullScreenMap() }
        )

        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CurveonMapView(
                    location = currentLocation,
                    headingDeg = telemetry.headingDeg,
                    speedKmh = telemetry.speedKmh,
                    speedLimitKmh = telemetry.speedLimitKmh,
                    hasGpsFix = telemetry.hasGpsFix,
                    routePoints = routePoints,
                    groundPreference = groundPreference,
                    currentTurnInstruction = currentTurnInstruction,
                    isFullScreen = isFullScreenMap,
                    dark = mapDark,
                    previewRoutes = if (editor != null) listOf(MapRoute(editLine, true)).filter { it.points.size >= 2 }
                    else preview?.candidates?.mapIndexedNotNull { i, c ->
                        c.route?.let { r -> MapRoute(r.points.map { GeoPoint(it.lat, it.lon) }, i == preview!!.selected) }
                    }.orEmpty(),
                    markers = if (editor != null) emptyList() else markers,
                    editor = editor,
                    // The route starts on the nearest road the map joins up: show the way to it.
                    approachTo = if (preview != null) preview?.selectedRoute?.points?.firstOrNull()?.takeIf { planGoal?.start == null }?.let { GeoPoint(it.lat, it.lon) }
                        else navState.approach?.let { GeoPoint(it.lat, it.lon) },
                    onEditRoute = if (planGoal != null && !editing && mapPick == null) ({ editing = true }) else null,
                    previewSheetMinimized = previewMinimized,
                    surfaceWays = viewModel::surfaceWays,
                    roadNameProbe = roadNameProbe,
                    onRoadName = viewModel::onRoadName,
                    onLongPress = { p -> scope.launch { pickPlace(p, viewModel.describePoint(p)) } },
                    onTap = if (mapPick != null) ({ p ->
                        picked = p to null
                        scope.launch { val name = viewModel.describePoint(p); if (picked?.first == p) picked = p to name }
                    }) else null,
                    pickedPoint = picked?.first,
                    gpxTracks = overlayTracks,
                    gpxPois = overlayPois,
                    trackHint = trackHint.takeIf { preview == null && !isNavigating },
                    onPoiClick = { shownPoi = it },
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
            }

            if (!isFullScreenMap) {
                Column(
                    Modifier.width(SIDE_COLUMN_WIDTH).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpeedCard(telemetry, camera = cameraAlert)

                    val attention = voiceReply != null || notifications.isNotEmpty()
                    when {
                        voiceReply != null -> VoiceReplyCard(
                            reply = voiceReply!!,
                            onSend = { phone.confirmVoiceReply() },
                            onRetry = { phone.startVoiceReply(voiceReply!!.notificationKey) },
                            onCancel = { phone.cancelVoiceReply() },
                            modifier = Modifier.weight(1f)
                        )
                        notifications.isNotEmpty() -> NotificationCard(
                            notifications = notifications,
                            onReply = { phone.startVoiceReply(it.id) },
                            onDismiss = { phone.dismissNotification(it.id) },
                            onSwipe = { phone.hideNotification(it.id) },
                            onAction = { n, i -> phone.invokeAction(n.id, i) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // A ringing call gets the whole column; a message shares it with a one-row player.
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

                    if (telemetry.isObdEnabled && telemetry.isObdConnected) Obd2TelemetryCard(telemetry = telemetry)
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

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

        searchPurpose?.let { purpose ->
            DestinationSearchDialog(
                searchStatus = remember { viewModel.searchStatus() },
                onSearch = { viewModel.searchPlaces(it) },
                onPick = { r ->
                    if (purpose == SearchPurpose.START && r.kind == KIND_HERE) viewModel.setPreviewStart(null, "")
                    else usePlace(purpose, GeoPoint(r.lat, r.lon), r.name)
                    closeSearch()
                },
                onDismiss = closeSearch,
                savedRoutes = savedRoutes,
                recents = remember(purpose) { viewModel.recents() },
                onPickSaved = { closeSearch(); viewModel.previewSaved(it) },
                onDeleteSaved = { viewModel.deleteSavedRoute(it) },
                onNearest = { viewModel.nearest(it) },
                purpose = purpose,
                onPickOnMap = { startMapPick(purpose) },
                growIn = searchGrowIn
            )
        }

        pendingPlace?.let { (point, name) ->
            PlaceActionDialog(
                name = name,
                onAddStop = { viewModel.addStop(point, name); pendingPlace = null },
                onGoHere = {
                    if (planning) viewModel.setPreviewDestination(point, name) else viewModel.previewDestination(point, name)
                    pendingPlace = null
                },
                onStartHere = if (planning) ({ viewModel.setPreviewStart(point, name); pendingPlace = null }) else null,
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
                onDismiss = { editStopMenu = null },
                onChangePlace = { editStopMenu = null; stopTarget = StopTarget.Instead(i); searchPurpose = SearchPurpose.STOP },
                onAddBefore = { editStopMenu = null; stopTarget = StopTarget.Before(i); searchPurpose = SearchPurpose.STOP }
            )
        }

        saveName?.let { suggested ->
            SaveRouteDialog(
                initialName = suggested,
                onSave = { name -> viewModel.saveRoute(name); saveName = null; toast = context.getString(R.string.toast_saved_to_where_to) },
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

        if (showRoundtrip) {
            RoundtripDialog(
                initialConfig = lastRoundtrip,
                onDismiss = { showRoundtrip = false },
                onGenerate = { config ->
                    lastRoundtrip = config
                    viewModel.previewRoundtrip(config)
                    showRoundtrip = false
                }
            )
        }

        if (showGpx) HostDialog(onDismiss = { showGpx = false }) {
            val tabs = @Composable {
                DialogTabs(TracksTab.entries, tracksTab, { stringResource(it.label) }, { it.icon }, { tracksTab = it }, width = 170.dp)
            }
            if (tracksTab == TracksTab.RIDES) RideLogDialog(
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

        shownPoi?.let { poi ->
            PoiDialog(
                poi, from = currentLocation,
                onGo = { shownPoi = null; pickPlace(GeoPoint(poi.lat, poi.lon), poi.displayName(context)) },
                onCall = { number -> if (phone.placeCall(number)) toast = context.getString(R.string.toast_calling, poi.displayName(context)) else toast = context.getString(R.string.toast_connect_phone_to_call) },
                onDismiss = { shownPoi = null }
            )
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
                    useMph = telemetry.useMph, mapTheme = mapTheme, obdEnabled = telemetry.isObdEnabled,
                    autoZoom = autoZoom, autoFollowTracks = autoFollow,
                    offRoute = viewModel.offRouteSettings.collectAsStateWithLifecycle().value,
                    isNavigating = navigationSummary != null, isDemoRiding = isDemoRiding,
                    maps = installedMaps, mapDownload = mapDownload, mapQueue = mapQueue,
                    mapName = { id -> viewModel.mapCatalog.byId[id]?.let { if (it.parent.isEmpty()) it.displayName(locale) else it.name } ?: id },
                    routingDataStatus = routingDataStatus, searchStatus = remember { viewModel.searchStatus() }
                ),
                actions = SettingsActions(
                    onLanguage = { com.curveon.AppLanguage.set(context, it) },
                    onUnits = viewModel::setUseMph,
                    onMapTheme = viewModel::setMapTheme,
                    onObd = viewModel::setObd,
                    onAutoZoom = viewModel::setAutoZoom,
                    onAutoFollow = viewModel::setAutoFollow,
                    onOffRoute = viewModel::setOffRouteSettings,
                    onDemoRide = { viewModel.toggleDemoRide(); showSettings = false },
                    onDownloadMap = viewModel::downloadMap,
                    onCancelMapDownload = viewModel::cancelMapDownload,
                    onDeleteMap = viewModel::deleteMap,
                    onImportMap = { importMapLauncher.launch(arrayOf("*/*")) },
                    onBrowseMaps = { showMapCatalog = true }
                ),
                phone = phone,
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

/**
 * ~1/3 of the 7" tablet's 960 dp width. Narrower made cover art and messages too small to read
 * at a glance; the map still gets ~55% of the screen and goes full width with one tap.
 */
private val SIDE_COLUMN_WIDTH = 300.dp

/** Room for the full-map speed and limit sign in the top-right corner (about 66 dp tall). */
private val FULL_MAP_SPEED_CLEARANCE = 80.dp

@Composable
private fun Rail(
    isFullScreenMap: Boolean,
    phoneState: CompanionConnectionState,
    unread: Int,
    onSearch: () -> Unit,
    onRoundtrip: () -> Unit,
    onGpx: () -> Unit,
    phoneOpen: Boolean,
    onPhone: () -> Unit,
    onSettings: () -> Unit,
    onToggleMap: () -> Unit
) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(CockpitSurface).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Clock()
        RailButton(Icons.Default.Search, stringResource(R.string.where_to), ElectricCyan, Color(0xFF00363D), filled = true, tag = "rail_search", onClick = onSearch)
        RailButton(Icons.Default.Explore, stringResource(R.string.roundtrip), NeonAmber, NeonAmber, tag = "rail_roundtrip", onClick = onRoundtrip)
        RailButton(Icons.Default.Route, stringResource(R.string.gpx_manager), ElectricCyan, ElectricCyan, tag = "rail_gpx", onClick = onGpx)
        RailButton(Icons.Default.Phone, stringResource(R.string.phone), EcoGreen, if (phoneOpen) Color.Black else EcoGreen,
            filled = phoneOpen, tag = "rail_phone", onClick = onPhone)
        RailButton(Icons.Default.Settings, stringResource(R.string.settings), TextSecondary, TextPrimary, tag = "rail_settings", onClick = onSettings)
        Spacer(Modifier.weight(1f))
        Box {
            RailButton(
                if (isFullScreenMap) Icons.Default.Dashboard else Icons.Default.Map,
                stringResource(if (isFullScreenMap) R.string.dashboard else R.string.full_map),
                ElectricCyan, if (isFullScreenMap) Color(0xFF00363D) else ElectricCyan,
                filled = isFullScreenMap, tag = "rail_toggle_map", onClick = onToggleMap
            )
            if (isFullScreenMap && unread > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(NeonAmber),
                    contentAlignment = Alignment.Center
                ) { Text(unread.toString(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black) }
            }
        }
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
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) accent else CockpitSurfaceVariant)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    Text(
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
        color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold
    )
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
    RIDES(R.string.rides, Icons.Default.TwoWheeler)
}
