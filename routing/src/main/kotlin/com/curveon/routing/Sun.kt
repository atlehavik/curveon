package com.curveon.routing

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Low-precision solar position (±0.5°), plenty to decide between a day and a night map. */
object Sun {
    /** Sun elevation above the horizon in degrees at [timeMillis] (UTC epoch) for a position. */
    fun elevationDeg(lat: Double, lon: Double, timeMillis: Long): Double {
        val d = timeMillis / 86_400_000.0 + 2440587.5 - 2451545.0 // days since J2000
        val g = Math.toRadians(norm(357.529 + 0.98560028 * d))
        val q = norm(280.459 + 0.98564736 * d)
        val l = Math.toRadians(norm(q + 1.915 * sin(g) + 0.020 * sin(2 * g)))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = Math.toDegrees(atan2(cos(e) * sin(l), cos(l)))
        val dec = asin(sin(e) * sin(l))
        val gmstDeg = norm((18.697374558 + 24.06570982441908 * d) * 15.0)
        val ha = Math.toRadians(norm(gmstDeg + lon - ra))
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha)))
    }

    /** Daylight including civil twilight; switches the map a little after sunset, as a rider's eyes adapt. */
    fun isDay(lat: Double, lon: Double, timeMillis: Long) = elevationDeg(lat, lon, timeMillis) > -4.0

    private fun norm(deg: Double) = ((deg % 360.0) + 360.0) % 360.0
}
