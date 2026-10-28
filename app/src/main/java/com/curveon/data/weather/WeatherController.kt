package com.curveon.data.weather

import android.content.Context
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.Navigator
import com.curveon.data.routing.Stop
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The weather on the trip in the preview, and what could make it drier. */
data class PreviewWeather(
    /** The route it is about (the selected one). */
    val route: Route,
    val trip: TripWeather,
    /** Near which place the rain (or else the strong wind) starts; null = no name for it, or none. */
    val place: String?,
    /** The weather on each of the preview's alternatives, in their order (null = not routed, or no forecast). */
    val candidates: List<TripWeather?>,
    /** Leaving at this time instead, the trip is dry; null = no dry start in the next hours (or it is dry now). */
    val drierStart: Long? = null,
    /** A way round the rain: through this place, costing [via] extra seconds. */
    val via: WeatherVia? = null,
    /** Still looking for a way round the rain. */
    val searching: Boolean = false,
    /** Offline with an older forecast: when it was made (null = fresh). */
    val forecastFrom: Long? = null
)

data class WeatherVia(val stop: Stop, val extraSeconds: Double, val dry: Boolean)

/** "Good weather today": the best directions for a loop of [hours] from here. */
data class GoodWeather(val hours: Int, val loading: Boolean, val directions: List<DirectionWeather>, val failed: Boolean = false)

/**
 * Weather for planning (docs/features.md, "Kjør etter godt vær"): it shows only where the rider plans, and only when
 * there is a forecast. Nothing is fetched in the background: a trip in the preview, or Where to? opening, asks for it.
 */
