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
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** The weather on the map: where its symbol is put (on a road near the [place] it was asked for), what kind, and how warm. */
data class MapWeather(val point: LatLon, val kind: Kind, val tempC: Int, val place: LatLon = point) {
    enum class Kind { SUN, CLOUD, RAIN, WIND }
}

/** The part of the map in view. */
data class MapBounds(val south: Double, val north: Double, val west: Double, val east: Double)

/**
 * Weather for planning (docs/features.md, "Kjør etter godt vær"): it shows only where the rider plans, and only when
 * there is a forecast. Nothing is fetched in the background: a trip in the preview, drawing a loop, or the weather map asks.
 */
class WeatherController(
    private val context: Context,
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
    /** Null (never expected): then the places can't be told from the sea, and all are shown. */
    private val landMask by lazy { LandMask.load(context) }

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
            // With the setting, so switching it off also stops what is being worked out, and on again shows it.
            combine(navigator.state.map { s -> s.preview?.let { p -> Triple(p.goal, p.candidates.map { it.route }, p.selected) } }
                .distinctUntilChanged(), _enabled) { p, on -> p.takeIf { on } }
                .collectLatest { p ->
                    if (p == null) { _preview.value = null; return@collectLatest }
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
        if (!on) clearSketchWeather()
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
        // Not where the rider stands: the arrow is there, and that weather they can see. A little off the rings, which look
        // odd otherwise; the same each time for the same ring and direction.
        val places = RINGS_KM.flatMapIndexed { ring, r ->
            WeatherScore.DIRECTIONS.mapIndexed { i, d ->
                val h = mix(ring, i, 0)
                val km = r * spread * (1 + 0.15 * (share(h) * 2 - 1))
                // There about when the rider would be (the road winds a third longer than the crow flies).
                val at = now + (km * 1.3 / KMH * 3_600_000).toLong()
                Place(Geo.destination(here, d + (if (ring == 1) 22.5 else 0.0) + 12 * (share(h ushr 16) * 2 - 1), km * 1000), at)
            }
        }
        sketchJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val land = landMask
            fill(_sketch, places.filter { land == null || land.isLand(it.at.lat, it.at.lon) }, SKETCH_APART_M * spread)
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

    /**
     * The weather map, for as long as [views] is collected: the part of the map in view (null = not shown). A view that shows
     * the same places asks nothing again and lets the loading go on; any other cancels it, keeping the forecasts on their way.
     */
    suspend fun followMap(views: kotlinx.coroutines.flow.Flow<MapBounds?>) {
        views.map { v -> v?.let { mapGrid(it.south, it.north, it.west, it.east, landMask) } }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .distinctUntilChangedBy { it?.places }
            .collectLatest { grid ->
                if (grid == null) { _map.value = emptyList(); return@collectLatest }
                val now = System.currentTimeMillis()
                withContext(kotlinx.coroutines.Dispatchers.Default) {
                    fill(_map, grid.places.sortedBy { Geo.distance(it, grid.middle) }.map { Place(it, now) }, grid.apartM * ON_ROADS_APART)
                }
            }
    }

    private class Place(val at: LatLon, val time: Long)

    /**
     * Fills [target] with the weather at [places], in that order, one symbol at a time as each is ready, so the rider sees it
     * come and the map is never held up. What is not wanted any more goes at once (symbols from the last zoom level would lie
     * in a clump) and what is wanted stays where it is. Symbols keep [apartM] between them. A forecast that could not be
     * fetched is tried again now and then, for as long as the view stays. Cancelled when the view changes.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun fill(target: MutableStateFlow<List<MapWeather>>, places: List<Place>, apartM: Double) {
        val wanted = places.mapTo(HashSet()) { it.at }
        val shown = LinkedHashMap<LatLon, MapWeather>()
        fun fits(w: MapWeather) = shown.values.none { it.place != w.place && Geo.distance(it.point, w.point) < apartM }
        target.value.forEach { if (it.place in wanted && fits(it)) shown[it.place] = it }
        target.value = shown.values.toList()

        var todo = places
        while (todo.isNotEmpty()) {
            val failed = java.util.concurrent.ConcurrentLinkedQueue<Place>()
            todo.asFlow().flatMapMerge(FETCHES_AT_ONCE) { p ->
                flow {
                    val road = onRoad(p.at) ?: return@flow
                    val hours = runCatching {
                        service.hours(listOf(WeatherPoint(road, p.time, 0.0), WeatherPoint(road, p.time + 3_600_000L, 0.0)))
                    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
                    val w = mapWeather(road, hours.getOrNull(0), hours.getOrNull(1))
                    if (w == null) failed += p else emit(w.copy(place = p.at))
                }
            }.collect { w ->
                if (shown[w.place] != w && (w.place in shown || fits(w))) {
                    shown[w.place] = w
                    target.value = shown.values.toList()
                    kotlinx.coroutines.delay(SYMBOL_GAP_MS)
                }
            }
            todo = failed.toList()
            if (todo.isNotEmpty()) kotlinx.coroutines.delay(RETRY_MS)
        }
    }

    /** Where each place's weather is put on the map: on the nearest road within 6 km (null = no road near: left out). */
    private val roadSpots = object : LinkedHashMap<LatLon, LatLon?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LatLon, LatLon?>?) = size > 3000
    }

    private suspend fun onRoad(p: LatLon): LatLon? {
        synchronized(roadSpots) { if (roadSpots.containsKey(p)) return roadSpots[p] }
        val at = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { navigator.nearestRoad(p, LAND_SNAP_M) }.getOrDefault(p) }
        synchronized(roadSpots) { roadSpots[p] = at }
        return at
    }

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
        /** Places to choose from: cells at most this share of the view's size (the root of its area, so upright and sideways alike). */
        private const val MAP_CELL_SHARE = 0.1
        /** Symbols are kept this share of the view's size apart: how dense the weather map is, at every zoom. */
        private const val MAP_APART_SHARE = 0.15
        /** Going on roads may bring two nearer: this much of that is still let through. */
        private const val ON_ROADS_APART = 0.75
        /** A place's weather goes on a road this near it, or is left out (with no road at all). */
        private const val LAND_SNAP_M = 6_000.0
        /** With the row round the view: a wide tablet zoomed out stays within this, by coarser cells. */
        private const val MAX_MAP_CELLS = 240
        /** The finest cells' side, north-south (a forecast cell); east-west twice the degrees, about as many km in the north. */
        private const val FINEST_STEP = 0.1
        private const val M_PER_DEGREE = 111_000.0
        /** Each coarser level doubles the step. */
        private const val MAX_LEVEL = 7
        private const val SKETCH_APART_M = 8_000.0
        /** No more than [WeatherService] lets through, so what is no longer wanted is not queued up. */
        private const val FETCHES_AT_ONCE = 4
        /** The map is given a new symbol at most this often, however fast they are ready. */
        private const val SYMBOL_GAP_MS = 50L
        private const val RETRY_MS = 30_000L

        internal data class MapGrid(val places: List<LatLon>, val apartM: Double, val middle: LatLon)

        /**
         * The places to show weather at in the view and a row round it. The world is cut in cells, [FINEST_STEP] a side
         * or a doubling of it, with one place in each. A cell picks its place from one of its four quarters at random, and
         * always the same one, so the places don't form a grid, stay the same however the map is moved, and those of a coarser
         * level are among those of the finer: zooming keeps what is there. A cell with only sea has none. Places too near each
         * other for the view's size give way, always in the same order, so the map is equally full at every zoom (the cells alone
         * would make it four times fuller or emptier at each doubling) and only those shown are asked for. Zoomed in closer than
         * one step, the one in the middle.
         */
        internal fun mapGrid(south: Double, north: Double, west: Double, east: Double, land: LandMask? = null): MapGrid {
            val size = kotlin.math.sqrt((north - south) * (east - west) * kotlin.math.cos(Math.toRadians((south + north) / 2))) * M_PER_DEGREE
            var level = 0
            while (FINEST_STEP * (1 shl level) * M_PER_DEGREE < size * MAP_CELL_SHARE && level < MAX_LEVEL) level++
            fun rows(l: Int): IntRange { val s = FINEST_STEP * (1 shl l); return (kotlin.math.floor(south / s).toInt() - 1)..(kotlin.math.floor(north / s).toInt() + 1) }
            fun cols(l: Int): IntRange { val s = FINEST_STEP * (1 shl l) * 2; return (kotlin.math.floor(west / s).toInt() - 1)..(kotlin.math.floor(east / s).toInt() + 1) }
            while (rows(level).count() * cols(level).count() > MAX_MAP_CELLS) level++
            val apartM = size * MAP_APART_SHARE
            val kept = ArrayList<LatLon>()
            // Those a coarser level has too go first: zooming out, they are the ones left, so what is on the map stays.
            rows(level).flatMap { r -> cols(level).mapNotNull { c -> placeIn(level, r, c, land)?.let { p -> Triple(p, levelsUp(level, r, c, p, land), mix((p.lat * 10000).toInt(), (p.lon * 10000).toInt(), 7)) } } }
                .sortedWith(compareBy({ -it.second }, { it.third }))
                .forEach { (p, _, _) -> if (kept.none { Geo.distance(it, p) < apartM }) kept += p }
            val middle = LatLon(round4((south + north) / 2), round4((west + east) / 2))
            if (kept.none { it.lat in south..north && it.lon in west..east } && (land == null || land.isLand(middle.lat, middle.lon))) kept += middle
            return MapGrid(kept, apartM, middle)
        }

        /** How many levels above its own cell's the place [p] is still the one chosen. */
        private fun levelsUp(level: Int, row: Int, col: Int, p: LatLon, land: LandMask?): Int {
            var up = 0
            while (level + up < MAX_LEVEL + 2 && placeIn(level + up + 1, row shr (up + 1), col shr (up + 1), land) == p) up++
            return up
        }

        /** The place of a cell; null = only sea. */
        private fun placeIn(level: Int, row: Int, col: Int, land: LandMask?): LatLon? {
            if (level == 0) {
                for (attempt in 0 until 4) {
                    val h = mix(row, col, attempt)
                    val lat = round4((row + 0.1 + 0.8 * share(h)) * FINEST_STEP)
                    val lon = round4((col + 0.1 + 0.8 * share(h ushr 16)) * FINEST_STEP * 2)
                    if (land == null || land.isLand(lat, lon)) return LatLon(lat, lon)
                }
                return null
            }
            val step = FINEST_STEP * (1 shl level)
            if (land != null && !land.anyLand(row * step, (row + 1) * step, col * step * 2, (col + 1) * step * 2)) return null
            val h = mix(row, col, level + 100)
            val stride = if (h and 4 == 0) 1 else 3
            for (i in 0 until 4) {
                val quarter = ((h and 3) + i * stride) and 3
                placeIn(level - 1, row * 2 + (quarter shr 1), col * 2 + (quarter and 1), land)?.let { return it }
            }
            return null
        }

        /** Well-stirred bits from a few numbers: random-looking, and the same every time. */
        private fun mix(a: Int, b: Int, c: Int): Int {
            var h = a * 73856093 xor b * 19349663 xor c * 83492791
            h = (h xor (h ushr 15)) * 1274126177
            h = (h xor (h ushr 13)) * -1028477379
            return h xor (h ushr 16)
        }

        /** 0 to 1 from the lowest 16 bits. */
        private fun share(h: Int) = (h and 0xFFFF) / 65536.0

        private fun round4(v: Double) = Math.round(v * 10000) / 10000.0
    }
}
