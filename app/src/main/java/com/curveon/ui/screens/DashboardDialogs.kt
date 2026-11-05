package com.curveon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.bluetooth.TabletRoutes
import com.curveon.data.model.GeoPoint
import com.curveon.data.routing.NavigationGoal
import com.curveon.ui.components.*
import com.curveon.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay

/**
 * A ringing call is big and covers whatever is there: answering or declining is all that matters.
 * An answered one is only a small icon the rider can drag out of the way, on every screen.
 */
@Composable
internal fun BoxScope.CallLayer(viewModel: DashboardViewModel, portrait: Boolean) {
    val phone = viewModel.phoneCompanion
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val call = notifications.firstOrNull { it.isIncomingCall }
    if (call != null) {
        val callOngoing = call.isOngoingCall
        val onAction: (Int) -> Unit = { i -> phone.invokeAction(call.id, i) }
        val onDismiss = { phone.hideNotification(call.id) }
        // An answered call is an icon over the full-screen map; with the dashboard's cards showing, it is a card there (portrait hides them while a trip is planned).
        if (callOngoing) { if (isFullScreenMap || (portrait && preview != null)) FloatingCallBubble(call, onAction, onDismiss, Modifier.matchParentSize().padding(8.dp)) }
        else CallCard(call, onAction, onDismiss, Modifier.align(Alignment.Center).fillMaxWidth(0.92f).widthIn(max = 440.dp)
            .fillMaxHeight(0.85f).heightIn(max = 320.dp))
    }
}

/** The group ride: its dialog, the rider's own SOS and the alert for someone else's. */
@Composable
internal fun GroupLayer(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val phone = viewModel.phoneCompanion
    val currentLocationState = viewModel.currentLocation.collectAsStateWithLifecycle()
    val telemetryState = viewModel.telemetry.collectAsStateWithLifecycle()
    val useMph by remember { derivedStateOf { telemetryState.value.useMph } }
    GroupInviteOfferDialog(phone.groups)
    FriendHighFive(phone.groups)
    val shownRiders = state.shownRiders
    when (shownRiders.size) {
        0 -> Unit
        1 -> RiderInfoDialog(
            phone.groups, shownRiders[0], currentLocationState.value.latitude, currentLocationState.value.longitude, useMph,
            onDismiss = { state.shownRiders = emptyList() }
        )
        else -> RiderPickerDialog(
            phone.groups, shownRiders, useMph, onPick = { state.shownRiders = listOf(it) }, onDismiss = { state.shownRiders = emptyList() }
        )
    }
    NudgeAlert(phone.groups)
    GroupSosOverlay(
        phone.groups, currentLocationState.value.latitude, currentLocationState.value.longitude,
        navigateTo = { lat, lon, name -> viewModel.previewDestination(GeoPoint(lat, lon), name) }
    )
}

/** Offers and answers that may come at any time, also with no rail screen open, and the walks through the app. */
@Composable
internal fun DashboardOffers(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val context = LocalContext.current
    val sharedOffer by viewModel.sharedTrips.offer.collectAsStateWithLifecycle()
    val friendList by viewModel.phoneCompanion.groups.friends.collectAsStateWithLifecycle()
    val routeFromPhone by viewModel.routeFromPhone.collectAsStateWithLifecycle()
    SharedOffers(viewModel, sharedOffer) { state.toast = it }
    state.pickFriends?.let { fromPreview ->
        FriendPickerDialog(
            friendList,
            onSend = { ids ->
                state.pickFriends = null
                state.toast = context.getString(if (viewModel.sharedTrips.sendToFriends(ids, fromPreview)) R.string.toast_trip_sent_friends else R.string.toast_trip_not_shared)
            },
            onDismiss = { state.pickFriends = null }
        )
    }
    routeFromPhone?.let { t ->
        RouteFromPhoneDialog(
            trip = t,
            onRideNow = { viewModel.dismissRouteFromPhone(); state.showGpx = false; viewModel.previewSaved(t) },
            onLater = viewModel::dismissRouteFromPhone
        )
    }
    state.tabletResult?.let { o ->
        SendToTabletResultDialog(o, onOpenCompanion = { TabletRoutes.openCompanion(context) }, onDismiss = { state.tabletResult = null })
    }
    state.whatsNew?.let { WhatsNewDialog(it, onDismiss = { state.whatsNew = null }) }
    if (state.showTour) WelcomeTour(state.tourTargets, firstStart = state.tourIsFirst, onFinish = { state.showTour = false; state.tourIsFirst = false; viewModel.markTourSeen() })
    TripHelp(viewModel, state)
}

