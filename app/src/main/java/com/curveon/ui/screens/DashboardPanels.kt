package com.curveon.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.model.GeoPoint
import com.curveon.data.model.GroundPreference
import com.curveon.ui.components.*
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.DashboardViewModel

/** Ways into Android's file picker, made once for the whole dashboard (a picker's answer comes back to them). */
internal class DashboardImports(val importGpx: () -> Unit, val importMap: () -> Unit)

/**
 * The rail's screens (Where to?, GPX, Phone, Settings) are laid out over the whole map area, not
 * in windows of their own, so the rail stays in sight: pressing the open one closes it, another
 * one swaps it in.
 */
@Composable
internal fun BoxScope.PanelLayer(viewModel: DashboardViewModel, state: DashboardScreenState, imports: DashboardImports) {
    // The cover takes every touch, so nothing reaches the map underneath.
    if (state.panelOpen) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } }) {
        Box(Modifier.fillMaxSize().imePadding().padding(8.dp)) {
            CompositionLocalProvider(LocalInPanel provides true) {
                CompositionLocalProvider(LocalToast provides state.toast) {
                    state.phoneTab?.let { tab -> PhonePanel(viewModel, state, tab) }
                    if (state.showGroup) FriendsDialog(viewModel.phoneCompanion.groups, onDismiss = { state.showGroup = false })
                    state.searchPurpose?.let { purpose -> key(purpose) { SearchPanel(viewModel, state, purpose) } }
                    if (state.showGpx) TracksPanel(viewModel, state, imports)
                    if (state.showSettings) SettingsPanel(viewModel, state, imports)
                }
            }
        }
    }
}

@Composable
private fun PhonePanel(viewModel: DashboardViewModel, state: DashboardScreenState, tab: PhoneTab) {
    val context = LocalContext.current
    val phone = viewModel.phoneCompanion
    val phoneState by phone.connectionState.collectAsStateWithLifecycle()
    val phoneName by phone.connectedDeviceName.collectAsStateWithLifecycle()
    val thisDeviceIsPhone by phone.thisDevice.collectAsStateWithLifecycle()
    val contacts by phone.contacts.collectAsStateWithLifecycle()
    val recents by phone.recentCalls.collectAsStateWithLifecycle()
    val photos by phone.photos.collectAsStateWithLifecycle()
    val contactsAllowed by phone.contactsAllowed.collectAsStateWithLifecycle()
    val callLogAllowed by phone.callLogAllowed.collectAsStateWithLifecycle()
    val allowHere = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { phone.refreshThisDevice() }
    PhoneDialog(
        state = PhonePanelState(
            contacts, recents, photos, contactsAllowed, callLogAllowed,
            connected = phoneState == CompanionConnectionState.CONNECTED || phoneState == CompanionConnectionState.DEMO,
            offlineText = when {
                phoneName.isEmpty() -> stringResource(R.string.phone_none_linked)
                phoneState == CompanionConnectionState.CONNECTING -> stringResource(R.string.phone_connecting_to, phoneName)
                else -> stringResource(R.string.phone_not_connected_named, phoneName)
            },
            thisDevice = thisDeviceIsPhone,
            canCall = phone.canCall
        ),
        tab = tab,
        onTab = { state.phoneTab = it },
        onCall = { number, name ->
            if (phone.placeCall(number)) { state.toast = context.getString(R.string.toast_calling, name); state.phoneTab = null }
            else state.toast = context.getString(R.string.toast_phone_not_connected)
        },
        // The phone listens for the message; it is read back on the dashboard card to send or say again.
        onSms = { number, name ->
            if (phone.startSms(number, name)) state.phoneTab = null
            else state.toast = context.getString(R.string.toast_phone_not_connected)
        },
        onNeedPhoto = phone::requestPhoto,
        onReconnect = phone::retryNow,
        onAllow = { allowHere.launch(THIS_DEVICE_PERMISSIONS) },
        onClose = { state.phoneTab = null }
    )
}

