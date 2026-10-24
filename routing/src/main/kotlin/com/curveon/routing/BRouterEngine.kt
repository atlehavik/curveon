package com.curveon.routing

import btools.router.OsmNodeNamed
import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.CurveonBridge
import java.io.File
import kotlin.math.roundToInt

/**
 * Offline routing with BRouter.
 *
 * @param segmentDir directory with BRouter .rd5 segment files (e.g. E10_N60.rd5)
 * @param profileDir directory holding curveon-moto.brf and lookups.dat (see [ProfileInstaller])
 */
class BRouterEngine(
    private val segmentDir: File,
    private val profileDir: File,
    private val timeoutMillis: Long = 60_000L,
    /**
     * Memory BRouter may fill with decoded map data before it starts throwing data away (and
     * decoding it again later). Its default of 64 MB is sized for old phones.
     */
    private val memoryClassMb: Int = 64
) {

    /**
     * Route from the first to the last waypoint, passing the ones in between in order.
     *
     * @param avoidedFerryCost what one ferry costs when [RouteOptions.avoidFerries] is on, in metres
     *   of detour. The default makes ferries a last resort; a lower value keeps searches near
     *   islands quick (BRouter explores everything cheaper than the first route it finds).
     */
    fun route(
        waypoints: List<LatLon>,
        options: RouteOptions,
        viaCatchingRangeMeters: Double? = null,
        avoidedFerryCost: Int = AVOIDED_FERRY_COST,
        /** Direction the rider is moving at the start, so turning off ahead costs less than turning round. */
        startHeadingDeg: Double? = null,
        /** Small areas the route must not pass through (e.g. the road just behind the rider). */
        noGo: List<NoGo> = emptyList(),
        /**
         * With Tarmac, tracks may still be ridden when they are the only way to a place (a farm,
         * a cabin, a forest road home). Off false for ways back to a route: those should be roads.
         */
        tracksAsLastResort: Boolean = true,
        /**
         * The via points are stops to ride to: reach each one even down a dead end and back. Without
         * it BRouter cuts such an out-and-back short and rides past the stop (fine for points that
         * only shape the route, like a track's).
         */
        visitEveryVia: Boolean = false,
        /** Give up on this route after this long, instead of the engine's own limit (a quick look at a road that may wind for ever). */
        maxMillis: Long? = null
    ): Route {
        val reach = options.gravelReachKm
        if (reach != null && options.surface >= SurfacePreference.GRAVEL) {
            // Gravel, but not too far off: the ordinary route first, then the gravel one with less and
            // less pull towards gravel until it stays within reach of it.
            fun plan(o: RouteOptions) = route(waypoints, o.copy(gravelReachKm = null), viaCatchingRangeMeters, avoidedFerryCost,
                startHeadingDeg, noGo, tracksAsLastResort, visitEveryVia)
            // No gravel within reach: the ordinary route (with what gravel it has).
            val ordinary = plan(options.copy(surface = SurfacePreference.MIXED))
            for (pull in GRAVEL_PULLS) {
                val r = runCatching { plan(options.copy(gravelPull = pull)) }.getOrNull() ?: continue
                if (Routes.farthestFrom(r, ordinary) <= reach * 1000.0) return r
            }
            return ordinary
        }
        if (visitEveryVia && waypoints.size > 2) {
            return Routes.joinLegs(legs(waypoints, options, viaCatchingRangeMeters, avoidedFerryCost, startHeadingDeg, null, noGo, tracksAsLastResort, maxMillis))
        }
        val r = routeOnce(waypoints, options, viaCatchingRangeMeters, avoidedFerryCost, startHeadingDeg, noGo, tracksAsLastResort, lookPastPaths = false, maxMillis = maxMillis)
        // Start and end on the rider's own road even where the map doesn't join it to the network.
        return OwnRoad { pts, look -> routeOnce(pts, options, null, avoidedFerryCost, null, emptyList(), tracksAsLastResort, look, maxMillis) }.join(waypoints, r)
    }

    /**
     * The legs from each waypoint to the next, every one a route of its own, as [route] rides them with
     * visitEveryVia: a planner can keep the legs that did not change and ask only for the ones that did.
     * [arrivedHeadingDeg]: the way the rider rode into the first waypoint (from a leg before these, see
     * [arrivalHeading]), so it is left the same way instead of by turning round.
     */
    fun routeLegs(waypoints: List<LatLon>, options: RouteOptions, arrivedHeadingDeg: Double? = null, maxMillis: Long? = null): List<Route> =
        legs(waypoints, options, null, AVOIDED_FERRY_COST, null, arrivedHeadingDeg, emptyList(), true, maxMillis)

    /** Leg by leg: each stop is a leg's end, so it can't be cut out. (BRouter's allowSamewayback would keep it too, but turns a plain A→B trip into there and back.) */
    private fun legs(
        waypoints: List<LatLon>, options: RouteOptions, viaCatchingRangeMeters: Double?, avoidedFerryCost: Int, startHeadingDeg: Double?,
        arrivedHeadingDeg: Double?, noGo: List<NoGo>, tracksAsLastResort: Boolean, maxMillis: Long?
    ): List<Route> {
        require(waypoints.size >= 2) { "Need at least start and destination" }
        val legs = ArrayList<Route>(waypoints.size - 1)
        for ((i, ab) in waypoints.zipWithNext().withIndex()) {
            val (a, b) = ab
            val heading = if (i == 0) arrivedHeadingDeg else arrivalHeading(legs.last())
            legs += if (heading == null) route(listOf(a, b), options, viaCatchingRangeMeters, avoidedFerryCost, if (i == 0) startHeadingDeg else null, noGo, tracksAsLastResort, maxMillis = maxMillis)
                else leaveStop(a, b, heading, options, viaCatchingRangeMeters, avoidedFerryCost, noGo, tracksAsLastResort, maxMillis)
        }
        return legs
    }

    /**
     * The leg from [stop] on to [next], leaving the stop the way the rider rode in ([heading]). A leg on its own starts
     * in any direction, so a stop by the road (a fuel station beside the main road, a via point) became
     * "ride to the point and turn round on the road", even at 80 km/h. Turning round at the stop is kept
     * only for a dead end, or when going on to a place to turn costs more than [STOP_TURN_SLACK_S].
     */
    private fun leaveStop(
        stop: LatLon, next: LatLon, heading: Double, options: RouteOptions, viaCatchingRangeMeters: Double?,
        avoidedFerryCost: Int, noGo: List<NoGo>, tracksAsLastResort: Boolean, maxMillis: Long?
    ): Route {
        val plain = route(listOf(stop, next), options, viaCatchingRangeMeters, avoidedFerryCost, null, noGo, tracksAsLastResort, maxMillis = maxMillis)
        if (!OffRoutePlanner.turnsBack(plain, stop, heading) && !OffRoutePlanner.uTurnsAhead(plain)) return plain
        val ahead = OffRoutePlanner(this).forwardOnly(stop, heading, listOf(next), options, noGo).second ?: return plain
        return if (ahead.durationSeconds - plain.durationSeconds <= STOP_TURN_SLACK_S) ahead else plain
    }

    private fun routeOnce(
        waypoints: List<LatLon>,
        options: RouteOptions,
        viaCatchingRangeMeters: Double?,
        avoidedFerryCost: Int,
        startHeadingDeg: Double?,
        noGo: List<NoGo>,
        tracksAsLastResort: Boolean,
        /** Paths count as roads: only to find where the rider's own road meets one (see [OwnRoad]). */
        lookPastPaths: Boolean,
        maxMillis: Long? = null
    ): Route {
        require(waypoints.size >= 2) { "Need at least start and destination" }
        val nodes = waypoints.mapIndexed { i, p ->
            OsmNodeNamed().apply {
                name = when (i) { 0 -> "from"; waypoints.lastIndex -> "to"; else -> "via$i" }
                ilat = ((p.lat + 90.0) * 1_000_000.0).roundToInt()
                ilon = ((p.lon + 180.0) * 1_000_000.0).roundToInt()
            }
        }
        val rc = RoutingContext().apply {
            localFunction = File(profileDir, PROFILE_FILE).absolutePath
            keyValues = profileParams(options, avoidedFerryCost, tracksAsLastResort, lookPastPaths)
            waypointCatchingRange = viaCatchingRangeMeters ?: DEFAULT_CATCHING_RANGE_M
            memoryclass = memoryClassMb
            if (startHeadingDeg != null) {
                startDirection = ((startHeadingDeg.roundToInt() % 360) + 360) % 360
                forceUseStartDirection = true
            }
            if (noGo.isNotEmpty()) {
                val list = noGo.map { n ->
                    OsmNodeNamed().apply {
                        name = "nogo" + n.radiusMeters.roundToInt()
                        ilat = ((n.center.lat + 90.0) * 1_000_000.0).roundToInt()
                        ilon = ((n.center.lon + 180.0) * 1_000_000.0).roundToInt()
                        isNogo = true
                        nogoWeight = n.weight ?: Double.NaN
                        radius = n.radiusMeters
                    }
                }
                RoutingContext.prepareNogoPoints(list)
                nogopoints = list
            }
        }
        val engine = RoutingEngine(null, null, segmentDir, nodes, rc, RoutingEngine.BROUTER_ENGINEMODE_ROUTING)
        engine.quite = true
        val cancel = CANCEL.get()
        cancel?.running(engine) { engine.doRun(maxMillis ?: timeoutMillis) } ?: engine.doRun(maxMillis ?: timeoutMillis)
        if (cancel?.cancelled == true) throw RoutingException(CANCELLED)
        engine.errorMessage?.let { throw RoutingException(it) }
        val track = engine.foundTrack ?: throw RoutingException("No route found")
        return toRoute(track)
    }

    private fun profileParams(o: RouteOptions, avoidedFerryCost: Int, tracksAsLastResort: Boolean, lookPastPaths: Boolean): MutableMap<String, String> {
        val params = sortedMapOf(
            "road_style" to o.roadStyle.ordinal.toString(),
            "surface_pref" to o.surface.ordinal.toString(),
            "avoid_ferries" to o.avoidFerries.flag(),
            "avoid_motorways" to o.avoidMotorways.flag(),
            "avoid_trunk" to o.avoidTrunk.flag(),
            "avoid_toll" to o.avoidToll.flag(),
            "avoided_ferry_cost" to avoidedFerryCost.toString(),
            "tracks_last_resort" to tracksAsLastResort.flag(),
            "look_past_paths" to lookPastPaths.flag(),
            "gravel_pull" to o.gravelPull.toString()
        )
        // BRouter caches parsed profiles keyed by the *sum* of the parameters' hash codes, so
        // "curvy, no ferries" (1 + 1) is the same profile to it as "very curvy, ferries" (2 + 0) and
        // a route would silently use whichever was parsed first. One extra value spelling out all the
        // parameters makes every combination's sum different.
        // (Values must be numbers: all digits of all parameters, behind a 9 so none get lost.)
        params[CACHE_KEY] = "9" + params.values.joinToString("")
        return HashMap(params)
    }

    private fun Boolean.flag() = if (this) "1" else "0"

    private fun toRoute(track: OsmTrack): Route {
        val raw = CurveonBridge.points(track)
        val points = ArrayList<RoutePoint>(raw.size)
        var dist = 0.0
        var secs = 0.0
        var unpaved = 0.0
        var ascent = 0.0
        var ferries = 0
        var lastWasFerry = false
        for ((i, p) in raw.withIndex()) {
            val tags = WayTags.parse(p.wayTags)
            if (i > 0) {
                val prev = raw[i - 1]
                val seg = Geo.distance(prev.lat, prev.lon, p.lat, p.lon)
                dist += seg
                secs += seg / (SpeedModel.cruiseKmh(tags) / 3.6)
                if (tags.unpaved) unpaved += seg
                if (p.ele > prev.ele) ascent += p.ele - prev.ele
                if (tags.ferry && !lastWasFerry) ferries++
                lastWasFerry = tags.ferry
            }
            points += RoutePoint(
                lat = p.lat, lon = p.lon, elevation = p.ele,
                distanceFromStart = dist, secondsFromStart = secs,
                highway = tags.highway, maxSpeedKmh = tags.maxSpeed,
                unpaved = tags.unpaved, ferry = tags.ferry, surface = tags.surface, tracktype = tags.tracktype
            )
        }
        val maneuvers = buildManeuvers(CurveonBridge.hints(track), points)
        return Route(
            points = points,
            maneuvers = maneuvers,
            distanceMeters = dist,
            durationSeconds = secs,
            ascentMeters = ascent,
            curviness = Curviness.score(points.map { LatLon(it.lat, it.lon) }),
            unpavedMeters = unpaved,
            ferryCount = ferries
        )
    }

    private fun buildManeuvers(hints: List<CurveonBridge.Hint>, points: List<RoutePoint>): List<Maneuver> {
        val out = mutableListOf(Maneuver(ManeuverType.DEPART, 0, 0.0))
        for (h in hints) {
            val type = when (h.command) {
                "TL" -> ManeuverType.LEFT
                "TSLL" -> ManeuverType.SLIGHT_LEFT
                "TSHL" -> ManeuverType.SHARP_LEFT
                "TR" -> ManeuverType.RIGHT
                "TSLR" -> ManeuverType.SLIGHT_RIGHT
                "TSHR" -> ManeuverType.SHARP_RIGHT
                "KL" -> ManeuverType.KEEP_LEFT
                "KR" -> ManeuverType.KEEP_RIGHT
                "EL" -> ManeuverType.EXIT_LEFT
                "ER" -> ManeuverType.EXIT_RIGHT
                "TU", "TLU", "TRU" -> ManeuverType.U_TURN
                "RNDB", "RNLB" -> ManeuverType.ROUNDABOUT
                "C" -> ManeuverType.STRAIGHT
                else -> null // END is added below; beeline/off-route hints are not maneuvers
            } ?: continue
            val idx = h.indexInTrack.coerceIn(0, points.lastIndex)
            out += Maneuver(type, idx, points[idx].distanceFromStart, if (type == ManeuverType.ROUNDABOUT) h.roundaboutExit else 0)
        }
        out += Maneuver(ManeuverType.ARRIVE, points.lastIndex, points.last().distanceFromStart)
        return out
    }

    /**
     * Lets a search on another thread be given up (a planner page asked for something newer): [cancel] stops the
     * BRouter runs going on under [cancellable], and those not started yet end at once with a [RoutingException].
     */
    class Cancel {
        @Volatile var cancelled = false
            private set
        private val engines = java.util.concurrent.ConcurrentHashMap.newKeySet<RoutingEngine>()

        fun cancel() {
            cancelled = true
            engines.forEach { it.terminate() }
        }

        internal fun running(engine: RoutingEngine, run: () -> Unit) {
            if (cancelled) throw RoutingException(CANCELLED)
            engines += engine
            try { run() } finally { engines -= engine }
        }
    }

    companion object {
        const val PROFILE_FILE = "curveon-moto.brf"
        /** The message of a search given up with [Cancel]. */
        const val CANCELLED = "cancelled"
        private val CANCEL = ThreadLocal<Cancel?>()

        /** Runs [block] on this thread so that [cancel] stops the routes it works out (not those on other threads). */
        fun <T> cancellable(cancel: Cancel, block: () -> T): T {
            CANCEL.set(cancel)
            try { return block() } finally { CANCEL.remove() }
        }

        /** Whether the search this thread runs for was given up (for work between BRouter runs). */
        fun cancelled(): Boolean = CANCEL.get()?.cancelled == true

        /** Which way a route ends: the bearing over its last 20 m, or null for a leg too short to say. */
        fun arrivalHeading(leg: Route): Double? =
            if (leg.distanceMeters < 40) null else Routes.pointAt(leg, leg.distanceMeters - 20).second
        /** Gravel pull tried in turn while a gravel route strays too far: full, then weaker. */
        private val GRAVEL_PULLS = listOf(100, 50, 20)

        private const val CACHE_KEY = "curveon_params"

        /** Going on from a stop to a place to turn may cost this much more than turning round on the road there. */
        const val STOP_TURN_SLACK_S = 600.0

        /** An avoided ferry is worth a ~1000 km detour. */
        const val AVOIDED_FERRY_COST = 1_000_000


        /** Start/destination may be a tap in the forest; snap to a road up to this far away. */
        const val DEFAULT_CATCHING_RANGE_M = 1_500.0
    }
}

