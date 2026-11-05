package com.curveon.data.model

import kotlin.math.roundToInt

data class TelemetryData(
    val speedKmh: Float = 0f,
    /** 0 = unknown, the sign is hidden. */
    val speedLimitKmh: Int = 0,
    val rpm: Int = 0,
    val maxRpm: Int = 12000,
    val redlineRpm: Int = 10000,
    val gear: String = "N",
    val coolantTempC: Int = 0,
    val batteryVoltage: Float = 0f,
    val fuelPercent: Int = 0,
    val headingDeg: Float = 0f,
    val tripDistanceKm: Float = 0f,
    val tripDurationMinutes: Int = 0,
    val altitudeMeters: Int = 0,
    val hasGpsFix: Boolean = false,
    /** Position is estimated along the route because GPS is lost (tunnel). */
    val isDeadReckoning: Boolean = false,
    /** No real fix for a while (GPS signal lost), navigating or not: the rider's arrow turns to a ghost. */
    val gpsLost: Boolean = false,
    val gpsAccuracyMeters: Int = 0,
    val isObdEnabled: Boolean = false,
    val isObdConnected: Boolean = false,
    val useMph: Boolean = false
) {
    val displaySpeed: Int
        get() = if (useMph) (speedKmh * 0.621371f).toInt() else speedKmh.toInt()

    val displaySpeedLimit: Int
        get() = if (useMph) mphLimit(speedLimitKmh) else speedLimitKmh

    val speedUnit: String
        get() = if (useMph) "MPH" else "KM/H"

    val isOverSpeedLimit: Boolean
        get() = speedLimitKmh > 0 && speedKmh > (speedLimitKmh + 3)

    companion object {
        /**
         * A speed limit sign in mph: limits in mph are steps of 5, and the data keeps a 30 mph road as 50 km/h, which
         * converted straight was 31 (20 mph showed 18, 70 mph 68).
         */
        fun mphLimit(kmh: Int): Int = ((kmh * 0.621371f) / 5f).roundToInt() * 5
    }
}
