package com.curveon.routing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * A give way or stop sign from the road register (Norway: NVDB), as tools/speedlimits/
 * build_speedlimits.py stores it: [lat], [lon] on the road at the sign and [lat2], [lon2] 10 m on.
 * [value] = sign * 10 + kind: sign 202 give way, 204 stop; kind 0 for traffic riding from the
 * first point towards the second, 1/2 for traffic coming out of a side road on the right/left of
 * that direction, 3 a side road on either side.
 */
data class GiveWaySign(val lat: Double, val lon: Double, val lat2: Double, val lon2: Double, val value: Int) {
    val stop get() = value / 10 == 204
    val kind get() = value % 10
}

/**
 * A junction the route meets where the rider gives way, [along] metres from its start (at [lat],
 * [lon], or just after for a sign standing before it), approached heading [bearingDeg].
 */
data class GiveWayOnRoute(val along: Double, val stop: Boolean, val lat: Double = 0.0, val lon: Double = 0.0, val bearingDeg: Double = 0.0)

/** A piece of road from the register, from ([lat1], [lon1]) to ([lat2], [lon2]). */
data class RoadPiece(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double)

object GiveWay {

    /**
     * The junctions on [route] where the rider gives way, in order along it: the signs among [signs]
     * that face the rider, and where the route comes onto a priority road out of another
     * road (side roads there often have no sign of their own). [priorityRoad]: the nearest piece of
     * priority road within ~20 m of a point, if any.
     */
    fun alongRoute(route: Route, signs: List<GiveWaySign>, priorityRoad: ((Double, Double) -> RoadPiece?)? = null): List<GiveWayOnRoute> {
        val pts = route.points
        if (pts.size < 2) return emptyList()
        val kx = 111_320.0 * cos(Math.toRadians(pts[0].lat))
        val found = ArrayList<GiveWayOnRoute>()
        if (priorityRoad != null) found += priorityEntries(route, kx, priorityRoad)
        // The route's segments by grid square, to find those near a sign quickly.
        val grid = HashMap<Long, MutableList<Int>>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            for (y in cell(minOf(a.lat, b.lat)) - 1..cell(maxOf(a.lat, b.lat)) + 1)
                for (x in cell(minOf(a.lon, b.lon) / 2) - 1..cell(maxOf(a.lon, b.lon) / 2) + 1)
                    grid.getOrPut(key(y, x)) { mutableListOf() } += i
        }

