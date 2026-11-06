package com.curveon.routing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** Puts the rider on a road line: used to draw them on the road they ride when there is no route. */
object RoadSnap {

    /**
     * The point nearest [p] within [maxM] metres on one of [lines] whose piece there runs within [maxAngleDeg]
     * of [headingDeg] (either way along it, so a road crossing at a junction doesn't win), and that piece's
     * bearing the way the rider goes. Null when there is none.
     */
    fun near(p: LatLon, lines: List<List<LatLon>>, headingDeg: Double, maxM: Double, maxAngleDeg: Double): Pair<LatLon, Double>? {
        val kx = cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_574.0
        var best: Pair<LatLon, Double>? = null
        var bestD = maxM
        for (line in lines) for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val sx = (b.lon - a.lon) * kx; val sy = (b.lat - a.lat) * ky
            val len2 = sx * sx + sy * sy
            if (len2 < 1.0) continue
            val t = (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * sx, ay + t * sy)
            if (d >= bestD) continue
            val bearing = (Math.toDegrees(atan2(sx, sy)) + 360) % 360
            val off = abs(Geo.angleDiff(headingDeg, bearing)) // 0 = the same way, 180 = the other way
            if (minOf(off, 180 - off) > maxAngleDeg) continue
            bestD = d
            best = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)) to (if (off <= 90) bearing else (bearing + 180) % 360)
        }
        return best
    }

    /**
     * The road through [from] (a point on one of [lines], as [near] gives it) as a line to carry the rider along
     * between fixes, so the arrow follows its bends instead of running straight off them: [behindM] metres back
     * and [aheadM] metres on the way [bearingDeg] points, from line to line where one carries on from another
     * (a junction, a bridge, the edge of a map tile), taking the one that goes on most nearly straight. Where the
     * lines read run out it goes on straight. Returns the line and how far along it [from] lies.
     */
    fun path(from: LatLon, lines: List<List<LatLon>>, bearingDeg: Double, behindM: Double, aheadM: Double): Pair<Route, Double> {
        val kx = cos(Math.toRadians(from.lat)) * 111_320.0
        val ky = 110_574.0
        val xy = lines.map { line -> line.map { (it.lon - from.lon) * kx to (it.lat - from.lat) * ky } }
        val back = walk(xy, (bearingDeg + 180) % 360, behindM)
        val ahead = walk(xy, bearingDeg, aheadM)
        val pts = back.asReversed() + ahead.drop(1)
        var d = 0.0
        val points = pts.mapIndexed { i, (x, y) ->
            if (i > 0) d += hypot(x - pts[i - 1].first, y - pts[i - 1].second)
            RoutePoint(from.lat + y / ky, from.lon + x / kx, 0.0, d, 0.0, null, null, false, false)
        }
        var behind = 0.0
        for (i in 1 until back.size) behind += hypot(back[i].first - back[i - 1].first, back[i].second - back[i - 1].second)
        return Route(points, emptyList(), d, 0.0, 0.0, 0, 0.0, 0) to behind
    }

    /** Points (metres east/north of the start) from the start along the roads [maxM] metres the way [bearingDeg] points. */
    private fun walk(xy: List<List<Pair<Double, Double>>>, bearingDeg: Double, maxM: Double): List<Pair<Double, Double>> {
        val out = mutableListOf(0.0 to 0.0)
        var heading = bearingDeg
        var left = maxM
        val used = HashSet<Int>()
        while (left > 0.5) {
            val (x, y) = out.last()
            // The line here that runs on nearest the way the rider goes: the one already on at the start, the one
            // carrying on at a line's end. Not a road turning off, nor a line that ends here.
            var best: Triple<Int, Int, Double>? = null // line, segment, t
            var bestForward = true
            var bestScore = Double.MAX_VALUE
            for ((k, line) in xy.withIndex()) {
                if (k in used) continue
                for (s in 0 until line.size - 1) {
                    val (ax, ay) = line[s]; val (bx, by) = line[s + 1]
                    val sx = bx - ax; val sy = by - ay
                    val len2 = sx * sx + sy * sy
                    if (len2 < 0.01) continue
                    val t = (((x - ax) * sx + (y - ay) * sy) / len2).coerceIn(0.0, 1.0)
                    val dist = hypot(ax + t * sx - x, ay + t * sy - y)
                    if (dist > JOIN_M) continue
                    val bearing = (Math.toDegrees(atan2(sx, sy)) + 360) % 360
                    val off = abs(Geo.angleDiff(heading, bearing))
                    val forward = off <= 90
                    val turn = if (forward) off else 180 - off
                    if (turn > MAX_TURN_DEG) continue
                    // Nothing left of this line that way: it ends here.
                    if (if (forward) s == line.size - 2 && t > 0.999 else s == 0 && t < 0.001) continue
                    val score = turn + dist * 10
                    if (score < bestScore) { bestScore = score; best = Triple(k, s, t); bestForward = forward }
                }
            }
            val (k, s, t) = best ?: break
            used += k
            val line = xy[k]
            val (ax, ay) = line[s]; val (bx, by) = line[s + 1]
            var px = ax + t * (bx - ax); var py = ay + t * (by - ay)
            var i = if (bestForward) s + 1 else s
            while (left > 0.5 && i in line.indices) {
                val (nx, ny) = line[i]
                val seg = hypot(nx - px, ny - py)
                if (seg > 0.01) heading = (Math.toDegrees(atan2(nx - px, ny - py)) + 360) % 360
                if (seg >= left) {
                    val f = left / seg
                    px += (nx - px) * f; py += (ny - py) * f
                    left = 0.0
                } else {
                    px = nx; py = ny
                    left -= seg
                    i += if (bestForward) 1 else -1
                }
                out += px to py
            }
        }
        if (left > 0.5) {
            val (x, y) = out.last()
            val h = Math.toRadians(heading)
            out += (x + left * kotlin.math.sin(h)) to (y + left * cos(h))
        }
        return out
    }

    /** A line carries on from another when it passes within this of where that one ends (tile edges, rounding). */
    private const val JOIN_M = 4.0
    /** A line turning off more than this from the way the rider goes is another road, not the one ridden. */
    private const val MAX_TURN_DEG = 50.0
}
