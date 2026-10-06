package com.curveon.data.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class TripStats(
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val maxSpeedKmh: Float = 0f
)

/**
 * Turns a stream of GPS fixes into speed, heading and trip statistics.
 * Pure Kotlin so it can be unit tested without Android.
 */
class TripTracker {

    var stats = TripStats()
        private set

    /** Last heading we trust; GPS bearing is noise when standing still, so we hold the old one. */
    var headingDeg: Float = 0f
        private set

    var speedKmh: Float = 0f
        private set

    private var lastFix: GpsFix? = null

    fun reset() {
        stats = TripStats()
        lastFix = null
    }

    fun onFix(fix: GpsFix) {
        val prev = lastFix
        val accurate = (fix.accuracyMeters ?: 0f) <= MAX_ACCURACY_M

        val derivedSpeedMps = if (prev != null && fix.timeMillis > prev.timeMillis) {
            distanceMeters(prev, fix) / ((fix.timeMillis - prev.timeMillis) / 1000.0)
        } else 0.0
        val speedMps = fix.speedMps?.toDouble() ?: derivedSpeedMps
        speedKmh = (speedMps * 3.6).toFloat().coerceAtLeast(0f)

        if (speedKmh >= MIN_HEADING_SPEED_KMH) {
            headingDeg = fix.bearingDeg
                ?: prev?.let { bearingDeg(it, fix) }
                ?: headingDeg
        }

        if (prev != null && accurate && fix.timeMillis > prev.timeMillis) {
            val d = distanceMeters(prev, fix)
            val moving = speedKmh >= MIN_MOVING_SPEED_KMH
            stats = stats.copy(
                distanceMeters = stats.distanceMeters + if (moving) d else 0.0,
                movingTimeMillis = stats.movingTimeMillis + if (moving) fix.timeMillis - prev.timeMillis else 0L,
                maxSpeedKmh = maxOf(stats.maxSpeedKmh, speedKmh)
            )
        }
        if (accurate || prev == null) lastFix = fix
    }

    companion object {
        const val MAX_ACCURACY_M = 30f
        const val MIN_MOVING_SPEED_KMH = 3f
        const val MIN_HEADING_SPEED_KMH = 5f
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun distanceMeters(a: GpsFix, b: GpsFix) =
            distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
        }

        fun bearingDeg(a: GpsFix, b: GpsFix): Float {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
        }
    }
}