        for (s in signs) {
            val near = grid[key(cell(s.lat), cell(s.lon / 2))] ?: continue
            // Where the route passes the sign: (along, offset, segment), one per pass.
            val passes = ArrayList<Triple<Double, Double, Int>>()
            for (i in near) {
                val a = pts[i]; val b = pts[i + 1]
                val ax = (a.lon - s.lon) * kx; val ay = (a.lat - s.lat) * KY
                val sx = (b.lon - a.lon) * kx; val sy = (b.lat - a.lat) * KY
                val len2 = sx * sx + sy * sy
                val t = if (len2 == 0.0) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * sx, ay + t * sy)
                if (d > MAX_OFFSET_M) continue
                val along = a.distanceFromStart + t * (b.distanceFromStart - a.distanceFromStart)
                val same = passes.indexOfFirst { abs(it.first - along) < 40 }
                if (same < 0) passes += Triple(along, d, i)
                else if (d < passes[same].second) passes[same] = Triple(along, d, i)
            }
            // The road at the sign, in metres: its direction (first -> second point).
            val dx = (s.lon2 - s.lon) * kx; val dy = (s.lat2 - s.lat) * KY
            val dLen = hypot(dx, dy)
            if (dLen < 1.0) continue
            for ((along, _, i) in passes) {
                val a = pts[i]; val b = pts[i + 1]
                val applies = if (s.kind == 0) {
                    // Riding the road the sign is on, the way it faces.
                    val rx = (b.lon - a.lon) * kx; val ry = (b.lat - a.lat) * KY
                    val rLen = hypot(rx, ry)
                    rLen > 0.0 && (rx * dx + ry * dy) / (rLen * dLen) > cos(Math.toRadians(MAX_ANGLE_DEG))
                } else {
                    // Coming onto that road out of a side road: a little before, the route is off it,
                    // on the side the sign is for (not riding along it and turning off).
                    val before = pointAt(route, along - SIDE_BACK_M) ?: continue
                    val px = (before.lon - s.lon) * kx; val py = (before.lat - s.lat) * KY
                    val side = (dx * py - dy * px) / dLen // > 0: left of the road's direction
                    abs(side) > SIDE_MIN_M && when (s.kind) {
                        1 -> side < 0
                        2 -> side > 0
                        else -> true
                    }
                }
                if (applies && !(priorityRoad != null && offPriorityRoad(route, along, dx / dLen, dy / dLen, kx, priorityRoad)))
                    found += GiveWayOnRoute(along, s.stop)
            }
        }
        // One per junction (a sign on each side, or give way under stop): stop wins.
        found.sortBy { it.along }
        val out = ArrayList<GiveWayOnRoute>()
        for (g in found) {
            val last = out.lastOrNull()
            if (last != null && g.along - last.along < 30) {
                if (g.stop && !last.stop) out[out.size - 1] = last.copy(stop = true)
            } else out += g
        }
        // Where each is and which way the route comes to it, for a sign on the map.
        return out.map { g ->
            val at = pointAt(route, g.along) ?: return@map g
            val from = pointAt(route, maxOf(0.0, g.along - 15)) ?: at
            g.copy(lat = at.lat, lon = at.lon, bearingDeg = if (from == at) 0.0 else Geo.bearing(from.lat, from.lon, at.lat, at.lon))
        }
    }

    /**
     * Where [route] comes onto a priority road out of another road and rides on along it. (Straight
     * across one is left out: from the geometry alone it can't be told from a bridge over it.)
     */
    private fun priorityEntries(route: Route, kx: Double, priorityRoad: (Double, Double) -> RoadPiece?): List<GiveWayOnRoute> {
        val out = ArrayList<GiveWayOnRoute>()
        var wasOn = true // the start counts as on it: setting off from beside one is no junction
        var along = 0.0
        val end = route.distanceMeters
        while (along <= end) {
            val p = pointAt(route, along) ?: break
            val piece = priorityRoad(p.lat, p.lon)
            val d = piece?.let { offset(it, p, kx, line = false) }
            val on = d != null && d < ON_PRIORITY_M
            if (on && !wasOn && piece != null) {
                // Coming from off its line: not riding along it (through a gap in the register) nor
                // turning off it and back.
                val before = pointAt(route, along - SIDE_BACK_M)
                val stays = (1..(STAYS_M / PRIORITY_STEP_M).toInt()).all { k ->
                    val q = pointAt(route, minOf(end, along + k * PRIORITY_STEP_M)) ?: return@all false
                    priorityRoad(q.lat, q.lon)?.let { offset(it, q, kx, line = false) < ON_PRIORITY_M } == true
                }
                if (stays && before != null && abs(offset(piece, before, kx, line = true)) > SIDE_MIN_M) {
                    // The junction: where the route reaches the priority road's line, a little on.
                    val at = (0..(ON_PRIORITY_M * 2 / PRIORITY_STEP_M).toInt()).map { along + it * PRIORITY_STEP_M }.firstOrNull { a ->
                        pointAt(route, minOf(end, a))?.let { abs(offset(piece, it, kx, line = true)) < AT_LINE_M } == true
                    } ?: along
                    out += GiveWayOnRoute(at, stop = false)
                }
            }
            wasOn = on
            along += PRIORITY_STEP_M
        }
        return out
    }

    /**
     * Whether the route reaches a sign [along] metres on just after turning off a priority road
     * across the sign's road (direction ([ux], [uy])): the rider on a priority road has right of way,
     * and the register now and then has such a sign facing the wrong way. A sign along the priority
     * road itself (where it ends, a roundabout) still counts.
     */
    private fun offPriorityRoad(route: Route, along: Double, ux: Double, uy: Double, kx: Double, priorityRoad: (Double, Double) -> RoadPiece?): Boolean =
        TURNED_OFF_BACK_M.any { back ->
            val p = pointAt(route, along - back) ?: return@any false
            val piece = priorityRoad(p.lat, p.lon) ?: return@any false
            if (offset(piece, p, kx, line = false) >= ON_PRIORITY_M) return@any false
            val px = (piece.lon2 - piece.lon1) * kx; val py = (piece.lat2 - piece.lat1) * KY
            val pLen = hypot(px, py)
            pLen > 0.0 && abs(px * ux + py * uy) / pLen < cos(Math.toRadians(MAX_ANGLE_DEG))
        }

    /** How far [p] is from [piece] (metres); with [line], signed (> 0 left) from the line through it. */
    private fun offset(piece: RoadPiece, p: LatLon, kx: Double, line: Boolean): Double {
        val ax = (piece.lon1 - p.lon) * kx; val ay = (piece.lat1 - p.lat) * KY
        val sx = (piece.lon2 - piece.lon1) * kx; val sy = (piece.lat2 - piece.lat1) * KY
        val len2 = sx * sx + sy * sy
        if (len2 == 0.0) return hypot(ax, ay)
        if (line) return (sx * -ay - sy * -ax) / Math.sqrt(len2)
        val t = (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * sx, ay + t * sy)
    }

    /** The route's point [along] metres from its start; null before the start. */
    private fun pointAt(route: Route, along: Double): LatLon? {
        val pts = route.points
        if (along < 0) return null
        var lo = 0; var hi = pts.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (pts[mid].distanceFromStart <= along) lo = mid else hi = mid
        }
        val a = pts[lo]; val b = pts[hi]
        val span = b.distanceFromStart - a.distanceFromStart
        val f = if (span <= 0) 0.0 else ((along - a.distanceFromStart) / span).coerceIn(0.0, 1.0)
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    private fun cell(deg: Double) = floor(deg / CELL_DEG).toLong()
    private fun key(y: Long, x: Long) = (y shl 32) xor (x and 0xffffffffL)

    private const val KY = 110_574.0
    private const val CELL_DEG = 0.002 // ~220 m north-south
    /** The route passes this close to the sign's point on the road (GPS-free: both are road centrelines). */
    private const val MAX_OFFSET_M = 15.0
    private const val MAX_ANGLE_DEG = 50.0
    /** How far back a route coming out of a side road is looked at, and how far off the road it must be there. */
    private const val SIDE_BACK_M = 30.0
    private const val SIDE_MIN_M = 12.0
    /** The route is on a priority road this close to it, looked at every [PRIORITY_STEP_M]. */
    private const val ON_PRIORITY_M = 8.0
    /** On the priority road's line: the junction itself. */
    private const val AT_LINE_M = 3.0
    private const val PRIORITY_STEP_M = 5.0
    /** Looked back this far from a sign for the priority road the route turned off. */
    private val TURNED_OFF_BACK_M = listOf(10.0, 20.0)
    /** How far the route must carry on along the priority road. */
    private const val STAYS_M = 25.0
}
