package com.curveon.data.routing

import android.content.Context
import com.curveon.routing.BRouterEngine
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.NavigationProgress
import com.curveon.routing.NavigationTracker
import com.curveon.routing.OffRouteDecision
import com.curveon.routing.OffRoutePlanner
import com.curveon.routing.ProfileInstaller
import com.curveon.routing.RoadStyle
import com.curveon.routing.RoundtripPlanner
import com.curveon.routing.AdventurePlanner
import com.curveon.routing.NoAdventureException
import com.curveon.routing.NoRoundtripException
import com.curveon.R
import com.curveon.routing.Route
import com.curveon.routing.RouteCorridor
import com.curveon.routing.RouteOptions
import com.curveon.routing.Routes
import com.curveon.routing.TrackFollower
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

sealed interface RoutingStatus {
    data object Idle : RoutingStatus
    data class Calculating(val message: String) : RoutingStatus
    data class Failed(val message: String) : RoutingStatus
}

/** A place to ride through. [via] = a point the route was dragged through on the map, not a real stop. */
data class Stop(val name: String, val point: LatLon, val via: Boolean = false)

/** The saved route a preview was opened from, so saving it again under the same name updates it. */
data class SavedRef(val id: Long, val title: String)

/** How far along [route] each of [stops] is (they are visited in order). */
fun alongDistances(stops: List<Stop>, route: Route): List<Double> {
    if (stops.isEmpty()) return emptyList()
    val corridor = RouteCorridor(route)
    var after = 0.0
    return stops.map { s ->
        (corridor.alongDistance(s.point.lat, s.point.lon, 2_000.0, after) ?: after).also { after = it }
    }
}

/** How forgiving navigation is about leaving the route (Settings → Navigation). */
data class OffRouteSettings(
    /** Further than this from the line counts as off route. */
    val offRouteMeters: Int = 40,
    /** A way back that keeps riding ahead is taken quietly when it costs at most this many minutes more… */
    val maxExtraMinutes: Int = 3,
    /** …and at most this share of the trip that is left. */
    val maxExtraPercent: Int = 10
)

/**
 * The rider left the route and there is no quick way back without turning round: ask whether to
 * turn back or carry on. If nobody answers by [autoAtMillis] and the rider has ridden on, still
 * off the route, we carry on ("new route from here" / "rejoin further ahead"). Stopped at the
 * roadside, the question waits.
 */
data class WrongTurn(
    /** True on an A→B trip (re-plan to the destination); false on a loop or track (rejoin it). */
    val isTrip: Boolean,
    /** Stops still to visit, so carrying on can ask whether to keep them. */
    val stopsLeft: Int,
    /** Where the rider left the route, metres along it. */
    val leftAlong: Double,
    /** Extra minutes the best way ahead costs over turning back, null when there is none. */
    val aheadExtraMinutes: Int?,
    val autoAtMillis: Long,
    /** Where the rider was when asked: carrying on by itself needs them to have ridden on since. */
    val askedAt: LatLon
)

/** What the rider asked for, kept so we can recalculate after leaving the route. */
sealed interface NavigationGoal {
    val options: RouteOptions
    val title: String

    /** A→B, visiting [stops] in order on the way. */
    data class Destination(
        val name: String,
        val destination: LatLon,
        override val options: RouteOptions,
        val stops: List<Stop> = emptyList(),
        /** Planning from somewhere else than where the rider is; null = from here. */
        val start: Stop? = null,
        /** A saved route's exact line: ridden as it is until the trip is changed. */
        val fixedLine: List<LatLon>? = null,
        val saved: SavedRef? = null,
        /** A loop made into this trip once found (its stops are points along it): Shuffle plans another. */
        val loop: Roundtrip? = null,
        /** An adventure made into this trip once found (its stops are points along it): Shuffle plans another. */
        val adventure: Adventure? = null
    ) : NavigationGoal {
        override val title get() = name
    }

    data class Roundtrip(
        val distanceKm: Double,
        val directionDeg: Double,
        override val options: RouteOptions,
        val variant: Int = 0,
        val name: String = "",
        /** An area drawn on the map to ride in (instead of the direction and distance); null = not drawn. */
        val area: List<LatLon>? = null,
        /** Starting somewhere else than where the rider is; null = from here. */
        val start: Stop? = null,
        /** A drawn area's loop no longer than this; null = the way round the whole area. */
        val maxKm: Int? = null
    ) : NavigationGoal {
        override val title get() = name
    }

    /**
     * To [destination] by the most fun roads inside [area], taking about [minutes] of estimated
     * riding time (null = no limit, the area decides): not the quickest way there. Ridden as a fixed line once started.
     */
    data class Adventure(
        val name: String,
        val destination: LatLon,
        val area: List<LatLon>,
        val minutes: Int?,
        override val options: RouteOptions,
        val variant: Int = 0,
        /** Starting somewhere else than where the rider is; null = from here. */
        val start: Stop? = null
    ) : NavigationGoal {
        override val title get() = name
    }

    /** A fixed line to ride: an imported GPX track, a saved roundtrip or a route with an added stop. */
    data class Track(val name: String, val points: List<LatLon>, override val options: RouteOptions) : NavigationGoal {
        override val title get() = name
    }
}

/** One option shown in the preview; [style] is null for loops and tracks, which have only one. */
data class RouteCandidate(val style: RoadStyle?, val route: Route? = null, val error: String? = null)

data class RoutePreview(
    val goal: NavigationGoal,
    val candidates: List<RouteCandidate>,
    val selected: Int,
    /** There is an earlier version of the trip to go back to. */
    val canUndo: Boolean = false,
    /** Routing data being fetched for this trip first, e.g. "Fetching routing data (1 of 2)…". */
    val fetching: String? = null,
    /** An adventure is still being worked on in the background: a better trip may come. */
    val improving: Boolean = false,
    /** The adventures found so far, each better than the one before; the rider switches between them. */
    val suggestions: List<Route> = emptyList(),
    /** Which of [suggestions] the trip on the map was made from. */
    val suggestion: Int = 0
) {
    val selectedRoute get() = candidates.getOrNull(selected)?.route
    val calculating get() = candidates.any { it.route == null && it.error == null }
}

data class NavigationState(
    val goal: NavigationGoal? = null,
    val route: Route? = null,
    val progress: NavigationProgress? = null,
    val status: RoutingStatus = RoutingStatus.Idle,
    val preview: RoutePreview? = null,
    /** Distance along [route] of each remaining stop, for drawing and for dropping passed stops. */
    val stopsAlong: List<Pair<Stop, Double>> = emptyList(),
    /** Off the route with no quick way back: the rider is being asked what to do. */
    val wrongTurn: WrongTurn? = null,
    /**
     * Where the route starts while the rider still has to get there: their own road isn't joined
     * to the road network in the map data (e.g. it only touches a cycle path), so the route
     * begins on the nearest road that is. Not "off route" until they have been on it.
     */
    val approach: LatLon? = null
) {
    val isNavigating get() = route != null
}

/**
 * Owns routing: previews (several styles side by side), the active route, following the rider
 * along it, stops, and rerouting.
 */
class Navigator(private val context: Context, private val scope: CoroutineScope) {