/** The first time a trip to a place is planned (the card open, not while planning on the map): a short walk over its symbols and buttons. */
@Composable
private fun TripHelp(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val planGoal = preview?.goal as? NavigationGoal.Destination
    val tripHelpWanted = planGoal != null && planGoal.loop == null && planGoal.adventure == null && preview?.selectedRoute != null &&
        !state.previewMinimized && !state.chaining(isNavigating) && !state.showTour && !viewModel.tripHelpSeen
    LaunchedEffect(tripHelpWanted) {
        if (tripHelpWanted) { delay(800); state.tripHelpOpen = true } else state.tripHelpOpen = false
    }
    if (state.tripHelpOpen && tripHelpWanted) WelcomeTour(state.tourTargets, firstStart = false, tripCard = true,
        onFinish = { state.tripHelpOpen = false; viewModel.markTripHelpSeen() })
}

/**
 * Trips and GPX that someone shared, to save or ignore. A function of its own and not part of [DashboardScreen]: that one
 * was so big that one more value made the compiler write code Android 17 refuses to run (it crashed at start on 2026-11-05).
 */
@Composable
private fun SharedOffers(viewModel: DashboardViewModel, sharedOffer: com.curveon.data.groups.SharedTrip?, toast: (String) -> Unit) {
    val context = LocalContext.current
    val sharedGpxOffers by viewModel.sharedTrips.gpx.offers.collectAsStateWithLifecycle()
    val sharedGpxOffer = sharedGpxOffers.firstOrNull()
    var savingGpxShare by remember { mutableStateOf<String?>(null) }
    val tripNamesTaken by viewModel.tripNamesTaken.collectAsStateWithLifecycle()
    val gpxGroupNames by viewModel.gpxGroupNames.collectAsStateWithLifecycle()
    val gpxTrackNames by viewModel.gpxTrackNames.collectAsStateWithLifecycle()
    sharedOffer?.let { t ->
        SharedTripOfferDialog(
            trip = t,
            onRideNow = { name -> viewModel.sharedTrips.open(t, name) },
            onSave = { name -> viewModel.sharedTrips.accept(t, name); toast(context.getString(R.string.toast_shared_trip_saved)) },
            onIgnore = { viewModel.sharedTrips.ignore(t) },
            fromFriend = viewModel.sharedTrips.fromFriend(t),
            taken = tripNamesTaken
        )
    }
    sharedGpxOffer?.takeIf { sharedOffer == null && it.shareId != savingGpxShare }?.let { o ->
        SharedGpxOfferDialog(
            offer = o,
            taken = if (o.isGroup) gpxGroupNames else gpxTrackNames,
            onSave = { name ->
                savingGpxShare = o.shareId // gone at once: two presses must not save it twice
                viewModel.saveSharedGpx(o, name) { toast(it); savingGpxShare = null }
            },
            onIgnore = { viewModel.sharedTrips.gpx.answered(o.shareId) }
        )
    }
}

/** What a place, a stop, a point of the trip or the trip being ridden can be made: the menus and dialogs for them. */
@Composable
internal fun DashboardPlaceDialogs(viewModel: DashboardViewModel, state: DashboardScreenState) {
    CompositionLocalProvider(LocalToast provides state.toast) {
        PlaceDialog(viewModel, state)
        TripPointMenus(viewModel, state)
        SaveDialog(viewModel, state)
        RidingRouteMenu(viewModel, state)
        state.shownPoi?.let { poi -> PoiOnMapDialog(viewModel, state, poi) }
    }
}

