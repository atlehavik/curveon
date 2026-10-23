package com.curveon.routing

import kotlin.math.abs

/**
 * Turns a GPX track into a navigable route: ride from where you are to the track (joining it
 * where you are if you are already on it), then follow the track through dense via points so the
 * router stays on the same roads but still produces turn instructions.
 */
class TrackFollower(private val engine: BRouterEngine) {

    /**
     * [fromStart]: a track picked to ride (rather than one the rider is already riding) joins at its
     * first pass by the rider, so a loop from home is ridden whole even where it passes home again.
     * Otherwise (a trip a friend shared) it is joined where the rider meets it, the way it goes; [headingDeg]
     * is the way the rider is riding, if known.
     */
    fun route(from: LatLon, track: List<LatLon>, options: RouteOptions, fromStart: Boolean = false, headingDeg: Double? = null): Route {
        require(track.size >= 2) { "Track has no points" }
        val joinIndex = (if (fromStart) firstIndexWithin(from, track, ON_TRACK_M) else
            nearestIndex(from, track).takeIf { Geo.distance(from, track[it]) < ON_TRACK_M }) ?: 0
        val vias = sample(track.subList(joinIndex, track.size), VIA_SPACING_M)
        // Tight snapping keeps us on the track's roads; the approach leg uses normal snapping
        // because the rider may start far from any road the track uses.
        val onTrack = engine.route(vias, options, viaCatchingRangeMeters = VIA_CATCHING_RANGE_M)
        if (!fromStart) joinAlong(from, onTrack, options, headingDeg)?.let { return it }
        if (Geo.distance(from, vias.first()) < 50) return onTrack
        val approach = engine.route(listOf(from, vias.first()), options)
        return Routes.concat(approach, onTrack)
    }

    /**
     * Onto [line] riding the way it goes. Joining at the nearest point on it sent a rider who met it from the
     * wrong side to that point and round on the road there (Bømlo, 2026-10-23, in an 80 zone). So: the place
     * nearest the rider and a few further along are tried, a way there that arrives against the line, turns
     * round, or (with [headingDeg]) starts by going back is dropped, and the quickest to the end wins. Null when
     * every way does one of those.
     */
    private fun joinAlong(from: LatLon, line: Route, options: RouteOptions, headingDeg: Double?): Route? {
        val (along, off) = nearestAlong(from, line)
        if (off < ON_LINE_M && (headingDeg == null || abs(Geo.angleDiff(headingDeg, Routes.pointAt(line, along).second)) < 90)) {
            return Routes.slice(line, along)
        }
        val joins = (listOf(along) + OffRoutePlanner.JOIN_AHEAD_M.map { along + it }).filter { it < line.distanceMeters - 50 }
        var noGo = emptyList<NoGo>()
        val ways = joins.mapIndexedNotNull { i, at ->
            runCatching {
                val (target, bearing) = Routes.pointAt(line, at)
                val leg = when {
                    headingDeg == null -> engine.route(listOf(from, target), options, tracksAsLastResort = false)
                    // The ways back found once (they are right behind the rider), then kept for the other joins.
                    i == 0 -> OffRoutePlanner(engine).forwardOnly(from, headingDeg, listOf(target), options).also { noGo = it.first }.second
                    else -> engine.route(listOf(from, target), options, startHeadingDeg = headingDeg, noGo = noGo, tracksAsLastResort = false)
                } ?: return@runCatching null
                if (OffRoutePlanner.uTurnsAhead(leg) || OffRoutePlanner.arrivesAgainst(leg, bearing) ||
                    (headingDeg != null && OffRoutePlanner.turnsBack(leg, from, headingDeg))) return@runCatching null
                val joined = if (line.distanceMeters - at > 30) Routes.joinLegs(listOf(leg, Routes.slice(line, at))) else leg
                joined to leg.durationSeconds + line.durationSeconds - Routes.secondsAt(line, at)
            }.getOrNull()
        }
        return ways.minByOrNull { it.second }?.first
    }

    /** How far along [line] the point nearest [p] is, and how far [p] is from it, in metres. */
    private fun nearestAlong(p: LatLon, line: Route): Pair<Double, Double> {
        val pts = line.points
        val kx = Math.cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until pts.size - 1) {
            val ax = (pts[i].lon - p.lon) * kx; val ay = (pts[i].lat - p.lat) * ky
            val bx = (pts[i + 1].lon - p.lon) * kx; val by = (pts[i + 1].lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            val d = Math.hypot(ax + t * dx, ay + t * dy)
            if (d < best) {
                best = d
                bestAlong = pts[i].distanceFromStart + t * (pts[i + 1].distanceFromStart - pts[i].distanceFromStart)
            }
        }
        return bestAlong to best
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
        // A long track starts with fewer points, so there is room left to add where the route leaves it.
        val spacing = maxOf(START_SPACING_M, along.last() / START_VIAS_MAX)
        var next = spacing
        for (i in 1 until pts.size - 1) if (along[i] >= next) { vias += i; next = along[i] + spacing }
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
            vias += fresh.take((MAX_VIAS - vias.size).coerceAtLeast(0))
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
        const val MAX_STRETCH_M = 1_000.0
        const val START_VIAS_MAX = 60
        const val MAX_VIAS = 500
        const val ROUNDS = 8
        const val VIA_SPACING_M = 1_000.0
        const val ON_TRACK_M = 300.0
        const val VIA_CATCHING_RANGE_M = 150.0
        /** Nearer the line than this, the rider is on it. */
        const val ON_LINE_M = 30.0

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