    val routingData = RoutingData(context)
    private val profileDir = File(context.filesDir, "brouter-profiles")

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var tracker: NavigationTracker? = null
    private var calcJob: Job? = null
    private var previewJob: Job? = null
    /** An adventure being planned: it goes on after the first trip is shown, looking for a better one. */
    private var adventureJob: Job? = null
    /** The goals that the adventure being planned is for: the adventure and the trip made of its first result. Planning stops when the screen shows anything else. */
    @Volatile private var watched: Set<NavigationGoal> = emptySet()
    /** The adventures the planner has found for the one on screen, in the order found (guarded by `this`). */
    private val suggestions = ArrayList<Route>()
    @Volatile private var adventureRunning = false
    private var lastPosition: LatLon? = null
    private var lastHeading: Double? = null
    /** Where the rider was ~20 m ago, for a heading from how they actually moved. */
    private var headingAnchor: LatLon? = null
    private var lastRerouteAt = 0L
    /** After "carry on", leaving the route again before this time re-plans without asking. */
    private var followUntil = 0L
    private var arrivalJob: Job? = null
    /** How far the route started from the rider, while they haven't reached it yet (see [NavigationState.approach]). */
    private var approachGap = 0.0

    /** A new route: does it start a little way from the rider? */
    private fun approachTo(route: Route): LatLon? {
        val pos = lastPosition ?: return null
        val first = route.points.firstOrNull()?.let { LatLon(it.lat, it.lon) } ?: return null
        val gap = Geo.distance(pos, first)
        approachGap = gap
        return first.takeIf { gap > offRouteSettings.offRouteMeters && gap < MAX_APPROACH_M }
    }

    /** Style the rider picked last time; the preview pre-selects it. */
    var preferredStyle: RoadStyle = RoadStyle.CURVY

    var offRouteSettings = OffRouteSettings()

    private val engine by lazy { BRouterEngine(routingData.segmentsDir, ProfileInstaller.install(profileDir)) }
    private val offRoute by lazy { OffRoutePlanner(engine) }
    private val surfaces by lazy { com.curveon.routing.SurfaceReader(routingData.segmentsDir, java.io.File(ProfileInstaller.install(profileDir), "lookups.dat")) }

    /** Gravel and softer roads in the area, from the routing data (the map tiles don't know surfaces). Blocking. */
    fun surfaceWays(south: Double, west: Double, north: Double, east: Double) = surfaces.ways(south, west, north, east)

    // Its own reader: counting a whole file would otherwise keep the map's dashes waiting on the shared one.
    private val heat by lazy {
        com.curveon.routing.GravelHeat(routingData.segmentsDir, File(context.filesDir, "gravel-heat"),
            com.curveon.routing.SurfaceReader(routingData.segmentsDir, File(ProfileInstaller.install(profileDir), "lookups.dat")))
    }

    /** The routing data files covering the area, for the gravel heat map (any country that is on the device). */
    fun gravelHeatTiles(south: Double, west: Double, north: Double, east: Double) = heat.tiles(south, west, north, east)

    /** How much gravel each part of one routing data file has; counted once (seconds), then kept. Blocking. */
    fun gravelHeat(tile: String) = heat.cells(tile)

    // ------------------------------------------------------------------ preview

    fun previewDestination(name: String, destination: LatLon, options: RouteOptions, stops: List<Stop> = emptyList(), start: Stop? = null) =
        preview(NavigationGoal.Destination(name, destination, options, stops, start))

    /** A saved A→B route: its stops stay editable, and it rides its saved line until changed. */
    fun previewPlanned(goal: NavigationGoal.Destination) = preview(goal)

    /** A trip that was being planned when Curveon was last ended, waiting for where the rider is. */
    private var pendingPreview: Pair<NavigationGoal, Int>? = null

    /** Open the preview of a trip planned before Curveon was ended, as soon as it can be planned. */
    fun restorePreview(goal: NavigationGoal, selected: Int) {
        val ownStart = when (goal) {
            is NavigationGoal.Destination -> goal.start != null
            is NavigationGoal.Roundtrip -> goal.start != null
            is NavigationGoal.Adventure -> goal.start != null
            is NavigationGoal.Track -> false
        }
        if (lastPosition == null && !ownStart) pendingPreview = goal to selected
        else preview(goal, keepSelection = selected)
    }

    /** Earlier versions of the previewed trip, newest last, for Undo. */
    private val previewHistory = ArrayList<NavigationGoal.Destination>()

    /** Change the previewed A→B trip (remembering the old one for Undo). It no longer follows a saved line. */
    private fun editPreview(change: (NavigationGoal.Destination) -> NavigationGoal.Destination?) {
        val p = _state.value.preview ?: return
        val g = p.goal as? NavigationGoal.Destination ?: return
        val changed = change(g)?.copy(fixedLine = null) ?: return
        previewHistory += g
        if (previewHistory.size > 30) previewHistory.removeAt(0)
        // A saved line had one option; changed, it gets all three, starting with the style it was saved with.
        val selected = if (g.fixedLine != null) RoadStyle.entries.indexOf(g.options.roadStyle) else p.selected
        preview(changed, selected, keepHistory = true)
    }

    /** Back to the trip as it was before the last change. */
    fun undoPreviewEdit() {
        val p = _state.value.preview ?: return
        val last = previewHistory.removeLastOrNull() ?: return
        preview(last, p.selected, keepHistory = true)
    }

    /** Plan the previewed trip from [start] instead of from here (null = from here again). */
    fun setPreviewStart(start: Stop?) {
        // A loop from somewhere else is another loop.
        val goal = _state.value.preview?.goal
        val adventure = goal as? NavigationGoal.Adventure ?: (goal as? NavigationGoal.Destination)?.adventure
        if (adventure != null) return preview(adventure.copy(start = start, options = goal!!.options))
        val loop = goal as? NavigationGoal.Roundtrip ?: (goal as? NavigationGoal.Destination)?.loop
        if (loop != null) preview(loop.copy(start = start, options = goal!!.options)) else editPreview { it.copy(start = start) }
    }

    /** Make [stop] the previewed trip's destination, keeping its stops and start. */
    fun setPreviewDestination(stop: Stop) = editPreview { it.copy(name = stop.name, destination = stop.point) }

    /** Move a previewed stop one place earlier ([delta] -1) or later (+1). */
    fun movePreviewStop(index: Int, delta: Int) = editPreview { g ->
        val to = index + delta
        if (index !in g.stops.indices || to !in g.stops.indices) null
        else g.copy(stops = g.stops.toMutableList().also { java.util.Collections.swap(it, index, to) })
    }

    /** Put [stop] in the previewed trip at [index] (0 = first), e.g. "add stop before" or a point dragged on the map. */
    fun insertPreviewStop(index: Int, stop: Stop) = editPreview { g ->
        g.copy(stops = g.stops.toMutableList().also { it.add(index.coerceIn(0, g.stops.size), stop) })
    }

    /**
     * Put several stops in the previewed trip in one change (one Undo), each at its index in the
     * trip as it is now, e.g. both ends of a part of the route marked for changing.
     */
    fun insertPreviewStops(stops: List<Pair<Int, Stop>>) = editPreview { g ->
        val list = g.stops.toMutableList()
        // Later ones first, so the earlier indexes still point where they did.
        stops.withIndex().sortedWith(compareByDescending<IndexedValue<Pair<Int, Stop>>> { it.value.first }.thenByDescending { it.index })
            .forEach { (_, s) -> list.add(s.first.coerceIn(0, list.size), s.second) }
        g.copy(stops = list)
    }

    /**
     * Ride on from the previewed trip's end to [next]: the old destination becomes a point the route
     * goes through (or a real stop, [keepEndAsStop]: a place the trip was planned to), [next] the new
     * destination (building a trip point by point on the map).
     */
    fun extendPreview(next: Stop, keepEndAsStop: Boolean = false) = editPreview { g ->
        g.copy(stops = g.stops + Stop(g.name, g.destination, via = !keepEndAsStop), name = next.name, destination = next.point)
    }

    /** Replace the previewed stop at [index] with [stop] (a wrong place moved or picked again). */
    fun replacePreviewStop(index: Int, stop: Stop) = editPreview { g ->
        if (index !in g.stops.indices) null else g.copy(stops = g.stops.toMutableList().also { it[index] = stop })
    }

