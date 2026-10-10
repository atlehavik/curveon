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
import com.curveon.routing.NoRoundtripException
import com.curveon.R
import com.curveon.routing.Route
import com.curveon.routing.RouteCorridor
import com.curveon.routing.RouteOptions
import com.curveon.routing.Routes
import com.curveon.routing.TrackFollower
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
        val saved: SavedRef? = null
    ) : NavigationGoal {
        override val title get() = name
    }

    data class Roundtrip(
        val distanceKm: Double,
        val directionDeg: Double,
        override val options: RouteOptions,
        val variant: Int = 0,
        val name: String = ""
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
    val fetching: String? = null
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

    // ------------------------------------------------------------------ preview

    fun previewDestination(name: String, destination: LatLon, options: RouteOptions, stops: List<Stop> = emptyList(), start: Stop? = null) =
        preview(NavigationGoal.Destination(name, destination, options, stops, start))

    /** A saved A→B route: its stops stay editable, and it rides its saved line until changed. */
    fun previewPlanned(goal: NavigationGoal.Destination) = preview(goal)

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
    fun setPreviewStart(start: Stop?) = editPreview { it.copy(start = start) }

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
     * goes through, [next] the new destination (building a trip point by point on the map).
     */
    fun extendPreview(next: Stop) = editPreview { g ->
        g.copy(stops = g.stops + Stop(g.name, g.destination, via = true), name = next.name, destination = next.point)
    }

    /** Replace the previewed stop at [index] with [stop] (a wrong place moved or picked again). */
    fun replacePreviewStop(index: Int, stop: Stop) = editPreview { g ->
        if (index !in g.stops.indices) null else g.copy(stops = g.stops.toMutableList().also { it[index] = stop })
    }

    fun previewRoundtrip(distanceKm: Double, directionDeg: Double, options: RouteOptions, variant: Int = 0) =
        preview(NavigationGoal.Roundtrip(distanceKm, directionDeg, options, variant, context.getString(R.string.roundtrip)))

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

    /** Another loop in the same direction and length. */
    fun shuffleRoundtrip() {
        val g = _state.value.preview?.goal as? NavigationGoal.Roundtrip ?: return
        preview(g.copy(variant = g.variant + 1))
    }

    fun updatePreviewOptions(transform: (RouteOptions) -> RouteOptions) {
        val p = _state.value.preview ?: return
        preview(
            when (val g = p.goal) {
                is NavigationGoal.Destination -> g.copy(options = transform(g.options))
                is NavigationGoal.Roundtrip -> g.copy(options = transform(g.options))
                is NavigationGoal.Track -> g.copy(options = transform(g.options))
            },
            keepSelection = p.selected, keepHistory = true
        )
    }

    fun selectCandidate(index: Int) {
        val p = _state.value.preview ?: return
        _state.value = _state.value.copy(preview = p.copy(selected = index))
    }

    fun removePreviewStop(index: Int) = editPreview { g ->
        if (index !in g.stops.indices) null else g.copy(stops = g.stops.filterIndexed { i, _ -> i != index })
    }

    fun cancelPreview() {
        previewJob?.cancel()
        _state.value = _state.value.copy(preview = null, status = RoutingStatus.Idle)
    }

    /** Ride the selected preview candidate. */
    fun startPreview() {
        val p = _state.value.preview ?: return
        val candidate = p.candidates.getOrNull(p.selected) ?: return
        val route = candidate.route ?: return
        candidate.style?.let { preferredStyle = it }
        val planned = p.goal as? NavigationGoal.Destination
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

    private fun preview(goal: NavigationGoal, keepSelection: Int? = null, keepHistory: Boolean = false) {
        if (!keepHistory) previewHistory.clear()
        val planned = goal as? NavigationGoal.Destination
        // A saved line is joined from where the rider is, like a track.
        val from = (if (planned?.fixedLine != null) lastPosition ?: planned.start?.point else planned?.start?.point ?: lastPosition)
            ?: return fail(context.getString(R.string.nav_waiting_gps))
        val tiles = tilesAround(goalPoints(goal, from))
        if (!canRoute(tiles)) return fail(routingData.describe())
        previewJob?.cancel()
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
            styles.mapIndexed { i, style ->
                async(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    val result = runCatching { calculate(goal, from, style) }
                    android.util.Log.i("Curveon", "Routed ${goal::class.simpleName} ${style ?: ""} in %.1f s: %s".format(
                        (System.nanoTime() - t0) / 1e9,
                        result.fold({ "%.0f km, %d ferries, %.0f m unpaved".format(it.distanceMeters / 1000, it.ferryCount, it.unpavedMeters) }, { "failed: ${it.message}" })))
                    synchronized(this@Navigator) {
                        val p = _state.value.preview ?: return@async
                        if (p.goal != goal) return@async
                        val updated = p.candidates.toMutableList().also {
                            it[i] = RouteCandidate(style, result.getOrNull(), result.exceptionOrNull()?.let(::message))
                        }
                        _state.value = _state.value.copy(preview = p.copy(candidates = updated))
                    }
                }
            }.awaitAll()
            // If the pre-selected style failed, fall back to one that worked.
            val p = _state.value.preview ?: return@launch
            if (p.selectedRoute == null) p.candidates.indexOfFirst { it.route != null }.takeIf { it >= 0 }?.let { selectCandidate(it) }
        }
    }

    // ------------------------------------------------------------------ routing data on demand

    /** Where a trip may go: its places, or for a loop the circle it can reach. */
    private fun goalPoints(goal: NavigationGoal, from: LatLon): List<LatLon> = when (goal) {
        is NavigationGoal.Destination -> listOf(from) + listOfNotNull(goal.start?.point) + goal.stops.map { it.point } + goal.destination + goal.fixedLine.orEmpty()
        is NavigationGoal.Track -> goal.points + from
        is NavigationGoal.Roundtrip -> {
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

    private fun calculate(goal: NavigationGoal, from: LatLon, style: RoadStyle?): Route = when (goal) {
        is NavigationGoal.Destination -> if (goal.fixedLine != null) TrackFollower(engine).route(from, goal.fixedLine, goal.options) else engine.route(
            listOf(from) + goal.stops.map { it.point } + goal.destination,
            goal.options.copy(roadStyle = style ?: goal.options.roadStyle)
        )
        is NavigationGoal.Roundtrip -> RoundtripPlanner(engine) { android.util.Log.d("CurveonPlan", it) }.plan(
            from, goal.distanceKm, goal.directionDeg, goal.options,
            rotationsDeg = listOf(0.0, -30.0, 30.0).map { it + goal.variant * 17.0 }
        )
        is NavigationGoal.Track -> TrackFollower(engine).route(from, goal.points, goal.options)
    }

    // ------------------------------------------------------------------ navigation

    fun stop() {
        calcJob?.cancel()
        previewJob?.cancel()
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
        val previewGoal = s.preview?.goal
        if (previewGoal is NavigationGoal.Destination) {
            return editPreview { g -> g.copy(stops = insertStop(g.start?.point ?: lastPosition, g.stops, g.destination, stop)) }
        }
        val goal = s.goal ?: return previewDestination(stop.name, stop.point, RouteOptions(preferredStyle))
        val route = s.route ?: return
        val pos = lastPosition ?: return
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
                activate(newGoal, newRoute)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
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
        newGoal to (if (heading != null) offRoute.fromHere(pos, heading, waypoints, g.options) else engine.route(listOf(pos) + waypoints, g.options))
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
        val free = engine.route(listOf(pos) + waypoints, options)
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
    suspend fun probeSpeedLimit(position: LatLon, headingDeg: Double): Int? = withContext(Dispatchers.Default) {
        if (!routingData.isReady()) return@withContext null
        val ahead = Geo.destination(position, headingDeg, 60.0)
        runCatching {
            engine.route(listOf(position, ahead), RouteOptions(RoadStyle.FASTEST), viaCatchingRangeMeters = 25.0)
                .points.drop(1).firstNotNullOfOrNull { it.maxSpeedKmh }
        }.getOrNull()
    }

    private fun message(e: Throwable): String = when {
        e is NoRoundtripException -> context.getString(R.string.nav_no_roundtrip)
        e.message?.contains("not mapped", true) == true || e.message?.contains("no track", true) == true ->
            context.getString(R.string.nav_no_road)
        else -> e.message ?: e.toString()
    }

    companion object {
        private const val REROUTE_COOLDOWN_MS = 10_000L
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