@Composable
private fun SearchPanel(viewModel: DashboardViewModel, state: DashboardScreenState, purpose: SearchPurpose) {
    val phone = viewModel.phoneCompanion
    val telemetryState = viewModel.telemetry.collectAsStateWithLifecycle()
    val voiceSearch by phone.voiceSearch.collectAsStateWithLifecycle()
    val phoneState by phone.connectionState.collectAsStateWithLifecycle()
    val savedRoutes by viewModel.savedRoutes.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val searchOffer by viewModel.searchOffer.collectAsStateWithLifecycle()
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val planning = isPlanning(preview)
    DestinationSearchDialog(
        // Read again once a search download offered here is done, so the line stops saying there is none.
        searchStatus = remember(searchOffer) { viewModel.searchStatus() },
        voice = voiceSearch,
        listenAtOnce = state.speakOnOpen,
        onListened = { state.speakOnOpen = false },
        onVoice = if (phoneState == CompanionConnectionState.CONNECTED || phoneState == CompanionConnectionState.DEMO) ({ phone.startVoiceSearch() }) else null,
        onVoiceCancel = phone::cancelVoiceSearch,
        voiceUnavailable = stringResource(R.string.voice_needs_phone),
        onSearch = { viewModel.searchPlaces(it) },
        onPick = { r ->
            if (purpose == SearchPurpose.START && r.kind == KIND_HERE) {
                if (state.startFirst) { state.planStart = null; state.startFirst = false; state.searchPurpose = SearchPurpose.DESTINATION }
                else viewModel.setPreviewStart(null, "")
            } else state.usePlace(purpose, GeoPoint(r.lat, r.lon), r.name)
            if (state.searchPurpose == purpose) state.closeSearch()
        },
        // Leaving the start search opened from "Where to?" goes back there.
        onDismiss = { if (state.startFirst) { state.startFirst = false; state.searchPurpose = SearchPurpose.DESTINATION } else state.closeSearch() },
        savedRoutes = savedRoutes.filter { it.inWhereTo },
        recents = remember(purpose) { viewModel.recents() },
        favorites = favorites,
        onDeleteFavorite = viewModel::deleteFavorite,
        onPickSaved = { state.closeSearch(); viewModel.previewSaved(it) },
        onDeleteSaved = { viewModel.deleteSavedRoute(it) },
        onNearest = { viewModel.nearest(it) },
        nearestEmpty = stringResource(if (telemetryState.value.hasGpsFix) R.string.search_nearest_none else R.string.search_nearest_no_position),
        searchOffer = searchOffer, onDownloadSearch = viewModel::downloadSearchIndex,
        onShown = viewModel::checkSearchOffer,
        purpose = purpose,
        onPickOnMap = { state.startMapPick(purpose) },
        growIn = state.searchGrowIn,
        startName = state.planStart?.second,
        onChangeStart = if (purpose == SearchPurpose.DESTINATION && !isNavigating && !planning) {
            { state.startFirst = true; state.searchGrowIn = false; state.searchPurpose = SearchPurpose.START }
        } else null,
        // A loop only as a new trip: not while one is planned or ridden.
        onRoundtrip = if (purpose == SearchPurpose.DESTINATION && !isNavigating && preview == null) {
            { state.roundtripStart = state.planStart; state.closeSearch(); state.sketching = true }
        } else null
    )
}

/** GPX & rides: the GPX tracks, the rides recorded, the trips planned and those shared with the rider. */
@Composable
private fun TracksPanel(viewModel: DashboardViewModel, state: DashboardScreenState, imports: DashboardImports) {
    PagedCockpitDialog(
        title = stringResource(R.string.tracks_title), icon = Icons.Default.Route, accent = NeonViolet, onDismiss = { state.showGpx = false }, tag = "gpx_manager",
        pages = TracksTab.entries, selected = state.tracksTab, onSelect = { state.tracksTab = it },
        pageLabel = { stringResource(it.label) }, pageIcon = { it.icon }, pageBadge = { it == TracksTab.RIDES && state.recordingResting }, tabsWidth = 170.dp,
        actions = { if (state.tracksTab == TracksTab.GPX) GpxImportButton { imports.importGpx() } }
    ) { which ->
        when (which) {
            TracksTab.GPX -> GpxPage(viewModel, state, imports)
            TracksTab.RIDES -> RidesPage(viewModel, state)
            TracksTab.PLANNED -> PlannedPage(viewModel, state)
            TracksTab.SHARED -> {
                val sharedTripList by viewModel.sharedTrips.shared.collectAsStateWithLifecycle()
                SharedTripsPage(
                    trips = sharedTripList,
                    onRide = { t -> state.showGpx = false; viewModel.sharedTrips.open(t) },
                    onRemove = viewModel.sharedTrips::remove
                )
            }
        }
    }
}