    fun previewRoundtrip(distanceKm: Double, directionDeg: Double, options: RouteOptions, start: Stop? = null) =
        preview(NavigationGoal.Roundtrip(distanceKm, directionDeg, options, name = context.getString(R.string.roundtrip), start = start))

    /** A loop inside [area], drawn on the map with a finger. */
    fun previewAreaRoundtrip(area: List<LatLon>, options: RouteOptions, start: Stop? = null, maxKm: Int? = null) =
        preview(NavigationGoal.Roundtrip(0.0, 0.0, options, name = context.getString(R.string.roundtrip), area = area, start = start, maxKm = maxKm))

    /** An adventure to [destination] inside [area], taking about [minutes] of estimated riding time (null = no limit). */
    fun previewAdventure(name: String, destination: LatLon, area: List<LatLon>, minutes: Int?, options: RouteOptions, start: Stop? = null) =
        preview(NavigationGoal.Adventure(name, destination, area, minutes, options, start = start))

    /** The adventure on screen: being worked out, or found and made into an ordinary trip. */
    private fun previewedAdventure(): NavigationGoal.Adventure? {
        val p = _state.value.preview ?: return null
        val adventure = p.goal as? NavigationGoal.Adventure ?: (p.goal as? NavigationGoal.Destination)?.adventure ?: return null
        // As planned, on the road style picked now (Fastest still finds the adventure on curvy roads) and the options changed since.
        val style = p.candidates.getOrNull(p.selected)?.style?.takeIf { it != RoadStyle.FASTEST } ?: adventure.options.roadStyle
        return adventure.copy(options = p.goal.options.copy(roadStyle = style))
    }

    /** The previewed adventure again taking [minutes] (null = no limit). */
    fun setAdventureMinutes(minutes: Int?) {
        val g = previewedAdventure() ?: return
        preview(g.copy(minutes = minutes, variant = 0))
    }

    /** The previewed loop again with another max length (null = the whole area), on the road style picked now. */
    fun setLoopMax(maxKm: Int?) = replanLoop { it.copy(maxKm = maxKm, variant = 0) }

    /**
     * Plans the previewed loop again, changed by [change]: with the trip's current options, and the
     * road style picked in the sheet (Fastest still finds the loop on curvy roads).
     */
    private fun replanLoop(change: (NavigationGoal.Roundtrip) -> NavigationGoal.Roundtrip) {
        val p = _state.value.preview ?: return
        val loop = p.goal as? NavigationGoal.Roundtrip ?: (p.goal as? NavigationGoal.Destination)?.loop ?: return
        val style = p.candidates.getOrNull(p.selected)?.style?.takeIf { it != RoadStyle.FASTEST } ?: loop.options.roadStyle
        preview(change(loop.copy(options = p.goal.options.copy(roadStyle = style))))
    }

    /** Names places (for the via points of a loop made into a trip); set by whoever has the search. */
    var describePlace: suspend (LatLon) -> String = { "" }

    /**
     * A loop as soon as it is found becomes an ordinary trip, to change like one (stops, road style,
     * on the map): points along it become via points (about one every 8 km), ridden back to where
     * it starts. It remembers the loop, so Shuffle can plan another.
     */
    private suspend fun tripFromLoop(g: NavigationGoal.Roundtrip, route: Route) {
        val first = LatLon(route.points.first().lat, route.points.first().lon)
        val home = g.start ?: Stop(describePlace(first), first)
        val count = (route.distanceMeters / LOOP_VIA_SPACING_M).toInt().coerceIn(4, 12)
        var next = 0
        val points = buildList {
            for (pt in route.points) if (next < count && pt.distanceFromStart >= route.distanceMeters * (next + 1) / (count + 1)) {
                add(LatLon(pt.lat, pt.lon)); next++
            }
        }
        val vias = points.map { Stop(describePlace(it), it, via = true) }
        // Still the same loop on screen?
        if (_state.value.preview?.goal != g) return
        previewHistory.clear()
        // The loop's road style stays picked.
        val style = RoadStyle.entries.indexOf(g.options.roadStyle)
        preview(NavigationGoal.Destination(home.name, home.point, g.options, vias, g.start, loop = g), keepSelection = style, keepHistory = true)
        // The loop stays on the map until its own style's route through the points is in.
        synchronized(this) {
            val p = _state.value.preview ?: return
            if (p.candidates[style].route == null && p.candidates[style].error == null) _state.value = _state.value.copy(
                preview = p.copy(candidates = p.candidates.toMutableList().also { it[style] = RouteCandidate(g.options.roadStyle, route) }))
        }
    }

    fun previewTrack(name: String, points: List<LatLon>, options: RouteOptions) =
        preview(NavigationGoal.Track(name, points, options))

