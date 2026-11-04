package com.curveon.routing

import kotlin.math.abs

/** Where the speed limit along a route changes to [limit] (km/h): [along] metres from the start, at [lat], [lon], heading [bearingDeg]. */
data class SpeedChange(val along: Double, val lat: Double, val lon: Double, val bearingDeg: Double, val limit: Int)

/**
 * The limit along a route, as its signs show it: what it begins with, where a sign changes it, and the stretches where
 * none is known. [sideRoad]: not navigating, the sign of a road the rider would have to turn onto, which says nothing of their own road.
 * [startSure] false: not navigating, the road ahead turns off so soon that [start] may be read from another road than the rider's.
 */
data class RouteLimits(
    val start: Int?,
    val changes: List<SpeedChange>,
    val unknown: List<ClosedRange<Double>> = emptyList(),
    val sideRoad: SpeedChange? = null,
    val startSure: Boolean = true
) {
    /** The limit [along] metres from the start: it changes as the rider passes a sign. Null where none is known. */
    fun at(along: Double): Int? =
        if (unknown.any { along in it }) null else changes.lastOrNull { it.along <= along }?.limit ?: start

    /** What the limit is at the start, when it can be read right there: a limit first read further on says nothing of this place. */
    val startHere: Int? get() = start.takeIf { unknown.none { it.start <= SpeedSigns.UNKNOWN_M } }
}

object SpeedSigns {
    private const val SAMPLE_M = 25.0
    /** A change counts once it holds for this many samples, so a junction's other road doesn't make signs. */
    private const val HOLD_SAMPLES = 2
    /** How exactly the place of a change is looked for between two samples. */
    private const val EXACT_M = 2.0
    /** A stretch the register misses this short is not read from OpenStreetMap, whose limit may differ. */
    private const val REGISTER_GAP_M = 100.0
    /** A limit that begins where the route turns stands this far into the road it turns onto, not at the road it leaves. */
    private const val INTO_ROAD_M = 40.0
    /** The same past a roundabout's entry, so that it stands past the ring. */
    private const val PAST_ROUNDABOUT_M = 70.0
    /** A limit found this little before a turn belongs to the road turned onto: junctions are not drawn exactly alike in the two maps. */
    private const val BEFORE_TURN_M = 15.0
    /** Not navigating: how far down a side road a limit counts as what that road has. */
    private const val SIDE_ROAD_M = 150.0
    /** No limit read for this far: not known here. A shorter stretch keeps the limit it has. */
    internal const val UNKNOWN_M = 100.0

    private val TURNS = setOf(
        ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT, ManeuverType.SHARP_LEFT,
        ManeuverType.SLIGHT_RIGHT, ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT,
        ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT,
        ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT,
        ManeuverType.U_TURN, ManeuverType.ROUNDABOUT
    )

    /** Turning off this soon, the road ahead says little of the limit where the rider is. */
    private const val START_SURE_M = 75.0
    /** A roundabout's way out this close to the way in is the road going straight on. */
    private const val STRAIGHT_THROUGH_DEG = 45.0
    private const val WAY_IN_M = 25.0

    private val SLIGHT = setOf(ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT)
    private val ROAD_CLASSES = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service", "track")

    /** Where the rider leaves the road that goes straight on. A slight bend or keeping left or right is still straight on. */
    private val LEAVES_ROAD = setOf(
        ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT,
        ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT, ManeuverType.U_TURN
    )

    /**
     * Where the limit changes along [route]: [limitAt] (a country's official limit of the road at a point, heading a way)
     * or else OpenStreetMap's, read every [SAMPLE_M] metres along the line and not only at its corners, which can
     * lie hundreds of metres apart on a straight road. No sign at the start: it is the limit the rider begins with.
     */
    fun along(route: Route, limitAt: (Double, Double, Double) -> Int?): List<SpeedChange> = limits(route, limitAt).changes

    /** As [along], with the limit the rider begins with and where none is known, so the speed card can say the same as the signs. */
    fun limits(route: Route, limitAt: (Double, Double, Double) -> Int?): RouteLimits {
        val pts = route.points
        if (pts.size < 2) return RouteLimits(null, emptyList())
        val end = pts.last().distanceFromStart
        var segment = 0
        var registerAt = Double.NEGATIVE_INFINITY

        /** The route's stretch at [d], and the point there. */
        fun place(d: Double): Pair<Int, LatLon> {
            while (segment > 0 && pts[segment].distanceFromStart > d) segment--
            while (segment < pts.size - 2 && pts[segment + 1].distanceFromStart <= d) segment++
            val a = pts[segment]; val b = pts[segment + 1]
            val len = b.distanceFromStart - a.distanceFromStart
            val t = if (len > 0) ((d - a.distanceFromStart) / len).coerceIn(0.0, 1.0) else 0.0
            return segment to LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }

        fun read(d: Double): Int? {
            val (i, p) = place(d)
            val a = pts[i]; val b = pts[i + 1]
            limitAt(p.lat, p.lon, Geo.bearing(a.lat, a.lon, b.lat, b.lon))?.let { registerAt = d; return it }
            return if (d - registerAt < REGISTER_GAP_M) null else b.maxSpeedKmh // a point's limit is that of the stretch up to it
        }

        val found = ArrayList<Pair<Double, Int>>()
        var stable: Int? = null
        var stableAt = 0.0
        var candidate: Int? = null
        var candidateAt = 0.0
        var count = 0
        var start: Int? = null
        val unknown = ArrayList<ClosedRange<Double>>()
        var readAt = 0.0
        var unknownFrom: Double? = null
        var d = 0.0
        while (d < end) {
            val at = d
            d += SAMPLE_M
            val limit = read(at)
            if (limit == null) {
                if (unknownFrom == null && at - readAt >= UNKNOWN_M) unknownFrom = at
                continue
            }
            unknownFrom?.let { unknown += it..at }
            unknownFrom = null
            readAt = at
            when {
                limit == stable -> { candidate = null; stableAt = at }
                limit == candidate -> if (++count >= HOLD_SAMPLES) {
                    if (stable != null) found += exactly(stableAt, candidateAt, limit, ::read) to limit else start = limit
                    stable = limit; stableAt = at; candidate = null
                }
                else -> { candidate = limit; candidateAt = at; count = 1 }
            }
        }
        unknownFrom?.let { unknown += it..end }
        val changes = found.map { (at, limit) ->
            val along = intoRoad(route, at, end)
            val (i, p) = place(along)
            SpeedChange(along, p.lat, p.lon, Geo.bearing(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon), limit)
        }
        return RouteLimits(start ?: candidate, changes, unknown)
    }