@Composable
private fun GpxPage(viewModel: DashboardViewModel, state: DashboardScreenState, imports: DashboardImports) {
    val context = LocalContext.current
    val savedGpxTracks by viewModel.savedGpxTracks.collectAsStateWithLifecycle()
    val gpxGroups by viewModel.gpxGroups.collectAsStateWithLifecycle()
    val gpxMessage by viewModel.gpxMessage.collectAsStateWithLifecycle()
    val poiCategories by viewModel.poiCategories.collectAsStateWithLifecycle()
    val autoFollow by viewModel.autoFollowTracks.collectAsStateWithLifecycle()
    val companionInstalled = state.companionInstalled
    GpxManagerDialog(
        tracks = savedGpxTracks,
        groups = gpxGroups,
        message = gpxMessage,
        poiCategories = poiCategories,
        autoFollow = autoFollow,
        actions = remember(viewModel, companionInstalled) {
            GpxActions(
                onImport = { imports.importGpx() },
                onToggleOnMap = { t, on -> viewModel.setTrackOnMap(t, on) },
                onColor = { t, c -> viewModel.setTrackColor(t, c) },
                onReverse = { t, r -> viewModel.setTrackReversed(t, r) },
                onTogglePois = { t, on -> viewModel.setTrackPois(t, on) },
                onRide = { t -> state.showGpx = false; viewModel.previewGpx(t, reverse = false, ground = GroundPreference.MIXED) },
                onToRoute = { t -> state.showGpx = false; viewModel.gpxToTrip(t, GroundPreference.MIXED) },
                onDelete = { viewModel.deleteGpx(it) },
                onRenameTrack = { t, name -> viewModel.renameTrack(t, name) },
                onMove = { t, id, name -> viewModel.moveTrack(t, id, name) },
                onGroupVisibility = { viewModel.toggleGroupVisibility(it) },
                onGroupDirection = { viewModel.toggleGroupDirection(it) },
                onGroupColor = { g, c -> viewModel.setGroupColor(g, c) },
                onRenameGroup = { g, name -> viewModel.renameGroup(g, name) },
                onDeleteGroup = { g, keep -> viewModel.deleteGroup(g, keep) },
                onExport = { g, t, uri -> viewModel.exportGpx(g, t, uri) },
                onSendToTablet = if (companionInstalled) { t -> state.sendToTablet(viewModel.gpxForTablet(t)) } else null,
                onShareWithGroup = { t -> state.toast = viewModel.shareWithGroup(viewModel.gpxForTablet(t)) },
                onShareAsGpx = { t -> viewModel.shareGpxTrack(t) { state.toast = it } },
                onShareGroup = { g -> viewModel.shareGpxGroup(g) { state.toast = it } },
                onToPlanned = { t ->
                    state.toast = context.getString(if (viewModel.gpxToPlanned(t)) R.string.toast_added_to_planned else R.string.share_nothing)
                }
            )
        },
        onTogglePoiCategory = viewModel::togglePoiCategory,
        onAutoFollow = viewModel::setAutoFollow,
        onDismiss = { state.showGpx = false },
        embedded = true
    )
}

