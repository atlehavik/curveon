package com.curveon.routing

/** Where the speed limit along a route changes to [limit] (km/h): [along] metres from the start, at [lat], [lon], heading [bearingDeg]. */
data class SpeedChange(val along: Double, val lat: Double, val lon: Double, val bearingDeg: Double, val limit: Int)

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

    private val TURNS = setOf(
        ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT, ManeuverType.SHARP_LEFT,
        ManeuverType.SLIGHT_RIGHT, ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT,
        ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT,
        ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT,
        ManeuverType.U_TURN, ManeuverType.ROUNDABOUT
    )

    /**
     * Where the limit changes along [route]: [limitAt] (a country's official limit of the road at a point, heading a way)
     * or else OpenStreetMap's, read every [SAMPLE_M] metres along the line and not only at its corners, which can
     * lie hundreds of metres apart on a straight road. No sign at the start: it is the limit the rider begins with.
     */
    fun along(route: Route, limitAt: (Double, Double, Double) -> Int?): List<SpeedChange> {
        val pts = route.points
        if (pts.size < 2) return emptyList()
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
        var d = 0.0
        while (d < end) {
            val at = d
            d += SAMPLE_M
            val limit = read(at) ?: continue
            when {
                limit == stable -> { candidate = null; stableAt = at }
                limit == candidate -> if (++count >= HOLD_SAMPLES) {
                    if (stable != null) found += exactly(stableAt, candidateAt, limit, ::read) to limit
                    stable = limit; stableAt = at; candidate = null
                }
                else -> { candidate = limit; candidateAt = at; count = 1 }
            }
        }
        return found.map { (at, limit) ->
            val along = intoRoad(route, at, end)
            val (i, p) = place(along)
            SpeedChange(along, p.lat, p.lon, Geo.bearing(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon), limit)
        }
    }

    /**
     * Not navigating: as [along], for [probe], the road ahead ([RoadAhead]). Past its first turn it is a guess at a
     * way the rider may not go, so only the limit that begins just past that turn: what the side road has. Roads
     * often run on through a roundabout, so that is not a turn here.
     */
    fun onRoadAhead(probe: Route, limitAt: (Double, Double, Double) -> Int?): List<SpeedChange> {
        val changes = along(probe, limitAt)
        val turn = probe.maneuvers.firstOrNull { it.type in TURNS && it.type != ManeuverType.ROUNDABOUT }?.distanceFromStart ?: return changes
        val past = changes.firstOrNull { it.along > turn }?.takeIf { it.along < turn + SIDE_ROAD_M }
        return changes.filter { it.along <= turn } + listOfNotNull(past)
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
