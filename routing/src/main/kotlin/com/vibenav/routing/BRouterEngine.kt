package com.vibenav.routing

import btools.router.OsmNodeNamed
import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.VibeNavBridge
import java.io.File
import kotlin.math.roundToInt

/**
 * Offline routing with BRouter.
 *
 * @param segmentDir directory with BRouter .rd5 segment files (e.g. E10_N60.rd5)
 * @param profileDir directory holding vibenav-moto.brf and lookups.dat (see [ProfileInstaller])
 */
class BRouterEngine(
    private val segmentDir: File,
    private val profileDir: File,
    private val timeoutMillis: Long = 60_000L
) {

    /** Route from the first to the last waypoint, passing the ones in between in order. */
    fun route(
        waypoints: List<LatLon>,
        options: RouteOptions,
        viaCatchingRangeMeters: Double? = null
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
            keyValues = profileParams(options)
            waypointCatchingRange = viaCatchingRangeMeters ?: DEFAULT_CATCHING_RANGE_M
        }
        val engine = RoutingEngine(null, null, segmentDir, nodes, rc, RoutingEngine.BROUTER_ENGINEMODE_ROUTING)
        engine.quite = true
        engine.doRun(timeoutMillis)
        engine.errorMessage?.let { throw RoutingException(it) }
        val track = engine.foundTrack ?: throw RoutingException("No route found")
        return toRoute(track)
    }

    private fun profileParams(o: RouteOptions): MutableMap<String, String> = hashMapOf(
        "road_style" to o.roadStyle.ordinal.toString(),
        "surface_pref" to o.surface.ordinal.toString(),
        "avoid_ferries" to o.avoidFerries.flag(),
        "avoid_motorways" to o.avoidMotorways.flag(),
        "avoid_toll" to o.avoidToll.flag()
    )

    private fun Boolean.flag() = if (this) "1" else "0"

    private fun toRoute(track: OsmTrack): Route {
        val raw = VibeNavBridge.points(track)
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
                unpaved = tags.unpaved, ferry = tags.ferry
            )
        }
        val maneuvers = buildManeuvers(VibeNavBridge.hints(track), points)
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

    private fun buildManeuvers(hints: List<VibeNavBridge.Hint>, points: List<RoutePoint>): List<Maneuver> {
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

    companion object {
        const val PROFILE_FILE = "vibenav-moto.brf"

        /** Start/destination may be a tap in the forest; snap to a road up to this far away. */
        const val DEFAULT_CATCHING_RANGE_M = 1_500.0
    }
}

internal data class WayTags(
    val highway: String?,
    val maxSpeed: Int?,
    val unpaved: Boolean,
    val ferry: Boolean
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
                ferry = map["route"] == "ferry"
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