    /**
     * Start navigating along a track straight away (no preview): used when the rider is already
     * riding it. Joins the track where the rider is.
     */
    fun followTrackNow(name: String, points: List<LatLon>, options: RouteOptions) {
        val from = lastPosition ?: return
        val tiles = tilesAround(points + from)
        if (!canRoute(tiles)) return fail(routingData.describe())
        calcJob?.cancel()
        _state.value = _state.value.copy(status = RoutingStatus.Calculating(context.getString(R.string.nav_following, name)), preview = null)
        val goal = NavigationGoal.Track(name, points, options)
        calcJob = scope.launch {
            try {
                fetchRoutingData(tiles)
                _state.value = _state.value.copy(status = RoutingStatus.Calculating(context.getString(R.string.nav_following, name)))
                val route = withContext(Dispatchers.Default) { TrackFollower(engine).route(from, points, options) }
                activate(goal, route)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = RoutingStatus.Failed(context.getString(R.string.nav_follow_failed, name, message(e))))
            }
        }
    }

    /** Ride the previewed track the other way round. */
    fun reversePreviewTrack() {
        val g = _state.value.preview?.goal as? NavigationGoal.Track ?: return
        preview(g.copy(points = g.points.reversed()))
    }

    /**
     * The previewed track (a GPX track or a ride) made into an ordinary trip, to change like one
     * (stops, road style, dragging it on the map) and keep as a route or a GPX track: it starts and ends
     * where the track does, with via points along it so the route keeps to its roads.
     */
    fun trackToTrip() {
        val g = _state.value.preview?.goal as? NavigationGoal.Track ?: return
        convertTrack(g, fromPreview = true)
    }

    /** A saved GPX track made into an ordinary trip straight from the list, without previewing it as a track first. */
    fun gpxToTrip(name: String, points: List<LatLon>, options: RouteOptions) =
        convertTrack(NavigationGoal.Track(name, points, options), fromPreview = false)

    private fun convertTrack(g: NavigationGoal.Track, fromPreview: Boolean) {
        if (g.points.size < 2) return
        scope.launch {
            val converting = context.getString(R.string.nav_converting, g.name)
            // The status card is hidden under the trip sheet, so from there the sheet shows the progress.
            _state.value = _state.value.let { s ->
                if (fromPreview && s.preview != null) s.copy(preview = s.preview.copy(fetching = converting))
                else s.copy(status = RoutingStatus.Calculating(converting))
            }
            val first = g.points.first()
            val last = g.points.last()
            val tiles = tilesAround(g.points)
            // Points where the track leaves what the router would choose, so side roads it took are kept.
            val along = if (canRoute(tiles)) {
                try {
                    fetchRoutingData(tiles)
                    withContext(Dispatchers.Default) { TrackFollower(engine).viaPoints(g.points, g.options) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pointsAlong(g.points, TRACK_VIA_SPACING_M)
                }
            } else pointsAlong(g.points, TRACK_VIA_SPACING_M)
            // Hundreds of points: named side by side.
            val vias = kotlinx.coroutines.coroutineScope { along.map { p -> async { Stop(describePlace(p), p, via = true) } }.awaitAll() }
            val start = Stop(describePlace(first), first)
            // Still the same track on screen?
            if (fromPreview && _state.value.preview?.goal != g) return@launch
            previewHistory.clear()
            val style = RoadStyle.entries.indexOf(g.options.roadStyle)
            val shown = if (fromPreview) _state.value.preview?.selectedRoute else null
            preview(NavigationGoal.Destination(g.name, last, g.options, vias, start), keepSelection = style, keepHistory = true)
            // The track's line stays on the map until the route through the points is in.
            if (shown != null) synchronized(this@Navigator) {
                val now = _state.value.preview ?: return@launch
                if (now.candidates[style].route == null && now.candidates[style].error == null) _state.value = _state.value.copy(
                    preview = now.copy(candidates = now.candidates.toMutableList().also { it[style] = RouteCandidate(g.options.roadStyle, shown) }))
            }
        }
    }

    /** Another loop in the same direction and length. */
    fun shuffleRoundtrip() {
        val g = previewedAdventure()
        if (g != null) preview(g.copy(variant = g.variant + 1)) else replanLoop { it.copy(variant = it.variant + 1) }
    }

    fun updatePreviewOptions(transform: (RouteOptions) -> RouteOptions) {
        val p = _state.value.preview ?: return
        preview(
            when (val g = p.goal) {
                is NavigationGoal.Destination -> g.copy(options = transform(g.options))
                is NavigationGoal.Roundtrip -> g.copy(options = transform(g.options))
                is NavigationGoal.Adventure -> g.copy(options = transform(g.options))
                is NavigationGoal.Track -> g.copy(options = transform(g.options))
            },
            keepSelection = p.selected, keepHistory = true
        )
    }

    fun selectCandidate(index: Int) {
        val p = _state.value.preview ?: return
        _state.value = _state.value.copy(preview = p.copy(selected = index))
    }

    /** Drop the previewed trip's destination: the last stop becomes the destination (none left: nothing to drop). */
    fun removePreviewDestination() = editPreview { g ->
        val last = g.stops.lastOrNull() ?: return@editPreview null
        g.copy(stops = g.stops.dropLast(1), name = last.name, destination = last.point)
    }

    fun removePreviewStop(index: Int) = editPreview { g ->
        if (index !in g.stops.indices) null else g.copy(stops = g.stops.filterIndexed { i, _ -> i != index })
    }

    fun cancelPreview() {
        pendingPreview = null
        previewJob?.cancel()
        adventureJob?.cancel()
        _state.value = _state.value.copy(preview = null, status = RoutingStatus.Idle)
    }

    /** Ride the selected preview candidate. */
    fun startPreview() {
        val p = _state.value.preview ?: return
        val candidate = p.candidates.getOrNull(p.selected) ?: return
        val route = candidate.route ?: return
        candidate.style?.let { preferredStyle = it }
        val planned = p.goal as? NavigationGoal.Destination
        (p.goal as? NavigationGoal.Roundtrip)?.takeIf { it.start != null }?.let { loop ->
            // A loop from elsewhere: ride to it and round it.
            return followTrackNow(loop.title, route.points.map { LatLon(it.lat, it.lon) }, loop.options)
        }
        (p.goal as? NavigationGoal.Adventure)?.let { adventure ->
            // Ridden as a fixed line, from elsewhere after riding to it.
            val line = route.points.map { LatLon(it.lat, it.lon) }
            return if (adventure.start != null) followTrackNow(adventure.title, line, adventure.options)
            else activate(NavigationGoal.Track(adventure.title, line, adventure.options), route)
        }
        // An unchanged saved route: ride its line, rejoining it after a wrong turn as before.
        if (planned?.fixedLine != null) return activate(NavigationGoal.Track(planned.saved?.title ?: planned.name, planned.fixedLine, planned.options), route)
        if (planned?.start != null) {
            // Planned from elsewhere: ride to the planned line and along it.
            return followTrackNow(planned.name, route.points.map { LatLon(it.lat, it.lon) },
                planned.options.copy(roadStyle = candidate.style ?: planned.options.roadStyle))
        }
        val goal = when (val g = p.goal) {
            is NavigationGoal.Destination -> g.copy(options = g.options.copy(roadStyle = candidate.style ?: g.options.roadStyle))
            else -> g
        }
        activate(goal, route)
    }

    private fun preview(goal: NavigationGoal, keepSelection: Int? = null, keepHistory: Boolean = false, keepAdventure: Boolean = false) {
        pendingPreview = null
        if (!keepHistory) previewHistory.clear()
        val planned = goal as? NavigationGoal.Destination
        // A saved line is joined from where the rider is, like a track.
        val from = (if (planned?.fixedLine != null) lastPosition ?: planned.start?.point
            else planned?.start?.point ?: (goal as? NavigationGoal.Roundtrip)?.start?.point ?: (goal as? NavigationGoal.Adventure)?.start?.point ?: lastPosition)
            ?: return fail(context.getString(R.string.nav_waiting_gps))
        val tiles = tilesAround(goalPoints(goal, from))
        if (!canRoute(tiles)) return fail(routingData.describe())
        previewJob?.cancel()
        if (!keepAdventure) adventureJob?.cancel()
        val styles: List<RoadStyle?> = if (planned != null && planned.fixedLine == null) RoadStyle.entries else listOf(null)
        val selected = (keepSelection ?: styles.indexOf(preferredStyle)).coerceIn(0, styles.lastIndex)
        _state.value = _state.value.copy(
            preview = RoutePreview(goal, styles.map { RouteCandidate(it) }, selected, canUndo = previewHistory.isNotEmpty()),
            status = RoutingStatus.Idle
        )
        previewJob = scope.launch {
            // Planning online somewhere no country was downloaded: fetch just the routing data first.
            try { fetchRoutingData(tiles) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Curveon", "Fetching routing data failed", e)
            }
            // An adventure is planned in a job of its own: it goes on looking for a better trip once this one is shown.
            if (goal is NavigationGoal.Adventure) return@launch startAdventure(goal, from)
            styles.mapIndexed { i, style ->
                async(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    val result = runCatching { calculate(goal, from, style) { !isActive } }
                    android.util.Log.i("Curveon", "Routed ${goal::class.simpleName} ${style ?: ""} in %.1f s: %s".format(
                        (System.nanoTime() - t0) / 1e9,
                        result.fold({ "%.0f km, %d ferries, %.0f m unpaved".format(it.distanceMeters / 1000, it.ferryCount, it.unpavedMeters) }, { "failed: ${it.message}" })))
                    synchronized(this@Navigator) {
                        val p = _state.value.preview ?: return@async
                        if (p.goal != goal) return@async
                        val updated = p.candidates.toMutableList().also {
                            it[i] = RouteCandidate(style, result.getOrNull(), result.exceptionOrNull()?.let(::message))
                        }
                        _state.value = _state.value.copy(preview = p.copy(candidates = updated, fetching = if (goal is NavigationGoal.Adventure) null else p.fetching))
                    }
                }
            }.awaitAll()
            // A loop found: on as an ordinary trip.
            val found = _state.value.preview?.takeIf { it.goal == goal }?.selectedRoute
            if (goal is NavigationGoal.Roundtrip && found != null) return@launch tripFromLoop(goal, found)
            // If the pre-selected style failed, fall back to one that worked.
            val p = _state.value.preview ?: return@launch
            if (p.selectedRoute == null) p.candidates.indexOfFirst { it.route != null }.takeIf { it >= 0 }?.let { selectCandidate(it) }
        }
    }

    /** "Finding roads… 12 of 48" in the sheet while an adventure is worked out. */
    private fun adventureProgress(goal: NavigationGoal, done: Int, total: Int) = synchronized(this) {
        val p = _state.value.preview?.takeIf { it.goal == goal && it.selectedRoute == null } ?: return
        _state.value = _state.value.copy(preview = p.copy(fetching = context.getString(R.string.nav_adventure_progress, done, total)))
    }

    // ------------------------------------------------------------------ routing data on demand

    /**
     * An adventure as soon as it is found becomes an ordinary trip like a loop does: points along it
     * become via points (about one every [ADVENTURE_VIA_SPACING_M]), so the rider can change it by
     * dragging it on the map, adding stops and so on. It remembers the adventure, so Shuffle, the
     * time and drawing the area again plan another.
     */
    private suspend fun tripFromAdventure(
        g: NavigationGoal.Adventure, route: Route,
        /** What must still be on screen for this to apply. */
        expected: NavigationGoal = g,
        /** Which of the suggestions this is, for the switcher in the sheet. */
        index: Int = 0
    ): NavigationGoal.Destination? {
        val count = (route.distanceMeters / ADVENTURE_VIA_SPACING_M).toInt().coerceIn(4, 16)
        var next = 0
        val points = buildList {
            // Never on a ferry: a stop out at sea is a ride out and back.
            for (pt in route.points) if (next < count && !pt.ferry && pt.distanceFromStart >= route.distanceMeters * (next + 1) / (count + 1)) {
                add(LatLon(pt.lat, pt.lon)); next++
            }
        }
        val vias = points.map { Stop(describePlace(it), it, via = true) }
        // Still the same trip on screen?
        if (_state.value.preview?.goal != expected) return null
        previewHistory.clear()
        val style = RoadStyle.entries.indexOf(g.options.roadStyle)
        val trip = NavigationGoal.Destination(g.name, g.destination, g.options, vias, g.start, adventure = g)
        watched = watched + trip
        preview(trip, keepSelection = style, keepHistory = true, keepAdventure = true)
        // The adventure stays on the map until its own style's route through the points is in.
        synchronized(this) {
            val p = _state.value.preview ?: return trip
            val candidates = if (p.candidates[style].route == null && p.candidates[style].error == null)
                p.candidates.toMutableList().also { it[style] = RouteCandidate(g.options.roadStyle, route) } else p.candidates
            _state.value = _state.value.copy(preview = p.copy(
                candidates = candidates, suggestions = suggestions.toList(), suggestion = index, improving = adventureRunning))
        }
        return trip
    }

    /**
     * Plans an adventure in rounds (see [AdventurePlanner.plan]): the first trip is shown as soon as it is
     * found and made an ordinary trip, and a better one found later is kept beside it ([RoutePreview.suggestions]),
     * for the rider to switch between. Planning stops when the rider changes or leaves the trip.
     */
    private fun startAdventure(goal: NavigationGoal.Adventure, from: LatLon) {
        adventureJob?.cancel()
        watched = setOf(goal)
        synchronized(this) { suggestions.clear() }
        adventureRunning = true
        adventureJob = scope.launch {
            val routes = kotlinx.coroutines.channels.Channel<Route>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val t0 = System.nanoTime()
            val planning = async(Dispatchers.Default) {
                try {
                    AdventurePlanner(engine, { android.util.Log.d("CurveonPlan", it) }, { done, total -> adventureProgress(goal, done, total) },
                        { !isActive || _state.value.preview?.goal !in watched }, surfaces)
                        .plan(from, goal.destination, goal.area, goal.minutes, goal.options, goal.variant, rounds = ADVENTURE_ROUNDS) { routes.trySend(it) }
                } finally {
                    routes.close()
                }
            }
            var first = true
            for (route in routes) {
                if (first) {
                    first = false
                    android.util.Log.i("Curveon", "First adventure in %.1f s: %.0f km".format((System.nanoTime() - t0) / 1e9, route.distanceMeters / 1000))
                    synchronized(this@Navigator) {
                        val p = _state.value.preview?.takeIf { it.goal == goal } ?: return@synchronized
                        _state.value = _state.value.copy(preview = p.copy(candidates = listOf(RouteCandidate(null, route)), fetching = null))
                    }
                    synchronized(this@Navigator) { suggestions += route }
                    tripFromAdventure(goal, route)
                } else synchronized(this@Navigator) {
                    // A better one: kept beside the others and drawn grey on the map, for the rider to switch to.
                    suggestions += route
                    val p = _state.value.preview?.takeIf { it.goal in watched } ?: return@synchronized
                    _state.value = _state.value.copy(preview = p.copy(suggestions = suggestions.toList()))
                }
            }
            adventureRunning = false
            val failure = try { planning.await(); null } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e }
            synchronized(this@Navigator) {
                val p = _state.value.preview ?: return@synchronized
                if (first && failure != null && p.goal == goal) {
                    android.util.Log.i("Curveon", "Adventure failed: ${failure.message}")
                    _state.value = _state.value.copy(preview = p.copy(candidates = listOf(RouteCandidate(null, null, message(failure))), fetching = null))
                } else if (p.improving && p.goal in watched) _state.value = _state.value.copy(preview = p.copy(improving = false))
            }
        }
    }

    /** Show another of the adventures found for this trip (the first stays, to go back to). */
    fun selectAdventureSuggestion(index: Int) {
        val p = _state.value.preview ?: return
        val route = p.suggestions.getOrNull(index) ?: return
        if (index == p.suggestion) return
        val g = previewedAdventure() ?: return
        scope.launch { tripFromAdventure(g, route, expected = p.goal, index = index) }
    }

    /** Where a trip may go: its places, or for a loop the circle it can reach. */
    private fun goalPoints(goal: NavigationGoal, from: LatLon): List<LatLon> = when (goal) {
        is NavigationGoal.Destination -> listOf(from) + listOfNotNull(goal.start?.point) + goal.stops.map { it.point } + goal.destination + goal.fixedLine.orEmpty()
        is NavigationGoal.Track -> goal.points + from
        is NavigationGoal.Adventure -> goal.area + from + goal.destination
        is NavigationGoal.Roundtrip -> if (goal.area != null) goal.area + from else {
            val r = goal.distanceKm / (2 * Math.PI) * 1.4 / 111.0 // degrees of latitude
            val rLon = r / kotlin.math.cos(Math.toRadians(from.lat)).coerceAtLeast(0.2)
            listOf(LatLon(from.lat - r, from.lon - rLon), LatLon(from.lat + r, from.lon + rLon))
        }
    }

    private fun tilesAround(points: List<LatLon>): List<String> =
        RoutingData.tilesFor(points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon })

    /** Routing data is here for [tiles], or can be fetched (online), or at least something is installed to try with. */
    private fun canRoute(tiles: List<String>) = routingData.isReady() || (routingData.toFetch(tiles).isNotEmpty() && routingData.online())

    /** Fetches the missing routing tiles when online, showing it as the status meanwhile. */
    private suspend fun fetchRoutingData(tiles: List<String>) {
        if (routingData.toFetch(tiles).isEmpty() || !routingData.online()) return
        try {
            routingData.fetch(tiles) { done, total ->
                val text = context.getString(R.string.nav_fetching_routing, done + 1, total)
                val s = _state.value
                // In a preview the sheet says it; otherwise the status line does.
                _state.value = if (s.preview != null) s.copy(preview = s.preview.copy(fetching = text)) else s.copy(status = RoutingStatus.Calculating(text))
            }
        } finally {
            val s = _state.value
            _state.value = if (s.preview != null) s.copy(preview = s.preview.copy(fetching = null)) else s.copy(status = RoutingStatus.Idle)
        }
    }

    private fun calculate(goal: NavigationGoal, from: LatLon, style: RoadStyle?, cancelled: () -> Boolean = { false }): Route = when (goal) {
        is NavigationGoal.Destination -> if (goal.fixedLine != null) TrackFollower(engine).route(from, goal.fixedLine, goal.options, fromStart = true) else engine.route(
            listOf(from) + goal.stops.map { it.point } + goal.destination,
            goal.options.copy(roadStyle = style ?: goal.options.roadStyle),
            visitEveryVia = true
        )
        is NavigationGoal.Roundtrip -> RoundtripPlanner(engine) { android.util.Log.d("CurveonPlan", it) }.let { planner ->
            if (goal.area != null) planner.planArea(from, goal.area, goal.options, goal.variant, goal.maxKm?.toDouble())
            else planner.plan(
                from, goal.distanceKm, goal.directionDeg, goal.options,
                rotationsDeg = listOf(0.0, -30.0, 30.0).map { it + goal.variant * 17.0 }
            )
        }
        is NavigationGoal.Track -> TrackFollower(engine).route(from, goal.points, goal.options, fromStart = true)
        is NavigationGoal.Adventure -> AdventurePlanner(engine, { android.util.Log.d("CurveonPlan", it) }, { done, total -> adventureProgress(goal, done, total) }, cancelled, surfaces)
            .plan(from, goal.destination, goal.area, goal.minutes, goal.options, goal.variant)
    }

    // ------------------------------------------------------------------ navigation

    fun stop() {
        pendingPreview = null
        calcJob?.cancel()
        previewJob?.cancel()
        adventureJob?.cancel()
        arrivalJob?.cancel()
        arrivalJob = null
        tracker = null
        _state.value = NavigationState()
    }

    fun dismissError() {
        if (_state.value.status is RoutingStatus.Failed) _state.value = _state.value.copy(status = RoutingStatus.Idle)
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(status = RoutingStatus.Failed(message))
    }

    private fun activate(goal: NavigationGoal, route: Route) {
        previewJob?.cancel()
        adventureJob?.cancel()
        followUntil = 0L
        arrivalJob?.cancel()
        arrivalJob = null
        tracker = newTracker(route)
        val pos = lastPosition
        _state.value = NavigationState(
            goal = goal,
            route = route,
            progress = pos?.let { tracker!!.update(it) },
            stopsAlong = stopsAlong(goal, route),
            approach = approachTo(route)
        )
    }

    private fun stopsAlong(goal: NavigationGoal, route: Route): List<Pair<Stop, Double>> {
        val stops = (goal as? NavigationGoal.Destination)?.stops ?: return emptyList()
        return stops.zip(alongDistances(stops, route))
    }

    /** Which way the rider is moving (from the last ~20 m ridden), null until known. */
    fun heading(): Double? = lastHeading

    private fun newTracker(route: Route) = NavigationTracker(route, offRouteSettings.offRouteMeters.toDouble())

    fun onLocation(position: LatLon, accuracyMeters: Double, headingDeg: Double? = null) {
        lastPosition = position
        // Heading from the last ~20 m ridden; the GPS bearing only until we have that (some
        // receivers, and the emulator, report a bearing of 0 when they don't know).
        val anchor = headingAnchor
        if (anchor == null || Geo.distance(anchor, position) > 200) headingAnchor = position
        else if (Geo.distance(anchor, position) >= HEADING_BASE_M) {
            lastHeading = Geo.bearing(anchor.lat, anchor.lon, position.lat, position.lon)
            headingAnchor = position
        }
        if (lastHeading == null && headingDeg != null) lastHeading = headingDeg
        pendingPreview?.let { (goal, selected) -> if (tracker == null) preview(goal, keepSelection = selected) }
        val t = tracker ?: return
        val progress = t.update(position, accuracyMeters)
        var current = _state.value.copy(progress = progress)
        // Getting to where the route starts: done once on it; gone elsewhere, it's off route as usual.
        current.approach?.let { a ->
            if (progress.distanceFromRoute <= offRouteSettings.offRouteMeters || Geo.distance(position, a) > approachGap + APPROACH_SLACK_M)
                current = current.copy(approach = null)
        }

        // Drop stops we have ridden past, so a reroute doesn't send us back to them.
        val passed = current.stopsAlong.filter { it.second < progress.distanceAlongRoute - 50 }
        if (passed.isNotEmpty()) {
            val g = current.goal as? NavigationGoal.Destination
            current = current.copy(
                goal = g?.copy(stops = g.stops - passed.map { it.first }.toSet()) ?: current.goal,
                stopsAlong = current.stopsAlong - passed.toSet()
            )
        }
        _state.value = current

        val asking = current.wrongTurn
        when {
            progress.arrived && current.goal !is NavigationGoal.Roundtrip ->
                if (arrivalJob == null) arrivalJob = scope.launch { delay(15_000); if (tracker === t) stop() }
            // Back on the line while we were asking: nothing to decide any more.
            asking != null && !progress.offRoute -> _state.value = current.copy(wrongTurn = null)
            // Not answered and still riding away: carry on the way the rider is going.
            asking != null -> if (System.currentTimeMillis() >= asking.autoAtMillis && calcJob?.isActive != true &&
                Geo.distance(asking.askedAt, position) > AUTO_AFTER_RIDING_M) {
                if (asking.isTrip) newRouteFromHere(keepStops = true) else rejoinAhead()
            }
            progress.offRoute && current.approach == null -> maybeReroute(position)
        }
    }

    /**
     * Adds a stop while riding (or while previewing). On an A→B trip it goes where it adds the
     * least detour; on a loop or track we ride to the stop and then rejoin the line after it.
     */
    fun addStop(stop: Stop) {
        val s = _state.value
        // For the navigation trace: every add stop is written down with where it is and what became of it.
        fun trace(what: String) = com.curveon.data.location.NavTrace.event("addStop '%s' %.5f %.5f: %s".format(stop.name, stop.point.lat, stop.point.lon, what))
        val previewGoal = s.preview?.goal
        if (previewGoal is NavigationGoal.Destination) {
            return editPreview { g -> g.copy(stops = insertStop(g.start?.point ?: lastPosition, g.stops, g.destination, stop)) }
        }
        val goal = s.goal ?: return previewDestination(stop.name, stop.point, RouteOptions(preferredStyle)).also { trace("no goal, planning a trip instead") }
        val route = s.route ?: return trace("no route, nothing done")
        val pos = lastPosition ?: return trace("no position yet, nothing done")
        trace("started, ${(goal as? NavigationGoal.Destination)?.stops?.size ?: -1} stops before, route ${route.points.size} points, calculating was ${calcJob?.isActive}")
        calcJob?.cancel()
        _state.value = s.copy(status = RoutingStatus.Calculating(context.getString(R.string.nav_adding_stop, stop.name)))
        calcJob = scope.launch {
            try {
                val (newGoal, newRoute) = withContext(Dispatchers.Default) {
                    when (goal) {
                        is NavigationGoal.Destination -> {
                            val g = goal.copy(stops = insertStop(pos, goal.stops, goal.destination, stop))
                            g to routeFromHere(pos, g.stops.map { it.point } + g.destination, g.options)
                        }
                        else -> {
                            val toStop = engine.route(listOf(pos, stop.point), goal.options)
                            val along = RouteCorridor(route).alongDistance(stop.point.lat, stop.point.lon, 3_000.0,
                                s.progress?.distanceAlongRoute ?: 0.0) ?: (s.progress?.distanceAlongRoute ?: 0.0)
                            val rest = rejoin(stop.point, route, along, goal.options)
                            val combined = Routes.concat(toStop, rest)
                            NavigationGoal.Track(goal.title, combined.points.map { LatLon(it.lat, it.lon) }, goal.options) to combined
                        }
                    }
                }
                trace("done, ${(newGoal as? NavigationGoal.Destination)?.stops?.size ?: -1} stops, new route ${newRoute.points.size} points (old ${route.points.size})")
                activate(newGoal, newRoute)
            } catch (e: kotlinx.coroutines.CancellationException) {
                trace("cancelled")
                throw e
            } catch (e: Exception) {
                trace("failed: ${e.message}")
                _state.value = _state.value.copy(status = RoutingStatus.Failed(context.getString(R.string.nav_add_stop_failed, stop.name, message(e))))
            }
        }
    }

    /** Insert where the straight-line detour is smallest. Good enough for a handful of stops. */
    private fun insertStop(from: LatLon?, stops: List<Stop>, destination: LatLon, stop: Stop): List<Stop> {
        val chain = listOfNotNull(from) + stops.map { it.point } + destination
        if (chain.size < 2) return stops + stop
        val offset = if (from != null) 1 else 0
        val best = (0 until chain.size - 1).minBy { i ->
            Geo.distance(chain[i], stop.point) + Geo.distance(stop.point, chain[i + 1]) - Geo.distance(chain[i], chain[i + 1])
        }
        return stops.toMutableList().also { it.add((best + 1 - offset).coerceIn(0, stops.size), stop) }
    }

    /**
     * Left the route. If there is a way back that keeps riding the way the rider is going and costs
     * little more than turning round (Settings → Navigation), take it quietly. Otherwise ask
     * ([WrongTurn]): turn back, or carry on with a new route.
     */
    private fun maybeReroute(position: LatLon) {
        val now = System.currentTimeMillis()
        val current = _state.value
        if (calcJob?.isActive == true || now - lastRerouteAt < REROUTE_COOLDOWN_MS) return
        val goal = current.goal ?: return
        val route = current.route ?: return
        val heading = lastHeading
        val leftAlong = current.progress?.distanceAlongRoute ?: 0.0
        lastRerouteAt = now
        _state.value = current.copy(status = RoutingStatus.Calculating(context.getString(R.string.nav_recalculating)))
        calcJob = scope.launch {
            try {
                val s = offRouteSettings
                val trip = (goal as? NavigationGoal.Destination)?.let { g -> g.stops.map { it.point } + g.destination }
                // The rider chose to carry on a moment ago (following someone, own way): no more questions.
                val following = now < followUntil && heading != null
                val decision = withContext(Dispatchers.Default) {
                    if (following) offRoute.decide(position, heading, route, leftAlong, goal.options, Double.MAX_VALUE, Double.MAX_VALUE, trip)
                    else offRoute.decide(position, heading, route, leftAlong, goal.options, s.maxExtraMinutes * 60.0, s.maxExtraPercent / 100.0, trip)
                }
                android.util.Log.i("CurveonNav", "Off route at %.0f m, heading %s: %s".format(leftAlong, heading?.let { "%.0f".format(it) } ?: "none", describe(decision)))
                when (decision) {
                    is OffRouteDecision.Rejoin -> adopt(position, decision.way.route)
                    is OffRouteDecision.Ask -> _state.value = _state.value.copy(
                        status = RoutingStatus.Idle,
                        wrongTurn = WrongTurn(
                            isTrip = goal is NavigationGoal.Destination,
                            stopsLeft = (goal as? NavigationGoal.Destination)?.stops?.size ?: 0,
                            leftAlong = leftAlong,
                            aheadExtraMinutes = decision.ahead?.let { ((it.totalSeconds - decision.back.totalSeconds) / 60).roundToInt().coerceAtLeast(0) },
                            autoAtMillis = System.currentTimeMillis() + WRONG_TURN_AUTO_MS,
                            askedAt = position
                        )
                    )
                    OffRouteDecision.Replan -> adopt(position, when (goal) {
                        // Nothing found from the route's point of view: re-plan as before.
                        is NavigationGoal.Destination -> routeFromHere(position, goal.stops.map { it.point } + goal.destination, goal.options)
                        else -> rejoin(position, route, leftAlong, goal.options)
                    })
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the old route on screen; we will try again after the cooldown.
                _state.value = _state.value.copy(status = RoutingStatus.Idle)
            }
        }
    }

    private fun describe(d: OffRouteDecision) = when (d) {
        is OffRouteDecision.Rejoin -> "rejoin at %.0f m (+%.0f s)".format(d.way.joinAlong, d.extraSeconds)
        is OffRouteDecision.Ask -> "ask (ahead ${d.ahead?.let { "+%.0f s".format(it.totalSeconds - d.back.totalSeconds) } ?: "none"})"
        OffRouteDecision.Replan -> "replan"
    }

    /** Wrong-turn answer: back to where the route was left (turning round). */
    fun turnBack() = answerWrongTurn(R.string.nav_turning_back) { pos, goal, route, wt ->
        goal to offRoute.back(pos, route, wt.leftAlong, goal.options).route
    }

    /** Wrong-turn answer on a trip: a new route to the destination that starts the way the rider is going. */
    fun newRouteFromHere(keepStops: Boolean) = answerWrongTurn(R.string.nav_recalculating, carryOn = true) { pos, goal, route, wt ->
        val g = goal as? NavigationGoal.Destination ?: return@answerWrongTurn goal to rejoin(pos, route, wt.leftAlong, goal.options)
        val newGoal = if (keepStops) g else g.copy(stops = emptyList())
        val waypoints = newGoal.stops.map { it.point } + newGoal.destination
        val heading = lastHeading
        newGoal to (if (heading != null) offRoute.fromHere(pos, heading, waypoints, g.options) else engine.route(listOf(pos) + waypoints, g.options, visitEveryVia = true))
    }

    /** Wrong-turn answer on a loop or track: join it again further ahead without turning round. */
    fun rejoinAhead() = answerWrongTurn(R.string.nav_recalculating, carryOn = true) { pos, goal, route, wt ->
        val heading = lastHeading
        goal to ((if (heading != null) offRoute.ahead(pos, heading, route, wt.leftAlong, goal.options)?.route else null)
            ?: rejoin(pos, route, wt.leftAlong, goal.options))
    }

    private fun answerWrongTurn(message: Int, carryOn: Boolean = false, plan: (LatLon, NavigationGoal, Route, WrongTurn) -> Pair<NavigationGoal, Route>) {
        val s = _state.value
        val wt = s.wrongTurn ?: return
        followUntil = if (carryOn) System.currentTimeMillis() + FOLLOW_MS else 0L
        val goal = s.goal ?: return
        val route = s.route ?: return
        val pos = lastPosition ?: return
        calcJob?.cancel()
        _state.value = s.copy(wrongTurn = null, status = RoutingStatus.Calculating(context.getString(message)))
        calcJob = scope.launch {
            try {
                val (newGoal, newRoute) = withContext(Dispatchers.Default) { plan(pos, goal, route, wt) }
                lastRerouteAt = System.currentTimeMillis()
                _state.value = _state.value.copy(goal = newGoal)
                adopt(pos, newRoute)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = RoutingStatus.Failed(context.getString(R.string.nav_reroute_failed, message(e))))
            }
        }
    }

    /** Ride [newRoute] from now on, keeping the goal. */
    private fun adopt(position: LatLon, newRoute: Route) {
        tracker = newTracker(newRoute)
        _state.value = _state.value.copy(
            route = newRoute,
            progress = tracker!!.update(position),
            status = RoutingStatus.Idle,
            wrongTurn = null,
            stopsAlong = stopsAlong(_state.value.goal ?: return, newRoute),
            approach = approachTo(newRoute)
        )
    }

    /**
     * A new route from where the rider is through [waypoints], carrying on the way they are going
     * unless turning round is clearly quicker (more than the quiet-rejoin slack in Settings).
     */
    private fun routeFromHere(pos: LatLon, waypoints: List<LatLon>, options: RouteOptions): Route {
        val free = engine.route(listOf(pos) + waypoints, options, visitEveryVia = true)
        val heading = lastHeading ?: return free
        if (!OffRoutePlanner.turnsBack(free, pos, heading) && !OffRoutePlanner.uTurnsAhead(free)) return free
        val ahead = offRoute.forwardOnly(pos, heading, waypoints, options).second ?: return free
        val s = offRouteSettings
        val slack = maxOf(OffRoutePlanner.ALWAYS_QUICK_S, minOf(s.maxExtraMinutes * 60.0, s.maxExtraPercent / 100.0 * free.durationSeconds))
        return if (ahead.durationSeconds - free.durationSeconds <= slack) ahead else free
    }

    /** Drop a stop while riding an A→B trip and re-plan from here. */
    fun removeStop(index: Int) {
        val s = _state.value
        val g = s.goal as? NavigationGoal.Destination ?: return
        if (index !in g.stops.indices) return
        val pos = lastPosition ?: return
        val newGoal = g.copy(stops = g.stops.filterIndexed { i, _ -> i != index })
        calcJob?.cancel()
        _state.value = s.copy(status = RoutingStatus.Calculating(context.getString(R.string.nav_recalculating)))
        calcJob = scope.launch {
            try {
                val r = withContext(Dispatchers.Default) {
                    routeFromHere(pos, newGoal.stops.map { it.point } + newGoal.destination, newGoal.options)
                }
                activate(newGoal, r)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = RoutingStatus.Failed(context.getString(R.string.nav_reroute_failed, message(e))))
            }
        }
    }

    private fun rejoin(position: LatLon, route: Route, along: Double, options: RouteOptions): Route {
        val target = along + REJOIN_AHEAD_M
        val remaining = route.points.filter { it.distanceFromStart >= target }
        if (remaining.size < 2) return engine.route(listOf(position, route.points.last().let { LatLon(it.lat, it.lon) }), options)
        // Route to the rejoin point, then through the rest of the line to keep its shape.
        return TrackFollower(engine).route(position, remaining.map { LatLon(it.lat, it.lon) }, options)
    }

    /**
     * Speed limit of the road the rider is on when not navigating: route a few metres ahead along
     * the current heading and read the first segment's maxspeed. Null when unknown/unsigned.
     */
    /**
     * What the routing data knows of the road nearest [point] (within ~40 m): its class, surface and
     * OSM speed limit, as the first stretch of a few metres' route from there. Null if none.
     */
    suspend fun roadAt(point: LatLon): com.curveon.routing.RoutePoint? = withContext(Dispatchers.Default) {
        if (!routingData.isReady()) return@withContext null
        runCatching {
            val r = engine.route(listOf(point, Geo.destination(point, 90.0, 20.0)),
                RouteOptions(RoadStyle.FASTEST, com.curveon.routing.SurfacePreference.MIXED), viaCatchingRangeMeters = 40.0)
            val first = r.points.firstOrNull() ?: return@runCatching null
            if (Geo.distance(first.lat, first.lon, point.lat, point.lon) > 45) null
            else r.points.drop(1).firstOrNull { it.highway != null }
        }.getOrNull()
    }

    suspend fun probeSpeedLimit(position: LatLon, headingDeg: Double): Int? = withContext(Dispatchers.Default) {
        if (!routingData.isReady()) return@withContext null
        val ahead = Geo.destination(position, headingDeg, 60.0)
        runCatching {
            engine.route(listOf(position, ahead), RouteOptions(RoadStyle.FASTEST), viaCatchingRangeMeters = 25.0)
                .points.drop(1).firstNotNullOfOrNull { it.maxSpeedKmh }
        }.getOrNull()
    }

    /**
     * Not navigating: the way the road the rider is on goes for the next ~[aheadM] metres, as a
     * short route towards a point straight ahead (the router keeps to roads). Null when unknown.
     */
    suspend fun probeAhead(position: LatLon, headingDeg: Double, aheadM: Double = 150.0): Route? = withContext(Dispatchers.Default) {
        if (!routingData.isReady()) return@withContext null
        runCatching {
            engine.route(listOf(position, Geo.destination(position, headingDeg, aheadM)), RouteOptions(RoadStyle.FASTEST), viaCatchingRangeMeters = 80.0)
                .takeIf { r -> r.points.firstOrNull()?.let { Geo.distance(it.lat, it.lon, position.lat, position.lon) < 30 } == true }
        }.getOrNull()
    }

    private fun message(e: Throwable): String = when {
        e is NoAdventureException -> e.directMinutes?.let { context.getString(R.string.nav_adventure_too_short, it) } ?: context.getString(R.string.nav_no_adventure)
        e is NoRoundtripException -> context.getString(if (e.inArea) R.string.nav_no_area_roundtrip else R.string.nav_no_roundtrip)
        e.message?.contains("not mapped", true) == true || e.message?.contains("no track", true) == true ->
            context.getString(R.string.nav_no_road)
        else -> e.message ?: e.toString()
    }

    companion object {
        private const val REROUTE_COOLDOWN_MS = 10_000L
        /** A loop made into a trip gets about one via point this far apart (close enough to keep its shape). */
        private const val LOOP_VIA_SPACING_M = 8_000.0
        /** A track made into a trip gets about one via point this far apart: close enough to keep to its roads. */
        private const val TRACK_VIA_SPACING_M = 5_000.0
        private const val TRACK_VIA_MAX = 30

        /**
         * Points along [line], evenly spaced by length about [spacingM] apart (never the ends, never fewer
         * than 2 or more than [TRACK_VIA_MAX]); none when the line is too short to need any.
         */
        internal fun pointsAlong(line: List<LatLon>, spacingM: Double): List<LatLon> {
            val along = DoubleArray(line.size)
            for (i in 1 until line.size) along[i] = along[i - 1] +
                com.curveon.data.location.TripTracker.distanceMeters(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
            val total = along.last()
            if (total < spacingM) return emptyList()
            val count = (total / spacingM).toInt().coerceIn(2, TRACK_VIA_MAX)
            var next = 0
            return buildList {
                for (i in line.indices) while (next < count && along[i] >= total * (next + 1) / (count + 1)) { add(line[i]); next++ }
            }
        }

        /** An adventure made into a trip gets about one via point this far apart: far enough to keep the roads it was made of, close enough to keep its shape. */
        private const val ADVENTURE_VIA_SPACING_M = 15_000.0
        /** An adventure is planned in this many rounds: the first answers quickly, the others look for a better one. */
        private const val ADVENTURE_ROUNDS = 3
        /** A route starting further away than this isn't "your road missing from the map" any more. */
        private const val MAX_APPROACH_M = 1_500.0
        /** Ridden this much further from the route's start than you began: you went elsewhere. */
        private const val APPROACH_SLACK_M = 200.0
        private const val HEADING_BASE_M = 20.0
        /** Unanswered question: carry on by itself only once the rider has ridden this far on. */
        private const val AUTO_AFTER_RIDING_M = 150.0
        /** Unanswered wrong-turn question: carry on by itself after this long. */
        const val WRONG_TURN_AUTO_MS = 20_000L
        /** How long after "carry on" further wrong turns are taken without asking. */
        private const val FOLLOW_MS = 5 * 60_000L
        private const val REJOIN_AHEAD_M = 1_500.0
    }
}
