package com.curveon.ui.screens

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Route
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.curveon.R
import com.curveon.data.bluetooth.TabletRoutes
import com.curveon.data.model.GeoPoint
import com.curveon.data.model.RouteItem
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.RoutePreview
import com.curveon.ui.components.HandleKind
import com.curveon.ui.components.PhoneTab
import com.curveon.ui.components.RouteSaveTargets
import com.curveon.ui.components.SearchPurpose
import com.curveon.ui.components.TourTargets
import com.curveon.ui.components.legIndexAt
import com.curveon.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the dashboard keeps while it is shown: which rail screen, menu or dialog is open, what is being picked,
 * edited or drawn on the map, and where the cards over the map sit. One object, so that the dashboard's parts can be
 * functions of their own ([DashboardScreen] grew so big that the compiler wrote code Android 17 refused to run).
 *
 * What the rider sees comes from the [DashboardViewModel]'s flows, collected where it is drawn; what happens on a tap
 * reads them as they are at that moment.
 */
@Stable
internal class DashboardScreenState(
    private val viewModel: DashboardViewModel,
    val scope: CoroutineScope,
    private val context: Context,
    showSettings: MutableState<Boolean>,
    showGroup: MutableState<Boolean>,
    tracksTab: MutableState<TracksTab>,
    showTour: MutableState<Boolean>,
    tourIsFirst: MutableState<Boolean>,
    webPlanShownReport: MutableState<Long>,
    tripHelpOpen: MutableState<Boolean>,
) {
    // Saveable: changing the language restarts the screen, and the rider should stay in Settings.
    var showSettings by showSettings
    var showGroup by showGroup
    var tracksTab by tracksTab
    var showTour by showTour
    /** The very first tour starts with the choice of language. */
    var tourIsFirst by tourIsFirst
    /** Once for each time something came in from the web planner: swiping between the tabs connects and disconnects, which is not news. */
    var webPlanShownReport by webPlanShownReport
    var tripHelpOpen by tripHelpOpen

    val tourTargets = TourTargets()

    var demoMenu by mutableStateOf(false)
    /** The newest message's banner over the full-screen map, put away (its key). */
    var bannerHidden by mutableStateOf<String?>(null)
    // A tap on the road card shows the road's name for a few seconds; a tap anywhere else puts it away.
    var roadNameShown by mutableStateOf(false)
    var roadCardBounds by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    var rootTopLeft by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    var shownPoi by mutableStateOf<com.curveon.data.gpx.GpxPoi?>(null)
    /**
     * Riders or friends tapped on the map ([com.curveon.ui.components.MapGroupRider.id]): one shows what is known about
     * them, several (standing together) are listed to pick from.
     */
    var shownRiders by mutableStateOf<List<String>>(emptyList())

    /** The open search and what it is for; null when closed. */
    var searchPurpose by mutableStateOf<SearchPurpose?>(null)
    /** "Pick on map": what the search was for while the rider taps the map; null when not picking. */
    var mapPick by mutableStateOf<SearchPurpose?>(null)
    var picked by mutableStateOf<Pair<GeoPoint, String?>?>(null)
    /**
     * Point by point from "+ Stop" on a trip that already has an end: whether the points tapped are
     * stops on the way (true) or ride the trip on past its end (false); null = not asked yet.
     */
    var alongTheWay by mutableStateOf<Boolean?>(null)
    /** A new trip set to start elsewhere in "Where to?", before the destination; null = from here. */
    var planStart by mutableStateOf<Pair<GeoPoint, String>?>(null)
    /** The start search was opened from "Where to?": its pick goes back there. */
    var startFirst by mutableStateOf(false)
    /** The search reopens growing out of the pick bar. */
    var searchGrowIn by mutableStateOf(false)
    /** Editing on the map: the next drag along the line marks the part of the route to change. */
    var marking by mutableStateOf(false)
    /** Editing on the map: the route locked against dragging, so a pan can't bend it (locked again each time editing starts). */
    var routeLocked by mutableStateOf(true)
    /** The help bubble for planning on the map: again each time point by point starts, unless hidden for good. */
    var showPbpHelp by mutableStateOf(false)
    // The route preview folded down to a bar so more of the route shows; open again for each new plan.
    var previewMinimized by mutableStateOf(false)
    /** Stop menu opened from its handle while editing (index into the trip's stops). */
    var editStopMenu by mutableStateOf<Int?>(null)
    /** The start or destination held on the map: its menu (remove it). */
    var endMenu by mutableStateOf<HandleKind?>(null)
    /** Where the next stop from the search goes; null = wherever it adds least detour. */
    var stopTarget by mutableStateOf<StopTarget?>(null)
    var showRouteMenu by mutableStateOf(false)
    /** Suggested name while the save dialog is open. */
    var saveName by mutableStateOf<String?>(null)
    /** Where the roundtrip starts when set elsewhere in "Where to?"; null = from here. */
    var roundtripStart by mutableStateOf<Pair<GeoPoint, String>?>(null)
    /** Drawing the roundtrip's area on the map. */
    var sketching by mutableStateOf(false)
    /** About how long the loop round the area drawn so far gets (its outline); null before drawing. */
    var sketchKm by mutableStateOf<Int?>(null)
    /** Chosen in the drawing bar: how long the loop may be (null = round the whole area), and its surface. */
    var sketchMaxKm by mutableStateOf<Int?>(null)
    var sketchSurface by mutableStateOf(viewModel.lastSurface())
    var sketchGravelReach by mutableStateOf(viewModel.lastGravelReach())
    /** Drawing the area for an adventure to this place instead of a loop's. */
    var adventure by mutableStateOf<AdventureDraft?>(null)
    /** "Good weather – pick for me" is working out the direction, or found no forecast to go by. */
    var weatherPicking by mutableStateOf(false)
    var weatherPickFailed by mutableStateOf(false)
    var showGpx by mutableStateOf(false)
    /** After saving a route or a ride: the rail buttons where it was put blink (the set, and a count so that the same one can blink again). */
    var railFlash by mutableStateOf<Pair<Set<RailPanel>, Int>>(emptySet<RailPanel>() to 0)
    /** Settings opened from the engine panel: start on its page. */
    var settingsOnEngine by mutableStateOf(false)
    /** Settings opened while a new version waits: start on Help, where it is. */
    var settingsOnHelp by mutableStateOf(false)
    /** What's new: shown once after an update, and from Settings → Help. */
    var whatsNew by mutableStateOf<List<com.curveon.data.update.ChangelogEntry>?>(null)
    /** The phone app's open tab; null when closed. */
    var phoneTab by mutableStateOf<PhoneTab?>(null)
    /** A place picked while riding or planning: a stop, the destination, or (planning) the start? */
    var pendingPlace by mutableStateOf<Pair<GeoPoint, String>?>(null)
    /** The road at the place held on the map (name, surface, speed limit), once looked up. */
    var roadInfo by mutableStateOf<com.curveon.data.routing.RoadInfo?>(null)
    /** The GPX tracks under a finger held on the map (name, colour), for the place there. */
    var pressedTracks by mutableStateOf<Pair<GeoPoint, List<Pair<String, String>>>?>(null)
    var toast by mutableStateOf<String?>(null)
    // Trips from the web planner: Planned → From the web.
    var showWebPlan by mutableStateOf(false)
    // Trips for the tablet, sent from Curveon on the phone through Curveon Companion: only offered when it is installed.
    var companionInstalled by mutableStateOf(TabletRoutes.companionInstalled(context))
    var tabletResult by mutableStateOf<TabletRoutes.Outcome?>(null)
    // The trip being ridden is open in the planner (Edit route): closing it just keeps riding it as it was.
    var editingRoute by mutableStateOf(false)
    // Picking friends to send a trip to: true for the one in the preview, false for the one being ridden.
    var pickFriends by mutableStateOf<Boolean?>(null)
    var reachedStop by mutableStateOf<String?>(null)
    /** Whether the dashboard (not the full map) was showing before picking on the map took the full map. */
    var dashboardBefore by mutableStateOf<Boolean?>(null)
    /** The full map gave way to the dashboard for writing a reply: it comes back afterwards. */
    var mapBeforeReply by mutableStateOf(false)
    /** Editing on the map: the last route found, kept on screen while the next one is worked out. */
    var editLine by mutableStateOf<List<GeoPoint>>(emptyList())

    // Where the cards sit over the map (see [mapCorners]).
    var mapBox by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var speedSize by mutableStateOf(androidx.compose.ui.unit.DpSize.Zero)
    var tripBarWidth by mutableStateOf(0.dp)
    var tripBarHeight by mutableStateOf(0.dp)
    // The gravel heat button sits top left too when it is shown (not while navigating): the road card goes under it.
    var heatButtonShown by mutableStateOf(false)
    var turnCardWidth by mutableStateOf(0.dp)
    // The music is a small cover icon until tapped, then the player as it was.
    var mediaOpen by mutableStateOf(false)
    /** A rider not heard from for two minutes is dimmed: counts up every 30 s while riders are on the map. */
    var groupTick by mutableIntStateOf(0)
    /** The handlebar remote asked for Where to? by voice: the search listens as it opens. */
    var speakOnOpen by mutableStateOf(false)
    /** A ride is being recorded while the bike stands still (a red dot on the GPX button and the Rides page). */
    var recordingResting by mutableStateOf(false)

    /** Picking on (and editing the trip on) the map, or the search back up from the pick bar: the map goes full-screen. */
    val mapMode get() = mapPick != null || searchGrowIn || sketching

    /**
     * Picking a destination or stops on the map is also where the trip is edited on the map: each tap
     * adds a point, the line and points can be dragged, a part of the route marked, points removed.
     * A stop put before or instead of another one, a stop added while riding, or the start: one place.
     */
    fun chaining(isNavigating: Boolean) =
        !isNavigating && stopTarget == null && (mapPick == SearchPurpose.DESTINATION || mapPick == SearchPurpose.STOP)

    /** The rail's screens: the open one is lit. */
    val activePanel: RailPanel?
        get() = when {
            searchPurpose != null -> RailPanel.SEARCH
            showGpx -> RailPanel.GPX
            phoneTab != null -> RailPanel.PHONE
            showGroup -> RailPanel.GROUP
            showSettings -> RailPanel.SETTINGS
            else -> null
        }

    /** A rail screen is open over the map. */
    val panelOpen get() = searchPurpose != null || showGpx || phoneTab != null || showGroup || showSettings

    private val isNavigating get() = viewModel.isNavigating.value
    private val preview: RoutePreview? get() = viewModel.preview.value
    private val planning get() = isPlanning(preview)

    fun askSaveName() {
        scope.launch { saveName = viewModel.suggestedRouteName() }
    }

    fun flashRail(t: RouteSaveTargets) {
        val where = buildSet { if (t.toWhereTo) add(RailPanel.SEARCH); if (t.toPlanned || t.toGpx) add(RailPanel.GPX) }
        railFlash = where to railFlash.second + 1
    }

    fun sendToTablet(item: RouteItem?) {
        if (item == null) tabletResult = TabletRoutes.Outcome.EMPTY
        else TabletRoutes.send(context, item) { outcome ->
            tabletResult = outcome
        }
    }

    fun shareTrip() {
        toast = context.getString(if (viewModel.sharedTrips.shareCurrent()) R.string.toast_trip_shared else R.string.toast_trip_not_shared)
    }

    // Picking a place: while riding or planning a trip, ask what it is; otherwise go straight to the preview.
    fun pickPlace(point: GeoPoint, name: String) {
        if (isNavigating || planning) { roadInfo = null; pendingPlace = point to name }
        else viewModel.previewDestination(point, name, planStart?.first, planStart?.second ?: "")
    }

    /** The search result (or picked map point) for each purpose. */
    fun usePlace(purpose: SearchPurpose, point: GeoPoint, name: String) {
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

    fun startMapPick(purpose: SearchPurpose) {
        searchGrowIn = false
        searchPurpose = null
        picked = null
        alongTheWay = null
        marking = false
        routeLocked = true
        mapPick = purpose
    }

    fun closeSearch() {
        searchPurpose = null
        searchGrowIn = false
        stopTarget = null
        planStart = null
        startFirst = false
    }

    fun planPointByPoint(p: GeoPoint, name: String) {
        when {
            alongTheWay == true -> viewModel.addStop(p, name)
            // The trip's end was a place it was planned to: it stays a stop.
            alongTheWay == false -> viewModel.planPointByPoint(p, name, keepEndAsStop = true)
            else -> viewModel.planPointByPoint(p, name, planStart?.first, planStart?.second ?: "")
        }
    }

    /** A tap point by point: adding stops to a planned trip, the first one asks where it goes. */
    fun chainTap(p: GeoPoint) {
        if (mapPick == SearchPurpose.STOP && planning && alongTheWay == null) {
            pickPoint(p)
        } else {
            picked = null
            scope.launch { planPointByPoint(p, viewModel.describePoint(p)) }
        }
    }

    /** A point tapped on the map while picking: shown at once, named once looked up. */
    fun pickPoint(p: GeoPoint) {
        picked = p to null
        scope.launch { val name = viewModel.describePoint(p); if (picked?.first == p) picked = p to name }
    }

    fun endMapPick() {
        mapPick = null
        picked = null
        alongTheWay = null
        marking = false
        stopTarget = null
        startFirst = false
        if (searchPurpose == null) planStart = null // not on the way back to "Where to?"
    }

    /** Edit the planned trip on the map: the same as picking stops there. */
    fun editOnMap() = startMapPick(SearchPurpose.STOP)

    /** The tapped point as a via point (white dot), put where it falls along the route so the order stays right. */
    fun addShapingPoint(p: GeoPoint) {
        val plan = preview
        val goal = plan?.goal as? NavigationGoal.Destination ?: return
        val line = plan.candidates.getOrNull(plan.selected)?.route?.points?.map { GeoPoint(it.lat, it.lon) }.orEmpty()
        val index = legIndexAt(goal.stops.map { GeoPoint(it.point.lat, it.point.lon) }, line, p) ?: goal.stops.size
        scope.launch { viewModel.insertPreviewStop(index, p, viewModel.describePoint(p), via = true) }
    }

    /**
     * The rail's screens: pressing the open one closes it (like its close button), pressing another one swaps it in.
     * A pick on the map or a drawing in progress ends first.
     */
    fun pressRail(panel: RailPanel) {
        val active = activePanel
        if (mapPick != null) endMapPick()
        sketching = false
        closeSearch()
        showGpx = false
        phoneTab = null
        showGroup = false
        showSettings = false
        if (panel != active) when (panel) {
            RailPanel.SEARCH -> searchPurpose = SearchPurpose.DESTINATION
            RailPanel.GPX -> showGpx = true
            RailPanel.PHONE -> phoneTab = PhoneTab.FAVORITES
            RailPanel.GROUP -> showGroup = true
            RailPanel.SETTINGS -> { settingsOnHelp = viewModel.appUpdate.value is com.curveon.data.update.UpdateState.Available; showSettings = true }
        }
    }

    /** Drawing the area for an adventure to the planned trip's destination. */
    fun startAdventure() {
        (preview?.goal as? NavigationGoal.Destination)?.let { g ->
            // No time limit to start from: the area drawn is the limit, and the trips found say how long they take.
            adventure = AdventureDraft(g.name, g.destination, null)
            roundtripStart = g.start?.let { GeoPoint(it.point.lat, it.point.lon) to it.name }
            sketchSurface = g.options.surface
            sketchGravelReach = g.options.gravelReachKm
            sketching = true
        }
    }

    /** Drawing the loop's or the adventure's area again. */
    fun redraw() {
        val goal = preview?.goal
        ((goal as? NavigationGoal.Destination)?.adventure ?: goal as? NavigationGoal.Adventure)?.let { g ->
            adventure = AdventureDraft(g.name, g.destination, g.minutes)
            roundtripStart = g.start?.let { GeoPoint(it.point.lat, it.point.lon) to it.name }
            sketchSurface = g.options.surface
            sketchGravelReach = g.options.gravelReachKm
        }
        // Same start, max and surface as the loop on screen.
        (goal as? NavigationGoal.Destination)?.loop?.let { loop ->
            roundtripStart = loop.start?.let { GeoPoint(it.point.lat, it.point.lon) to it.name }
            sketchMaxKm = loop.maxKm
            sketchSurface = loop.options.surface
            sketchGravelReach = loop.options.gravelReachKm
        }
        sketching = true
    }

    /** The area drawn: plan the adventure or the loop round it. */
    fun finishSketch(stroke: List<GeoPoint>) {
        val trip = adventure
        sketching = false
        if (trip != null) viewModel.previewAdventure(trip.name, trip.destination, stroke, trip.minutes, roundtripStart?.first, roundtripStart?.second ?: "", sketchSurface, sketchGravelReach)
        else viewModel.previewAreaRoundtrip(stroke, roundtripStart?.first, roundtripStart?.second ?: "", sketchMaxKm, sketchSurface, sketchGravelReach)
    }

    /** "Good weather – pick for me": the loop in the direction of the best weather. */
    fun pickGoodWeather() {
        if (!weatherPicking) scope.launch {
            weatherPicking = true
            val planned = viewModel.previewWeatherLoop(sketchMaxKm, roundtripStart?.first, roundtripStart?.second ?: "", sketchSurface, sketchGravelReach)
            weatherPicking = false
            if (planned) sketching = false else weatherPickFailed = true
        }
    }

    /** Held on the map: go there or keep it as a favourite (riding or planning: also a stop or the start). */
    fun longPress(p: GeoPoint, roadName: String?, mapRoad: com.curveon.data.road.MapRoad?, tracks: List<Pair<String, String>>) {
        roadInfo = null
        pressedTracks = p to tracks
        scope.launch { pendingPlace = p to viewModel.describePoint(p) }
        scope.launch { val r = viewModel.roadAt(p, roadName, mapRoad); if (pendingPlace?.first == p || pendingPlace == null) roadInfo = r }
    }
}

/** A trip to a place is being planned (not a loop, nor a ride along a track). */
internal fun isPlanning(preview: RoutePreview?) = preview?.goal is NavigationGoal.Destination

@Composable
internal fun rememberDashboardScreenState(viewModel: DashboardViewModel): DashboardScreenState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showSettings = rememberSaveable { mutableStateOf(false) }
    val showGroup = rememberSaveable { mutableStateOf(false) }
    val tracksTab = rememberSaveable { mutableStateOf(TracksTab.GPX) }
    val showTour = rememberSaveable { mutableStateOf(false) }
    val tourIsFirst = rememberSaveable { mutableStateOf(false) }
    val webPlanShownReport = rememberSaveable { mutableStateOf(0L) }
    val tripHelpOpen = rememberSaveable { mutableStateOf(false) }
    return remember {
        DashboardScreenState(viewModel, scope, context, showSettings, showGroup, tracksTab, showTour, tourIsFirst, webPlanShownReport, tripHelpOpen)
    }
}

