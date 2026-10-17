package com.curveon.routing

/**
 * Turns a GPX track into a navigable route: ride from where you are to the track (joining it
 * where you are if you are already on it), then follow the track through dense via points so the
 * router stays on the same roads but still produces turn instructions.
 */
class TrackFollower(private val engine: BRouterEngine) {

    /**
     * [fromStart]: a track picked to ride (rather than one the rider is already riding) joins at its
     * first pass by the rider, so a loop from home is ridden whole even where it passes home again.
     */
    fun route(from: LatLon, track: List<LatLon>, options: RouteOptions, fromStart: Boolean = false): Route {
        require(track.size >= 2) { "Track has no points" }
        val joinIndex = (if (fromStart) firstIndexWithin(from, track, ON_TRACK_M) else
            nearestIndex(from, track).takeIf { Geo.distance(from, track[it]) < ON_TRACK_M }) ?: 0
        val vias = sample(track.subList(joinIndex, track.size), VIA_SPACING_M)
        // Tight snapping keeps us on the track's roads; the approach leg uses normal snapping
        // because the rider may start far from any road the track uses.
        val onTrack = engine.route(vias, options, viaCatchingRangeMeters = VIA_CATCHING_RANGE_M)
        if (Geo.distance(from, vias.first()) < 50) return onTrack
        val approach = engine.route(listOf(from, vias.first()), options)
        return Routes.concat(approach, onTrack)
    }

    /**
     * Via points for making [track] an ordinary trip that keeps to the track's roads with as few points
     * as it takes: starts with one every [START_SPACING_M], routes through them, and where the route
     * leaves the track (a side road the router would not take by itself) adds a point on the track
     * there, then routes again. The ends are not included. Stops at [MAX_VIAS] or after [ROUNDS].
     */
    fun viaPoints(track: List<LatLon>, options: RouteOptions): List<LatLon> {
        val pts = sample(track, CHECK_SPACING_M)
        if (pts.size < 3) return emptyList()
        val along = DoubleArray(pts.size)
        for (i in 1 until pts.size) along[i] = along[i - 1] + Geo.distance(pts[i - 1], pts[i])
        val vias = sortedSetOf<Int>()
        var next = START_SPACING_M
        for (i in 1 until pts.size - 1) if (along[i] >= next) { vias += i; next = along[i] + START_SPACING_M }
        repeat(ROUNDS) {
            val route = try {
                engine.route(listOf(pts.first()) + vias.map { pts[it] } + pts.last(), options)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                return vias.map { pts[it] }
            }
            val line = route.points.map { LatLon(it.lat, it.lon) }
            val off = deviations(pts, line)
            val added = mutableListOf<Int>()
            var i = 0
            while (i < pts.size) {
                if (off[i] <= DEVIATION_M) { i++; continue }
                // One stretch away from the route: a point at its furthest, and more if it is long.
                var worst = i
                var chunkStart = along[i]
                while (i < pts.size && off[i] > DEVIATION_M) {
                    if (off[i] > off[worst]) worst = i
                    if (along[i] - chunkStart >= MAX_STRETCH_M) { added += worst; worst = i; chunkStart = along[i] }
                    i++
                }
                added += worst
            }
            val fresh = added.filter { it in 1 until pts.size - 1 && it !in vias }
            if (fresh.isEmpty()) return vias.map { pts[it] }
            vias += fresh.take(MAX_VIAS - vias.size)
            if (vias.size >= MAX_VIAS) return vias.map { pts[it] }
        }
        return vias.map { pts[it] }
    }

    /** How far each of [pts] is from [line], in metres. */
    private fun deviations(pts: List<LatLon>, line: List<LatLon>): DoubleArray = DoubleArray(pts.size) { k ->
        val p = pts[k]
        val kx = Math.cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        for (j in 0 until line.size - 1) {
            val ax = (line[j].lon - p.lon) * kx; val ay = (line[j].lat - p.lat) * ky
            val bx = (line[j + 1].lon - p.lon) * kx; val by = (line[j + 1].lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            val d = Math.hypot(ax + t * dx, ay + t * dy)
            if (d < best) best = d
        }
        best
    }

    companion object {
        const val START_SPACING_M = 5_000.0
        const val CHECK_SPACING_M = 100.0
        /** The route is off the track where the track is further than this from it. */
        const val DEVIATION_M = 120.0
        /** A stretch away from the route longer than this gets a point for each part of it. */
        const val MAX_STRETCH_M = 2_000.0
        const val MAX_VIAS = 80
        const val ROUNDS = 4
        const val VIA_SPACING_M = 1_000.0
        const val ON_TRACK_M = 300.0
        const val VIA_CATCHING_RANGE_M = 150.0

        fun nearestIndex(p: LatLon, track: List<LatLon>): Int =
            track.indices.minByOrNull { Geo.distance(p, track[it]) } ?: 0

        /** Where the track first comes within [range] of [p], or null if it never does. */
        fun firstIndexWithin(p: LatLon, track: List<LatLon>, range: Double): Int? =
            track.indices.firstOrNull { Geo.distance(p, track[it]) < range }

        /** Every point that is at least [spacing] metres along the track from the previous kept one, plus the end. */
        fun sample(track: List<LatLon>, spacing: Double): List<LatLon> {
            val out = mutableListOf(track.first())
            var acc = 0.0
            for (i in 1 until track.size) {
                acc += Geo.distance(track[i - 1], track[i])
                if (acc >= spacing) {
                    out += track[i]
                    acc = 0.0
                }
            }
            if (out.last() != track.last()) {
                if (Geo.distance(out.last(), track.last()) < spacing / 4 && out.size > 1) out[out.lastIndex] = track.last()
                else out += track.last()
            }
            return out
        }
    }
}