@Composable
private fun RidesPage(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val context = LocalContext.current
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    val currentRide by viewModel.currentRide.collectAsStateWithLifecycle()
    val routeGroups by viewModel.routeGroups.collectAsStateWithLifecycle()
    val gpxGroups by viewModel.gpxGroups.collectAsStateWithLifecycle()
    val logRides by viewModel.logRides.collectAsStateWithLifecycle()
    val autoSaveRidesGroup by viewModel.autoSaveRidesGroup.collectAsStateWithLifecycle()
    RideLogDialog(
        current = currentRide,
        rides = rides,
        // A ride the GPS hardly caught has no line to ride or keep: say so rather than do nothing.
        onRideAgain = { if (viewModel.rideHasTrack(it)) { state.showGpx = false; viewModel.rideAgain(it) } else state.tabletResult = com.curveon.data.bluetooth.TabletRoutes.Outcome.EMPTY },
        onRideReversed = { if (viewModel.rideHasTrack(it)) { state.showGpx = false; viewModel.rideAgain(it, reverse = true) } else state.tabletResult = com.curveon.data.bluetooth.TabletRoutes.Outcome.EMPTY },
        plannedGroups = routeGroups.map { com.curveon.data.model.GpxGroup(id = it.id, name = it.name) },
        gpxGroups = gpxGroups,
        saveGroups = viewModel.saveGroups,
        onSave = { r, name, t ->
            if (!viewModel.rideHasTrack(r)) { state.tabletResult = com.curveon.data.bluetooth.TabletRoutes.Outcome.EMPTY; return@RideLogDialog }
            viewModel.saveRide(r, name, t)
            state.toast = context.getString(savedToast(t))
            state.flashRail(t)
        },
        logRides = logRides,
        onLogRides = { viewModel.setLogRides(it) },
        onStopRecording = viewModel::stopRide,
        onMerge = viewModel::mergeRides,
        onSendToTablet = if (state.companionInstalled) { r -> state.sendToTablet(viewModel.rideForTablet(r)) } else null,
        onShareWithGroup = { r -> state.toast = viewModel.shareWithGroup(viewModel.rideForTablet(r)) },
        autoSaveGroup = autoSaveRidesGroup,
        onAutoSave = { id, name -> viewModel.setAutoSaveRides(id, name) },
        onDelete = { viewModel.deleteRides(it) },
        onDismiss = { state.showGpx = false },
        embedded = true
    )
}

@Composable
private fun ColumnScope.PlannedPage(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val context = LocalContext.current
    val savedRoutes by viewModel.savedRoutes.collectAsStateWithLifecycle()
    val routeGroups by viewModel.routeGroups.collectAsStateWithLifecycle()
    val gpxGroups by viewModel.gpxGroups.collectAsStateWithLifecycle()
    val webPlan by viewModel.webPlan.collectAsStateWithLifecycle()
    // Connected to the web planner while this page is open: a trip sent there arrives at once.
    DisposableEffect(Unit) { viewModel.webPlanShown(); onDispose { viewModel.webPlanHidden() } }
    PlannedTripsPage(
        trips = savedRoutes.filter { it.inPlanned },
        groups = routeGroups,
        onRide = { t, reversed -> state.showGpx = false; viewModel.ridePlanned(t, reversed) },
        gpxGroups = gpxGroups,
        onSaveToGpx = { t, groupId, newGroup -> viewModel.savePlannedToGpx(t, groupId, newGroup); state.toast = context.getString(R.string.toast_saved_to_gpx); state.flashRail(RouteSaveTargets(toGpx = true)) },
        onRename = viewModel::renamePlanned,
        onMove = viewModel::movePlanned,
        onDelete = viewModel::deletePlannedTrips,
        onRenameGroup = viewModel::renamePlannedGroup,
        onDeleteGroup = viewModel::deletePlannedGroup,
        onSendToTablet = if (state.companionInstalled) state::sendToTablet else null,
        onShareWithGroup = { t -> state.toast = viewModel.shareWithGroup(t) },
        onFromWeb = { state.showWebPlan = true }
    )
    if (state.showWebPlan) WebPlanDialog(
        state = webPlan,
        onLink = { uri -> viewModel.addWebPlanLink(uri).also { if (it) state.toast = context.getString(R.string.web_plan_linked) } },
        onFetch = { viewModel.fetchWebPlans() },
        onForget = { viewModel.forgetWebPlans(); state.showWebPlan = false },
        onDismiss = { state.showWebPlan = false }
    )
}
