package com.vibenav.data.routing

import android.content.Context
import com.vibenav.routing.BRouterEngine
import com.vibenav.routing.LatLon
import com.vibenav.routing.NavigationProgress
import com.vibenav.routing.NavigationTracker
import com.vibenav.routing.ProfileInstaller
import com.vibenav.routing.RoundtripPlanner
import com.vibenav.routing.Route
import com.vibenav.routing.RouteOptions
import com.vibenav.routing.TrackFollower
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** What the rider asked for, kept so we can recalculate after leaving the route. */
sealed interface NavigationGoal {
    val options: RouteOptions

    data class Destination(val name: String, val destination: LatLon, override val options: RouteOptions) : NavigationGoal

    data class Roundtrip(val distanceKm: Double, val directionDeg: Double, override val options: RouteOptions) : NavigationGoal

    data class Track(val name: String, val points: List<LatLon>, override val options: RouteOptions) : NavigationGoal
}

data class NavigationState(
    val goal: NavigationGoal? = null,
    val route: Route? = null,
    val progress: NavigationProgress? = null,
    val status: RoutingStatus = RoutingStatus.Idle
) {
    val isNavigating get() = route != null
}

/** Owns the active route: calculates it, follows the rider along it and reroutes when needed. */
class Navigator(context: Context, private val scope: CoroutineScope) {

    val routingData = RoutingData(context)
    private val profileDir = File(context.filesDir, "brouter-profiles")

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var tracker: NavigationTracker? = null
    private var calcJob: Job? = null
    private var lastPosition: LatLon? = null
    private var lastRerouteAt = 0L
    private var arrivalJob: Job? = null

    private val engine by lazy { BRouterEngine(routingData.segmentsDir, ProfileInstaller.install(profileDir)) }

    fun navigateTo(name: String, destination: LatLon, options: RouteOptions) =
        start(NavigationGoal.Destination(name, destination, options), "Calculating route to $name…")

    fun roundtrip(distanceKm: Double, directionDeg: Double, options: RouteOptions) =
        start(NavigationGoal.Roundtrip(distanceKm, directionDeg, options), "Planning a %.0f km roundtrip…".format(distanceKm))

    fun followTrack(name: String, points: List<LatLon>, options: RouteOptions) =
        start(NavigationGoal.Track(name, points, options), "Preparing $name…")

    fun stop() {
        calcJob?.cancel()
        arrivalJob?.cancel()
        arrivalJob = null
        tracker = null
        _state.value = NavigationState()
    }

    fun dismissError() {
        if (_state.value.status is RoutingStatus.Failed) _state.value = _state.value.copy(status = RoutingStatus.Idle)
    }

    fun onLocation(position: LatLon, accuracyMeters: Double) {
        lastPosition = position
        val t = tracker ?: return
        val progress = t.update(position, accuracyMeters)
        _state.value = _state.value.copy(progress = progress)
        if (progress.arrived && _state.value.goal !is NavigationGoal.Roundtrip) {
            // Keep the arrival banner briefly, then clear.
            if (arrivalJob == null) arrivalJob = scope.launch { kotlinx.coroutines.delay(15_000); if (tracker === t) stop() }
        } else if (progress.offRoute) {
            maybeReroute(position)
        }
    }

    private fun start(goal: NavigationGoal, message: String) {
        val from = lastPosition ?: run {
            _state.value = _state.value.copy(status = RoutingStatus.Failed("Waiting for GPS position"))
            return
        }
        if (!routingData.isReady()) {
            _state.value = _state.value.copy(status = RoutingStatus.Failed(routingData.describe()))
            return
        }
        calcJob?.cancel()
        _state.value = _state.value.copy(status = RoutingStatus.Calculating(message))
        calcJob = scope.launch {
            try {
                val route = calculate(goal, from)
                arrivalJob?.cancel()
                arrivalJob = null
                tracker = NavigationTracker(route)
                _state.value = NavigationState(goal = goal, route = route, progress = tracker!!.update(from))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = RoutingStatus.Failed(e.message ?: e.toString()))
            }
        }
    }

    private suspend fun calculate(goal: NavigationGoal, from: LatLon): Route = withContext(Dispatchers.Default) {
        when (goal) {
            is NavigationGoal.Destination -> engine.route(listOf(from, goal.destination), goal.options)
            is NavigationGoal.Roundtrip -> RoundtripPlanner(engine).plan(from, goal.distanceKm, goal.directionDeg, goal.options)
            is NavigationGoal.Track -> TrackFollower(engine).route(from, goal.points, goal.options)
        }
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
                        is NavigationGoal.Destination -> engine.route(listOf(position, goal.destination), goal.options)
                        // For a loop, ride back onto the planned loop a little further ahead instead of re-planning it.
                        is NavigationGoal.Roundtrip, is NavigationGoal.Track ->
                            rejoin(position, route, current.progress?.distanceAlongRoute ?: 0.0, goal.options)
                    }
                }
                tracker = NavigationTracker(newRoute)
                _state.value = NavigationState(goal = goal, route = newRoute, progress = tracker!!.update(position))
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
        // Route to the rejoin point, then through a few points of the rest of the loop to keep its shape.
        val step = (remaining.size / 4).coerceAtLeast(1)
        val vias = remaining.filterIndexed { i, _ -> i % step == 0 }.map { LatLon(it.lat, it.lon) } +
            LatLon(remaining.last().lat, remaining.last().lon)
        return engine.route(listOf(position) + vias.distinct(), options)
    }

    companion object {
        private const val REROUTE_COOLDOWN_MS = 10_000L
        private const val REJOIN_AHEAD_M = 1_500.0
    }
}
