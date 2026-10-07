package com.curveon.data.routing

import android.content.Context
import com.curveon.routing.BRouterEngine
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.NavigationProgress
import com.curveon.routing.NavigationTracker
import com.curveon.routing.ProfileInstaller
import com.curveon.routing.RoadStyle
import com.curveon.routing.RoundtripPlanner
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

sealed interface RoutingStatus {
    data object Idle : RoutingStatus
    data class Calculating(val message: String) : RoutingStatus
    data class Failed(val message: String) : RoutingStatus
}

data class Stop(val name: String, val point: LatLon)

/** What the rider asked for, kept so we can recalculate after leaving the route. */
sealed interface NavigationGoal {
    val options: RouteOptions
    val title: String

    /** A→B, visiting [stops] in order on the way. */
    data class Destination(
        val name: String,
        val destination: LatLon,
        override val options: RouteOptions,
        val stops: List<Stop> = emptyList()
    ) : NavigationGoal {
        override val title get() = name
    }

    data class Roundtrip(
        val distanceKm: Double,
        val directionDeg: Double,
        override val options: RouteOptions,
        val variant: Int = 0
    ) : NavigationGoal {
        override val title get() = "Roundtrip"
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
    val selected: Int
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
    val stopsAlong: List<Pair<Stop, Double>> = emptyList()
) {
    val isNavigating get() = route != null
}

/**
 * Owns routing: previews (several styles side by side), the active route, following the rider
 * along it, stops, and rerouting.
 */
class Navigator(context: Context, private val scope: CoroutineScope) {

    val routingData = RoutingData(context)
    private val profileDir = File(context.filesDir, "brouter-profiles")

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var tracker: NavigationTracker? = null
    private var calcJob: Job? = null
    private var previewJob: Job? = null
    private var lastPosition: LatLon? = null
    private var lastRerouteAt = 0L
    private var arrivalJob: Job? = null

    /** Style the rider picked last time; the preview pre-selects it. */
    var preferredStyle: RoadStyle = RoadStyle.CURVY

    private val engine by lazy { BRouterEngine(routingData.segmentsDir, ProfileInstaller.install(profileDir)) }

    // ------------------------------------------------------------------ preview

    fun previewDestination(name: String, destination: LatLon, options: RouteOptions, stops: List<Stop> = emptyList()) =
        preview(NavigationGoal.Destination(name, destination, options, stops))

    fun previewRoundtrip(distanceKm: Double, directionDeg: Double, options: RouteOptions, variant: Int = 0) =
        preview(NavigationGoal.Roundtrip(distanceKm, directionDeg, options, variant))

    fun previewTrack(name: String, points: List<LatLon>, options: RouteOptions) =
        preview(NavigationGoal.Track(name, points, options))

    /**
     * Start navigating along a track straight away (no preview): used when the rider is already
     * riding it. Joins the track where the rider is.
     */
    fun followTrackNow(name: String, points: List<LatLon>, options: RouteOptions) {
        val from = lastPosition ?: return
        if (!routingData.isReady()) return fail(routingData.describe())
        calcJob?.cancel()
        _state.value = _state.value.copy(status = RoutingStatus.Calculating("Following $name…"), preview = null)
        val goal = NavigationGoal.Track(name, points, options)
        calcJob = scope.launch {
            try {
                val route = withContext(Dispatchers.Default) { TrackFollower(engine).route(from, points, options) }
                activate(goal, route)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = RoutingStatus.Failed("Couldn't follow $name: ${message(e)}"))
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
            keepSelection = p.selected
        )
    }

    fun selectCandidate(index: Int) {
        val p = _state.value.preview ?: return
        _state.value = _state.value.copy(preview = p.copy(selected = index))
    }

    fun removePreviewStop(index: Int) {
        val g = _state.value.preview?.goal as? NavigationGoal.Destination ?: return
        preview(g.copy(stops = g.stops.filterIndexed { i, _ -> i != index }), keepSelection = _state.value.preview?.selected)
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
        val goal = when (val g = p.goal) {
            is NavigationGoal.Destination -> g.copy(options = g.options.copy(roadStyle = candidate.style ?: g.options.roadStyle))
            else -> g
        }
        activate(goal, route)
    }