@Composable
private fun PlaceDialog(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val planning = isPlanning(preview)
    val telemetryState = viewModel.telemetry.collectAsStateWithLifecycle()
    val useMph by remember { derivedStateOf { telemetryState.value.useMph } }
    val favoriteSaved = stringResource(R.string.favorite_saved)
    state.pendingPlace?.let { (point, name) ->
        PlaceActionDialog(
            name = name,
            road = state.roadInfo,
            useMph = useMph,
            tracks = state.pressedTracks?.takeIf { it.first == point }?.second.orEmpty(),
            onAddStop = if (isNavigating || planning) ({ viewModel.addStop(point, name); state.pendingPlace = null }) else null,
            onGoHere = {
                if (planning) viewModel.setPreviewDestination(point, name) else viewModel.previewDestination(point, name)
                state.pendingPlace = null
            },
            onStartHere = if (planning) ({ viewModel.setPreviewStart(point, name); state.pendingPlace = null }) else null,
            // On to the map to tap the next points (as "Pick on map" from search): either from here to
            // this place first, or with the trip starting at this place (nothing routed until the next tap).
            onPointByPoint = if (isNavigating || planning) null else ({ fromHere ->
                state.pendingPlace = null
                if (fromHere) viewModel.planPointByPoint(point, name) else state.planStart = point to name
                state.startMapPick(SearchPurpose.DESTINATION)
            }),
            onFavorite = { n, home ->
                viewModel.saveFavorite(point, n, home)
                state.pendingPlace = null
                state.toast = favoriteSaved
            },
            onDismiss = { state.pendingPlace = null }
        )
    }
}

/** A stop's menu, or the start's or destination's, from its handle while editing on the map. */
@Composable
private fun TripPointMenus(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val planGoal = preview?.goal as? NavigationGoal.Destination
    state.editStopMenu?.let { i ->
        val stops = planGoal?.stops.orEmpty()
        val st = stops.getOrNull(i)
        if (st == null) state.editStopMenu = null
        else StopMenuDialog(
            name = st.name,
            canMoveEarlier = !st.via && i > 0, canMoveLater = !st.via && i < stops.lastIndex,
            onMove = { d -> state.editStopMenu = null; viewModel.movePreviewStop(i, d) },
            onRemove = { state.editStopMenu = null; viewModel.removePreviewStop(i) },
            removeLabel = stringResource(if (st.via) R.string.remove_point else R.string.preview_remove_stop),
            onDismiss = { state.editStopMenu = null },
            // From the map: the search takes over (and the point goes in where asked, then back to the trip).
            onChangePlace = { state.editStopMenu = null; state.mapPick = null; state.picked = null; state.stopTarget = StopTarget.Instead(i); state.searchPurpose = SearchPurpose.STOP },
            onAddBefore = { state.editStopMenu = null; state.mapPick = null; state.picked = null; state.stopTarget = StopTarget.Before(i); state.searchPurpose = SearchPurpose.STOP }
        )
    }
    state.endMenu?.let { kind ->
        val g = planGoal
        if (g == null) state.endMenu = null
        else StopMenuDialog(
            name = if (kind == HandleKind.START) g.start?.name.orEmpty() else g.name,
            canMoveEarlier = false, canMoveLater = false, onMove = {},
            onRemove = {
                state.endMenu = null
                if (kind == HandleKind.START) viewModel.setPreviewStart(null, "") else viewModel.removePreviewDestination()
            },
            onDismiss = { state.endMenu = null },
            removeLabel = stringResource(R.string.remove_point),
            onChangePlace = {
                state.endMenu = null; state.mapPick = null; state.picked = null
                state.searchPurpose = if (kind == HandleKind.START) SearchPurpose.START else SearchPurpose.DESTINATION
            }
        )
    }
}

