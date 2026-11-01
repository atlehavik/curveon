package com.curveon.routing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Finds where a finished route turns back on itself on a road: rides off it, comes round within a short
 * way and rides back beside where it came from. However it is done, in one turn or over a stub, a
 * connector across the lanes or a ramp: the check looks at the line ridden, not at what the map calls
 * the pieces, so a shape of junction nobody thought of is caught too. A hairpin bend also comes round,
 * but opens up again; a roundabout is the proper way to turn back; the ends of a route are its way into
 * and out of the place itself, and so are a ferry's: a quay road can well go on beside the road it came by.
 */
object Reversals {
    private const val WITHIN_M = 150.0
    private const val TURNED_DEG = 150.0
    private const val ENDS_M = 300.0
    private const val ROUNDABOUT_M = 120.0
    private const val FERRY_M = 500.0
    private const val BESIDE_M = 40.0
    private const val MAX_CLOSE_M = 25.0
    private val BACK_ALONG_M = listOf(100.0, 200.0, 300.0)
    private val ROADS = setOf("motorway", "trunk", "primary", "secondary", "tertiary")

    /** Where [route] turns back, in order along it. */
    fun find(route: Route): List<LatLon> = places(route).map { it.at }

    /**
     * A place a route turns back: the middle of the piece it comes round on, and how far around that
     * is only that piece, so that closing it leaves open the carriageways it joins, which may lie 10 m apart.
     */
    data class Place(val at: LatLon, val radiusMeters: Double)

    fun places(route: Route): List<Place> {
        val ferries = route.points.filter { it.ferry }.map { it.distanceFromStart }
        val pts = route.points.filter { !it.ferry }.fold(ArrayList<RoutePoint>()) { out, p ->
            if (out.isEmpty() || Geo.distance(out.last().lat, out.last().lon, p.lat, p.lon) > 1.0) out += p
            out
        }
        val roundabouts = route.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT }.map { it.distanceFromStart }
        val found = ArrayList<Place>()
        var i = 1
        while (i < pts.size - 1) {
            val at = pts[i].distanceFromStart
            if (at < ENDS_M || at > route.distanceMeters - ENDS_M || pts[i].highway !in ROADS || ferries.any { abs(it - at) < FERRY_M }) { i++; continue }
            val before = bearing(pts[i - 1], pts[i])
            var j = i + 1
            var hit = -1
            while (j < pts.size && pts[j].distanceFromStart - at <= WITHIN_M) {
                if (pts[j].highway in ROADS && diff(before, bearing(pts[j - 1], pts[j])) >= TURNED_DEG &&
                    roundabouts.none { abs(it - at) < ROUNDABOUT_M } && ridesBack(pts, i, j)) { hit = j; break }
                j++
            }
            if (hit < 0) { i++; continue }
            found += place(pts, i, hit)
            val past = pts[hit].distanceFromStart + WITHIN_M
            while (i < pts.size - 1 && pts[i].distanceFromStart < past) i++
        }
        return found
    }

    private fun place(pts: List<RoutePoint>, from: Int, to: Int): Place {
        // Where it last rode the way it came, and first rides the way it goes.
        val after = bearing(pts[to - 1], pts[to])
        var i = from
        while (i + 1 < to - 1 && diff(bearing(pts[i], pts[i + 1]), after) >= TURNED_DEG) i++
        val a = pts[i].distanceFromStart
        val b = pts[to - 1].distanceFromStart
        val middle = (a + b) / 2
        val k = (i until to - 1).firstOrNull { pts[it + 1].distanceFromStart >= middle } ?: i
        val p = pts[k]
        val q = pts[minOf(k + 1, to - 1)]
        val span = q.distanceFromStart - p.distanceFromStart
        val t = if (span <= 0) 0.0 else (middle - p.distanceFromStart) / span
        val at = LatLon(p.lat + (q.lat - p.lat) * t, p.lon + (q.lon - p.lon) * t)
        return Place(at, ((b - a) / 2 - 1.0).coerceIn(1.0, MAX_CLOSE_M))
    }

    /** Whether the route after [j] keeps beside the way it came to [i]. */
    private fun ridesBack(pts: List<RoutePoint>, i: Int, j: Int): Boolean {
        val from = pts[j].distanceFromStart
        val first = pts.indexOfFirst { it.distanceFromStart >= pts[i].distanceFromStart - BACK_ALONG_M.last() - WITHIN_M }
        var checked = 0
        for (d in BACK_ALONG_M) {
            val q = pts.firstOrNull { it.distanceFromStart >= from + d } ?: break
            if ((first until i).none { k -> toSegment(q, pts[k], pts[k + 1]) < BESIDE_M }) return false
            checked++
        }
        return checked > 0
    }

    private fun toSegment(p: RoutePoint, a: RoutePoint, b: RoutePoint): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * 110_574.0
        val sx = (b.lon - a.lon) * kx; val sy = (b.lat - a.lat) * 110_574.0
        val len2 = sx * sx + sy * sy
        val t = if (len2 == 0.0) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * sx, ay + t * sy)
    }

    private fun bearing(a: RoutePoint, b: RoutePoint) = Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    private fun diff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)
}