internal data class WayTags(
    val highway: String?,
    val maxSpeed: Int?,
    val unpaved: Boolean,
    val ferry: Boolean,
    val surface: String? = null,
    val tracktype: String? = null
) {
    companion object {
        private val UNPAVED = setOf(
            "unpaved", "gravel", "ground", "dirt", "compacted", "fine_gravel", "pebblestone",
            "earth", "sand", "grass", "mud", "unpaved_minor", "dirt/sand", "soil", "clay"
        )

        fun parse(s: String?): WayTags {
            if (s.isNullOrBlank()) return WayTags(null, null, unpaved = false, ferry = false)
            val map = s.split(' ').mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i > 0) kv.substring(0, i) to kv.substring(i + 1) else null
            }.toMap()
            val highway = map["highway"]
            val surface = map["surface"]
            return WayTags(
                highway = highway,
                maxSpeed = map["maxspeed"]?.toIntOrNull(),
                unpaved = surface in UNPAVED || (surface == null && highway == "track"),
                ferry = map["route"] == "ferry",
                surface = surface,
                tracktype = map["tracktype"]
            )
        }
    }
}

/** Rough real-world motorcycle cruising speeds for ETA, capped by the signed limit. */
internal object SpeedModel {
    fun cruiseKmh(t: WayTags): Double {
        if (t.ferry) return 18.0
        val byClass = when (t.highway) {
            "motorway" -> 105.0
            "motorway_link", "trunk_link" -> 60.0
            "trunk" -> 80.0
            "primary", "primary_link" -> 72.0
            "secondary", "secondary_link" -> 65.0
            "tertiary", "tertiary_link" -> 55.0
            "unclassified" -> 45.0
            "residential", "living_street" -> 28.0
            "service" -> 20.0
            "track", "road" -> 25.0
            else -> 40.0
        }
        val limited = t.maxSpeed?.let { minOf(byClass, it * 0.95) } ?: byClass
        return if (t.unpaved) minOf(limited, 45.0) else limited
    }
}
