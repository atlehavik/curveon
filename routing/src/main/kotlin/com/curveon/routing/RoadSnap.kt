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
}