class WeatherController(
    context: Context,
    private val scope: CoroutineScope,
    private val navigator: Navigator,
    fetch: suspend (Double, Double) -> com.curveon.protocol.WeatherForecast?,
    /** A forecast can be fetched now (internet here, or through the phone). */
    private val canFetch: () -> Boolean,
    private val placeName: suspend (Double, Double) -> String?,
    private val position: () -> LatLon?
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val service = WeatherService(context, fetch)

    private val _enabled = MutableStateFlow(prefs.getBoolean("weather", true))
    /** Settings → Navigation → Weather: off, nothing is sent to MET and nothing about weather shows. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _preview = MutableStateFlow<PreviewWeather?>(null)
    val preview: StateFlow<PreviewWeather?> = _preview.asStateFlow()

    private val _goodWeather = MutableStateFlow<GoodWeather?>(null)
    val goodWeather: StateFlow<GoodWeather?> = _goodWeather.asStateFlow()
    private var goodJob: Job? = null
    private var goodAt: Pair<LatLon, Long>? = null

    init {
        scope.launch { service.tidy() }
        scope.launch {
            navigator.state.map { s -> s.preview?.let { p -> Triple(p.goal, p.candidates.map { it.route }, p.selected) } }
                .distinctUntilChanged()
                .collectLatest { p ->
                    if (p == null || !_enabled.value) { _preview.value = null; return@collectLatest }
                    val (goal, routes, selected) = p
                    val route = routes.getOrNull(selected) ?: run { _preview.value = null; return@collectLatest }
                    // Wait for every alternative before judging them against each other.
                    if (routes.size > 1 && routes.any { it == null } && navigator.state.value.preview?.calculating == true) return@collectLatest
                    runCatching { judge(goal, routes, route) }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.w(TAG, "Weather for the preview failed", it)
                    }
                }
        }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean("weather", on).apply()
        if (!on) { _preview.value = null; _goodWeather.value = null; goodJob?.cancel() }
    }

    // ------------------------------------------------------------------ the trip in the preview

    private suspend fun judge(goal: NavigationGoal, routes: List<Route?>, route: Route) {
        val now = System.currentTimeMillis()
        val points = WeatherScore.along(route, now)
        val trip = WeatherScore.trip(points, service.hours(points, now))
        if (!trip.known) { _preview.value = null; return }
        val candidates = routes.map { r ->
            if (r == null) null else if (r === route) trip
            else WeatherScore.along(r, now).let { WeatherScore.trip(it, service.hours(it, now)) }.takeIf { it.known }
        }
        val trouble = trip.firstWet ?: trip.firstWindy
        val place = trouble?.let { placeName(it.at.point.lat, it.at.point.lon) }
        var shown = PreviewWeather(route, trip, place, candidates, forecastFrom = service.staleSince(points, now))
        _preview.value = shown
        if (trip.firstWet == null) return

        // Leaving a little later: the same places, so mostly the same forecasts already fetched.
        val later = (1..8).map { now + it * 30 * 60_000L }.firstOrNull { start ->
            val p = WeatherScore.along(route, start)
            WeatherScore.trip(p, service.hours(p, now)).let { it.known && it.dry }
        }
        shown = shown.copy(drierStart = later)
        _preview.value = shown

        // A way round the rain: only for a trip to a place that is changed by adding a stop, and long enough for it.
        val d = goal as? NavigationGoal.Destination ?: return
        if (d.fixedLine != null || d.loop != null || d.adventure != null || d.shared != null || route.distanceMeters < 40_000) return
        val wet = trip.firstWet ?: return
        if (wet.at.metersFromStart < 10_000) return // raining where it starts: no way round that
        _preview.value = shown.copy(searching = true)
        val via = wayRound(d, route, wet, trip, now)
        _preview.value = shown.copy(via = via, searching = false)
    }

    /** Through a place to the side of where the rain starts: tried 30 and then 60 km off the route, either side. */
    private suspend fun wayRound(goal: NavigationGoal.Destination, route: Route, wet: WeatherSpot, trip: TripWeather, now: Long): WeatherVia? {
        val pts = route.points
        val i = pts.indexOfFirst { it.distanceFromStart >= wet.at.metersFromStart }.coerceAtLeast(1)
        val a = pts[(i - 20).coerceAtLeast(0)]
        val b = pts[(i + 20).coerceAtMost(pts.lastIndex)]
        val heading = Geo.bearing(a.lat, a.lon, b.lat, b.lon)
        val from = goal.start?.point ?: LatLon(pts.first().lat, pts.first().lon)
        var best: Pair<WeatherVia, Double>? = null
        for (offKm in listOf(30.0, 60.0)) {
            for (side in listOf(-90.0, 90.0)) {
                val at = Geo.destination(wet.at.point, heading + side, offKm * 1000)
                // Is it even dry there when the rider would pass? Cheap to know before routing.
                val there = service.hours(listOf(WeatherPoint(at, wet.at.time + (offKm / 60 * 3_600_000).toLong(), 0.0)), now).first()
                if (there == null || WeatherScore.wet(there)) continue
                val name = placeName(at.lat, at.lon) ?: continue
                val stop = Stop(name, at)
                val stops = navigator.withStop(from, goal.stops, goal.destination, stop)
                val r = navigator.tryRoute(listOf(from) + stops.map { it.point } + goal.destination, goal.options) ?: continue
                val extra = r.durationSeconds - route.durationSeconds
                if (extra > maxOf(20 * 60.0, route.durationSeconds * 0.25)) continue
                val p = WeatherScore.along(r, now)
                val w = WeatherScore.trip(p, service.hours(p, now))
                if (!w.known || w.score > trip.score * 0.6) continue
                if (best == null || w.score < best.second) best = WeatherVia(stop, extra, w.dry) to w.score
            }
            if (best != null) break
        }
        return best?.first
    }

    // ------------------------------------------------------------------ good weather today

    /** Where to? opened (or the rider picked another length): the best directions for a loop of [hours] from here. */
    fun checkGoodWeather(hours: Int = prefs.getInt("weatherHours", 3)) {
        if (!_enabled.value) return
        val here = position() ?: return
        val now = System.currentTimeMillis()
        val current = _goodWeather.value
        // The same length from about the same place within ten minutes: what is shown still holds.
        goodAt?.let { (p, t) ->
            if (current != null && current.hours == hours && !current.failed && Geo.distance(p, here) < 3_000 && now - t < 10 * 60_000L) return
        }
        if (!canFetch() && current == null) return
        prefs.edit().putInt("weatherHours", hours).apply()
        goodJob?.cancel()
        _goodWeather.value = GoodWeather(hours, loading = true, directions = current?.directions?.takeIf { current.hours == hours }.orEmpty())
        goodJob = scope.launch {
            val km = hours * KMH
            val loops = WeatherScore.DIRECTIONS.map { it to WeatherScore.loop(here, km, it, hours.toDouble(), now) }
            val found = runCatching {
                val all = service.hours(loops.flatMap { it.second }, now)
                var k = 0
                loops.map { (dir, pts) -> DirectionWeather(dir, WeatherScore.trip(pts, all.subList(k, k + pts.size).also { k += pts.size })) }
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
                .filter { it.weather.known }.sortedBy { it.weather.score }
            goodAt = here to now
            _goodWeather.value = if (found.isEmpty()) GoodWeather(hours, false, emptyList(), failed = true)
                else GoodWeather(hours, false, found.take(3))
        }
    }

    /** About how far a loop of [hours] goes: the router's own pace on curvy roads (a 3-hour loop is about 3 hours of riding). */
    fun kmFor(hours: Int) = hours * KMH

    companion object {
        private const val TAG = "CurveonWeather"
        private const val KMH = 60.0
        val HOURS = listOf(2, 3, 5, 7)
    }
}