@Composable
private fun SaveDialog(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val context = LocalContext.current
    val routeGroups by viewModel.routeGroups.collectAsStateWithLifecycle()
    val gpxGroups by viewModel.gpxGroups.collectAsStateWithLifecycle()
    state.saveName?.let { suggested ->
        SaveRouteDialog(
            initialName = suggested,
            initial = viewModel.saveGroups,
            plannedGroups = routeGroups.map { com.curveon.data.model.GpxGroup(id = it.id, name = it.name) },
            gpxGroups = gpxGroups,
            onSave = { name, t ->
                if (t.toWhereTo || t.toPlanned) viewModel.saveRoute(name, t.toWhereTo, t.toPlanned, t.plannedGroup, t.newPlannedGroup)
                if (t.toGpx) viewModel.saveRouteToGpx(name, t.gpxGroup, t.newGpxGroup)
                viewModel.rememberSaveGroups(t)
                state.saveName = null
                state.toast = context.getString(savedToast(t))
                state.flashRail(t)
            },
            onDismiss = { state.saveName = null }
        )
    }
}

/** The trip being ridden: its menu from the trip bar (stops, save, share, edit, end). */
@Composable
private fun RidingRouteMenu(viewModel: DashboardViewModel, state: DashboardScreenState) {
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val navState by viewModel.navigation.collectAsStateWithLifecycle()
    val sharingGroup by viewModel.phoneCompanion.groups.activeId.collectAsStateWithLifecycle()
    if (state.showRouteMenu && isNavigating) {
        val along = navState.progress?.distanceAlongRoute ?: 0.0
        val realStops = navState.stopsAlong.withIndex().filter { !it.value.first.via }
        val destination = navState.goal as? NavigationGoal.Destination
        RouteMenuDialog(
            title = navState.goal?.title ?: "",
            // Points the route was dragged through aren't stops to the rider.
            stops = realStops.map { (_, sa) -> sa.first.name to ((sa.second - along) / 1000).coerceAtLeast(0.0) },
            onRemoveStop = if (destination != null) { i: Int -> state.showRouteMenu = false; realStops.getOrNull(i)?.let { viewModel.removeStop(it.index) } } else null,
            onAddStop = { state.showRouteMenu = false; state.searchPurpose = SearchPurpose.STOP },
            onSave = { state.showRouteMenu = false; state.askSaveName() },
            onShare = if (sharingGroup != null && destination?.shared == null) { { state.showRouteMenu = false; state.shareTrip() } } else null,
            shared = destination?.shared != null,
            // A trip to a place with no group yet: Share leads to Group ride, to start or pick one.
            onFindGroup = if (sharingGroup == null && destination != null) { { state.showRouteMenu = false; state.pressRail(RailPanel.GROUP) } } else null,
            onEdit = if (destination != null) { { state.showRouteMenu = false; state.editingRoute = viewModel.editRoute() } } else null,
            onShareFriends = if (destination != null) { { state.showRouteMenu = false; state.pickFriends = false } } else null,
            onEnd = { state.showRouteMenu = false; viewModel.stopNavigation() },
            onDismiss = { state.showRouteMenu = false }
        )
    }
}

@Composable
private fun PoiOnMapDialog(viewModel: DashboardViewModel, state: DashboardScreenState, poi: com.curveon.data.gpx.GpxPoi) {
    val context = LocalContext.current
    val phone = viewModel.phoneCompanion
    val currentLocationState = viewModel.currentLocation.collectAsStateWithLifecycle()
    PoiDialog(
        poi, from = currentLocationState.value,
        onGo = { state.shownPoi = null; state.pickPlace(GeoPoint(poi.lat, poi.lon), poi.displayName(context)) },
        onCall = { number -> if (phone.placeCall(number)) state.toast = context.getString(R.string.toast_calling, poi.displayName(context)) else state.toast = context.getString(R.string.toast_connect_phone_to_call) },
        onDismiss = { state.shownPoi = null }
    )
}