/** The screens the rail opens over the map. */
internal enum class RailPanel { SEARCH, GPX, PHONE, GROUP, SETTINGS }

/** Where a stop chosen in the search goes in the planned trip: before stop [index], or in its place. */
internal sealed interface StopTarget {
    val index: Int
    data class Before(override val index: Int) : StopTarget
    data class Instead(override val index: Int) : StopTarget
}

/** Pages of the GPX & rides dialog (rail button). */
internal enum class TracksTab(val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GPX(R.string.gpx_tab, Icons.Default.Route),
    RIDES(R.string.rides, Icons.Default.History),
    PLANNED(R.string.planned_tab, Icons.Default.EventNote),
    SHARED(R.string.shared_tab, Icons.Default.Diversity3)
}

/** An adventure being drawn: where to, and how long it should take (minutes of estimated riding). */
internal data class AdventureDraft(val name: String, val destination: com.curveon.routing.LatLon, val minutes: Int?)

/** What to tell the rider after saving: where it went. */
internal fun savedToast(t: RouteSaveTargets) = when {
    listOf(t.toWhereTo, t.toPlanned, t.toGpx).count { it } > 1 -> R.string.toast_saved
    t.toWhereTo -> R.string.toast_saved_to_where_to
    t.toPlanned -> R.string.toast_saved_to_planned
    else -> R.string.toast_saved_to_gpx
}
