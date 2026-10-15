package com.curveon.data.location

import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.Route
import com.curveon.routing.Routes
import kotlin.math.cos
import kotlin.math.exp

/**
 * Where to draw the rider right now, between GPS fixes. The receiver gives one fix a second, and
 * that fix is already a little old when it arrives; moving the arrow (and the map) only when a fix
 * comes in makes it hop along a second behind the bike. Instead the last fix is carried forward at
 * the speed it reported: along the route line when the rider is on it (so bends are followed), or
 * straight ahead otherwise, plus [LEAD_S] for the receiver's own delay. When a new fix shows the
 * guess was a little off, the difference is faded out over [CORRECTION_TAU_S] instead of jumping.
 *
 * Pure Kotlin so it can be tested and replayed against logged rides; times are System.nanoTime().
 */
class RiderMotion {

    /** One reading to carry forward. [along] is set when [lat]/[lon] is snapped onto [route]. */
    data class Fix(
        val lat: Double,
        val lon: Double,
        val headingDeg: Double,
        val speedMps: Double,
        /** When the position was measured (System.nanoTime() base). */
        val atNanos: Long,
        val route: Route? = null,
        val along: Double? = null
    )

    data class Pose(val lat: Double, val lon: Double, val bearingDeg: Double)

    private var fix: Fix? = null
    /** Correction still to fade out, metres east/north, and when it was taken. */
    private var offE = 0.0
    private var offN = 0.0
    private var offAt = 0L
    private var bearing = Double.NaN
    private var lastSampleAt = 0L

    val hasFix get() = fix != null

    fun onFix(f: Fix, nowNanos: Long) {
        val old = fix
        if (old != null) {
            // Where we were showing the rider vs where the new fix says they are now.
            val shown = predict(old, nowNanos)
            val next = predict(f, nowNanos)
            val kx = metresPerDegLon(next.first.lat)
            val e = (shown.first.lon - next.first.lon) * kx + currentOffset(nowNanos).first
            val n = (shown.first.lat - next.first.lat) * M_PER_DEG_LAT + currentOffset(nowNanos).second
            if (e * e + n * n < JUMP_M * JUMP_M) { offE = e; offN = n } else { offE = 0.0; offN = 0.0 }
            offAt = nowNanos
        }
        fix = f
    }

    fun reset() { fix = null; offE = 0.0; offN = 0.0; bearing = Double.NaN }

    fun sample(nowNanos: Long): Pose? {
        val f = fix ?: return null
        val (p, targetBearing) = predict(f, nowNanos)
        val (e, n) = currentOffset(nowNanos)
        val lat = p.lat + n / M_PER_DEG_LAT
        val lon = p.lon + e / metresPerDegLon(p.lat)
        val dt = if (lastSampleAt == 0L) 1.0 else ((nowNanos - lastSampleAt) / 1e9).coerceIn(0.0, 1.0)
        lastSampleAt = nowNanos
        bearing = when {
            bearing.isNaN() -> targetBearing
            // Standing still the heading is noise: hold it.
            f.speedMps < MIN_SPEED_MPS -> bearing
            else -> (bearing + Geo.angleDiff(bearing, targetBearing) * (1 - exp(-dt / BEARING_TAU_S)) + 360.0) % 360.0
        }
        return Pose(lat, lon, bearing)
    }

    private fun currentOffset(nowNanos: Long): Pair<Double, Double> {
        if (offE == 0.0 && offN == 0.0) return 0.0 to 0.0
        val k = exp(-((nowNanos - offAt) / 1e9).coerceAtLeast(0.0) / CORRECTION_TAU_S)
        return offE * k to offN * k
    }

    /** The fix carried forward to [nowNanos]: position and the direction of travel there. */
    private fun predict(f: Fix, nowNanos: Long): Pair<LatLon, Double> {
        val moving = f.speedMps >= MIN_SPEED_MPS
        // The fix's age (never more than MAX_AHEAD_S) plus the lead; a negative lead (set by hand,
        // Settings → Navigation) can put the arrow behind the fix.
        val dt = if (!moving) 0.0 else ((nowNanos - f.atNanos) / 1e9).coerceIn(0.0, MAX_AHEAD_S) + LEAD_S
        val ahead = f.speedMps * dt
        val route = f.route
        val along = f.along
        if (route != null && along != null) {
            val d = along + ahead
            val (p, _) = Routes.pointAt(route, d)
            // Direction from a chord around the point, so the arrow turns smoothly through a bend
            // instead of snapping at every corner of the line.
            val a = Routes.pointAt(route, d - BEARING_CHORD_M).first
            val b = Routes.pointAt(route, d + BEARING_CHORD_M).first
            val bearing = if (Geo.distance(a, b) > 1) Geo.bearing(a.lat, a.lon, b.lat, b.lon) else f.headingDeg
            return p to bearing
        }
        val here = LatLon(f.lat, f.lon)
        return (if (ahead > 0) Geo.destination(here, f.headingDeg, ahead) else here) to f.headingDeg
    }

    companion object {
        /**
         * The receiver's own delay between measuring and reporting, on top of the fix's age. A test:
         * the rider sets it in Settings → Navigation, [MIN_LEAD_S]…[MAX_LEAD_S] in [LEAD_STEP_S] steps.
         */
        var LEAD_S = DEFAULT_LEAD_S
        const val DEFAULT_LEAD_S = 0.25
        const val MAX_LEAD_S = 3.0
        const val MIN_LEAD_S = -3.0
        const val LEAD_STEP_S = 0.25
        /** Never guess further ahead than this: GPS gone means wait, not run away. */
        const val MAX_AHEAD_S = 2.0
        const val CORRECTION_TAU_S = 0.35
        const val BEARING_TAU_S = 0.35
        const val BEARING_CHORD_M = 12.0
        const val MIN_SPEED_MPS = 1.4
        /** A correction bigger than this (tunnel exit, rejoining) is shown at once. */
        const val JUMP_M = 120.0
        private const val M_PER_DEG_LAT = 110_574.0
        private fun metresPerDegLon(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))
    }
}
