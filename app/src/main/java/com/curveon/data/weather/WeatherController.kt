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
import kotlin.math.roundToInt

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

/** The weather at a place on the map while a loop is drawn: what kind, and how warm. */
data class MapWeather(val point: LatLon, val kind: Kind, val tempC: Int) {
    enum class Kind { SUN, CLOUD, RAIN, WIND }
}

/**
 * Weather for planning (docs/features.md, "Kjør etter godt vær"): it shows only where the rider plans, and only when
 * there is a forecast. Nothing is fetched in the background: a trip in the preview, or drawing a loop, asks for it.
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

    private val _sketch = MutableStateFlow<List<MapWeather>>(emptyList())
    /** The weather around the rider on the map while a loop is drawn (empty: not drawing, or no forecast). */
    val sketch: StateFlow<List<MapWeather>> = _sketch.asStateFlow()
    private var sketchJob: Job? = null

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
        if (!on) { _preview.value = null; clearSketchWeather(); clearMapWeather() }
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

    // ------------------------------------------------------------------ drawing a roundtrip

    /**
     * Drawing a loop of about [km] (null = the whole area, taken as [DEFAULT_LOOP_KM]): the weather around the rider on
     * the map (round [around] when the loop starts elsewhere), for about when they would be at each place, so the area
     * can be drawn where it is dry.
     */
    fun showSketchWeather(km: Int?, around: LatLon? = null) {
        sketchJob?.cancel()
        if (!_enabled.value) { _sketch.value = emptyList(); return }
        val here = around ?: position() ?: return
        val now = System.currentTimeMillis()
        val spread = ((km ?: DEFAULT_LOOP_KM) / DEFAULT_LOOP_KM.toDouble()).coerceIn(0.5, 2.0)
        // Not where the rider stands: the arrow is there, and that weather they can see.
        val places = RINGS_KM.flatMap { r ->
            WeatherScore.DIRECTIONS.map { d -> Geo.destination(here, d + (if (r == RINGS_KM[1]) 22.5 else 0.0), r * spread * 1000) to r * spread }
        }
        sketchJob = scope.launch {
            // There about when the rider would be (the road winds a third longer than the crow flies), and the hour after.
            val points = places.flatMap { (p, d) ->
                val at = now + (d * 1.3 / KMH * 3_600_000).toLong()
                listOf(WeatherPoint(p, at, 0.0), WeatherPoint(p, at + 3_600_000L, 0.0))
            }
            val hours = runCatching { service.hours(points, now) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
            _sketch.value = places.mapIndexedNotNull { i, (p, _) -> mapWeather(p, hours.getOrNull(2 * i), hours.getOrNull(2 * i + 1)) }
        }
    }

    /** Sun, cloud, rain or wind at [p] from the hour the rider is there and the one after. */
    private fun mapWeather(p: LatLon, first: com.curveon.protocol.WeatherHour?, next: com.curveon.protocol.WeatherHour?): MapWeather? {
        first ?: return null
        val both = listOfNotNull(first, next)
        val kind = when {
            both.any(WeatherScore::wet) -> MapWeather.Kind.RAIN
            both.any(WeatherScore::windy) -> MapWeather.Kind.WIND
            first.symbol?.let { it.startsWith("clearsky") || it.startsWith("fair") } == true -> MapWeather.Kind.SUN
            else -> MapWeather.Kind.CLOUD
        }
        return MapWeather(p, kind, first.tempC.roundToInt())
    }

    // ------------------------------------------------------------------ the weather map button

    private val _map = MutableStateFlow<List<MapWeather>>(emptyList())
    /** The weather map (its button on the full-screen map, standing still): the next two hours in the part of the map in view. */
    val map: StateFlow<List<MapWeather>> = _map.asStateFlow()
    private var mapJob: Job? = null

    /** One symbol per forecast cell (about 10 km) in view, at most [MAX_MAP_CELLS]: a few across, as many down as fit. */
    fun showMapWeather(south: Double, north: Double, west: Double, east: Double) {
        mapJob?.cancel()
        if (!_enabled.value) { _map.value = emptyList(); return }
        val now = System.currentTimeMillis()
        val midLat = (south + north) / 2
        val widthKm = Geo.distance(midLat, west, midLat, east) / 1000
        val heightKm = Geo.distance(south, west, north, west) / 1000
        val cols = 5
        val rows = (cols * heightKm / widthKm.coerceAtLeast(0.1)).roundToInt().coerceIn(2, 8)
        // A place in view for each cell (its middle may be off the screen when zoomed in): the first one of the grid in it.
        val places = (0 until rows).flatMap { r -> (0 until cols).map { c ->
            LatLon(south + (north - south) * (r + 0.5) / rows, west + (east - west) * (c + 0.5) / cols)
        } }.distinctBy { WeatherService.cellOf(it.lat, it.lon) }.take(MAX_MAP_CELLS)
        mapJob = scope.launch {
            val points = places.flatMap { listOf(WeatherPoint(it, now, 0.0), WeatherPoint(it, now + 3_600_000L, 0.0)) }
            val hours = runCatching { service.hours(points, now) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
            _map.value = places.mapIndexedNotNull { i, p -> mapWeather(p, hours.getOrNull(2 * i), hours.getOrNull(2 * i + 1)) }
        }
    }

    fun clearMapWeather() { mapJob?.cancel(); _map.value = emptyList() }

    fun clearSketchWeather() { sketchJob?.cancel(); _sketch.value = emptyList() }

    /** "Good weather – pick for me": the direction a loop of about [km] from [start] (null = here) stays driest; null = no forecast. */
    suspend fun bestDirection(km: Int?, start: LatLon? = null): Double? {
        if (!_enabled.value) return null
        val from = start ?: position() ?: return null
        val now = System.currentTimeMillis()
        val length = (km ?: DEFAULT_LOOP_KM).toDouble()
        val loops = WeatherScore.DIRECTIONS.map { it to WeatherScore.loop(from, length, it, length / KMH, now) }
        val all = runCatching { service.hours(loops.flatMap { it.second }, now) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; return null }
        var k = 0
        return loops.map { (dir, pts) -> DirectionWeather(dir, WeatherScore.trip(pts, all.subList(k, k + pts.size).also { k += pts.size })) }
            .filter { it.weather.known }.minByOrNull { it.weather.score }?.directionDeg
    }

    companion object {
        private const val TAG = "CurveonWeather"
        /** The router's pace on curvy roads, about. */
        private const val KMH = 60.0
        /** A loop drawn round "the whole area" is planned this long when the weather picks the way. */
        const val DEFAULT_LOOP_KM = 150
        /** Weather on the map this far out (for a 150 km loop), eight places on each ring. */
        private val RINGS_KM = listOf(20.0, 40.0, 65.0)
        private const val MAX_MAP_CELLS = 30
    }
}