    private fun preview(goal: NavigationGoal, keepSelection: Int? = null) {
        val from = lastPosition ?: return fail("Waiting for GPS position")
        if (!routingData.isReady()) return fail(routingData.describe())
        previewJob?.cancel()
        val styles: List<RoadStyle?> = if (goal is NavigationGoal.Destination) RoadStyle.entries else listOf(null)
        val selected = keepSelection ?: styles.indexOf(preferredStyle).coerceAtLeast(0)
        _state.value = _state.value.copy(
            preview = RoutePreview(goal, styles.map { RouteCandidate(it) }, selected),
            status = RoutingStatus.Idle
        )
        previewJob = scope.launch {
            styles.mapIndexed { i, style ->
                async(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    val result = runCatching { calculate(goal, from, style) }
                    android.util.Log.i("Curveon", "Routed ${goal::class.simpleName} ${style ?: ""} in %.1f s: %s".format(
                        (System.nanoTime() - t0) / 1e9,
                        result.fold({ "%.0f km, %d ferries".format(it.distanceMeters / 1000, it.ferryCount) }, { "failed: ${it.message}" })))
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

    private fun calculate(goal: NavigationGoal, from: LatLon, style: RoadStyle?): Route = when (goal) {
        is NavigationGoal.Destination -> engine.route(
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
        arrivalJob?.cancel()
        arrivalJob = null
        tracker = NavigationTracker(route)
        val pos = lastPosition
        _state.value = NavigationState(
            goal = goal,
            route = route,
            progress = pos?.let { tracker!!.update(it) },
            stopsAlong = stopsAlong(goal, route)
        )
    }

    private fun stopsAlong(goal: NavigationGoal, route: Route): List<Pair<Stop, Double>> {
        val stops = (goal as? NavigationGoal.Destination)?.stops ?: return emptyList()
        val corridor = RouteCorridor(route)
        var after = 0.0
        return stops.map { s ->
            val along = corridor.alongDistance(s.point.lat, s.point.lon, 2_000.0, after) ?: after
            after = along
            s to along
        }
    }

    fun onLocation(position: LatLon, accuracyMeters: Double) {
        lastPosition = position
        val t = tracker ?: return
        val progress = t.update(position, accuracyMeters)
        var current = _state.value.copy(progress = progress)

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

        if (progress.arrived && current.goal !is NavigationGoal.Roundtrip) {
            if (arrivalJob == null) arrivalJob = scope.launch { delay(15_000); if (tracker === t) stop() }
        } else if (progress.offRoute) {
            maybeReroute(position)
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
            return preview(previewGoal.copy(stops = insertStop(lastPosition, previewGoal.stops, previewGoal.destination, stop)), s.preview.selected)
        }
        val goal = s.goal ?: return previewDestination(stop.name, stop.point, RouteOptions(preferredStyle))
        val route = s.route ?: return
        val pos = lastPosition ?: return
        calcJob?.cancel()
        _state.value = s.copy(status = RoutingStatus.Calculating("Adding ${stop.name}…"))
        calcJob = scope.launch {
            try {
                val (newGoal, newRoute) = withContext(Dispatchers.Default) {
                    when (goal) {
                        is NavigationGoal.Destination -> {
                            val g = goal.copy(stops = insertStop(pos, goal.stops, goal.destination, stop))
                            g to engine.route(listOf(pos) + g.stops.map { it.point } + g.destination, g.options)
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
                _state.value = _state.value.copy(status = RoutingStatus.Failed("Couldn't add ${stop.name}: ${message(e)}"))
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

    private fun maybeReroute(position: LatLon) {
        val now = System.currentTimeMillis()
        val current = _state.value
        if (calcJob?.isActive == true || now - lastRerouteAt < REROUTE_COOLDOWN_MS) return
        val goal = current.goal ?: return
        val route = current.route ?: return
        lastRerouteAt = now
        _state.value = current.copy(status = RoutingStatus.Calculating("Recalculating…"))
        calcJob = scope.launch {
            try {
                val newRoute = withContext(Dispatchers.Default) {
                    when (goal) {
                        is NavigationGoal.Destination ->
                            engine.route(listOf(position) + goal.stops.map { it.point } + goal.destination, goal.options)
                        // For a loop or track, ride back onto it a little further ahead instead of re-planning.
                        is NavigationGoal.Roundtrip, is NavigationGoal.Track ->
                            rejoin(position, route, current.progress?.distanceAlongRoute ?: 0.0, goal.options)
                    }
                }
                tracker = NavigationTracker(newRoute)
                _state.value = _state.value.copy(
                    route = newRoute,
                    progress = tracker!!.update(position),
                    status = RoutingStatus.Idle,
                    stopsAlong = stopsAlong(_state.value.goal ?: goal, newRoute)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the old route on screen; we will try again after the cooldown.
                _state.value = _state.value.copy(status = RoutingStatus.Idle)
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
        e.message?.contains("not mapped", true) == true || e.message?.contains("no track", true) == true ->
            "No road found there"
        else -> e.message ?: e.toString()
    }

    companion object {
        private const val REROUTE_COOLDOWN_MS = 10_000L
        private const val REJOIN_AHEAD_M = 1_500.0
    }
}