    /**
     * Not navigating: as [limits], for [probe], the road ahead ([RoadAhead]). Past where it leaves the road straight
     * on, it is a guess at a way the rider may not go, so no signs there; with [sideRoads] (a setting) only the limit
     * that begins just past there: what the side road has. Roads often run on through a roundabout, so only a way out
     * to one side counts as leaving.
     */
    fun onRoadAhead(probe: Route, sideRoads: Boolean, limitAt: (Double, Double, Double) -> Int?): RouteLimits {
        val limits = limits(probe, limitAt)
        val leaves = leavesRoadAt(probe) ?: return limits
        val side = limits.changes.firstOrNull { it.along > leaves }?.takeIf { sideRoads && it.along < leaves + SIDE_ROAD_M }
        return limits.copy(changes = limits.changes.filter { it.along <= leaves }, sideRoad = side, startSure = leaves >= START_SURE_M)
    }

    /**
     * Whether a [route] through a roundabout entered [at] metres from its start comes out another way than it went in:
     * the way in against the way to where a sign past the ring stands. Too little of the way in to tell, when the rider
     * is almost there: no.
     */
    private fun outToOneSide(route: Route, at: Double): Boolean {
        if (at < WAY_IN_M || route.points.last().distanceFromStart < at + PAST_ROUNDABOUT_M) return false
        val wayIn = bearingBetween(route, (at - 2 * WAY_IN_M).coerceAtLeast(0.0), at)
        val wayOut = bearingBetween(route, at, at + PAST_ROUNDABOUT_M)
        return abs(Geo.angleDiff(wayIn, wayOut)) > STRAIGHT_THROUGH_DEG
    }

    private fun bearingBetween(route: Route, from: Double, to: Double): Double {
        val a = pointAt(route, from); val b = pointAt(route, to)
        return Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    }

    private fun pointAt(route: Route, d: Double): LatLon {
        val pts = route.points
        val i = pts.indexOfLast { it.distanceFromStart <= d }.coerceIn(0, pts.size - 2)
        val a = pts[i]; val b = pts[i + 1]
        val len = b.distanceFromStart - a.distanceFromStart
        val t = if (len > 0) ((d - a.distanceFromStart) / len).coerceIn(0.0, 1.0) else 0.0
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** Where [probe] leaves the road that goes straight on, in metres from its start; null when it never does. */
    fun leavesRoadAt(probe: Route): Double? =
        probe.maneuvers.firstOrNull { it.type in LEAVES_ROAD || it.type == ManeuverType.ROUNDABOUT && outToOneSide(probe, it.distanceFromStart) || slipsOff(probe, it) }?.distanceFromStart

    /** A slight turn is the road bending, unless it goes onto a lesser road: then it is a slip road off this one. */
    private fun slipsOff(route: Route, m: Maneuver): Boolean {
        if (m.type !in SLIGHT) return false
        val before = route.points.getOrNull(m.pointIndex)?.highway ?: return false
        val after = route.points.asSequence().drop(m.pointIndex + 1).mapNotNull { it.highway }.firstOrNull() ?: return false
        return rank(after) > rank(before)
    }

    /** Bigger roads first; a road's own slip road counts as a lesser road. */
    private fun rank(highway: String): Int {
        val i = ROAD_CLASSES.indexOf(highway.removeSuffix("_link"))
        return if (i < 0) ROAD_CLASSES.size else i * 2 + if (highway.endsWith("_link")) 1 else 0
    }

    /** The place between [from] (the old limit) and [to] (already [limit]) where the limit becomes [limit]. */
    private fun exactly(from: Double, to: Double, limit: Int, read: (Double) -> Int?): Double {
        var low = from
        var high = to
        while (high - low > EXACT_M) {
            val mid = (low + high) / 2
            if (read(mid) == limit) high = mid else low = mid
        }
        return high
    }

    /** Where a change at [at] stands: past the turn, if the route turns just there. */
    private fun intoRoad(route: Route, at: Double, end: Double): Double {
        val turn = route.maneuvers.lastOrNull { it.type in TURNS && it.distanceFromStart <= at + BEFORE_TURN_M } ?: return at
        val into = turn.distanceFromStart + if (turn.type == ManeuverType.ROUNDABOUT) PAST_ROUNDABOUT_M else INTO_ROAD_M
        return if (at < into) minOf(into, end) else at
    }
}
