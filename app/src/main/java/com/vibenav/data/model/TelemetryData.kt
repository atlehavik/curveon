package com.vibenav.data.model

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
    val leanAngleDeg: Float = 0f,
    val headingDeg: Float = 0f,
    val tripDistanceKm: Float = 0f,
    val tripDurationMinutes: Int = 0,
    val altitudeMeters: Int = 0,
    val hasGpsFix: Boolean = false,
    /** Position is estimated along the route because GPS is lost (tunnel). */
    val isDeadReckoning: Boolean = false,
    val gpsAccuracyMeters: Int = 0,
    val isObdEnabled: Boolean = false,
    val isObdConnected: Boolean = false,
    val useMph: Boolean = false
) {
    val displaySpeed: Int
        get() = if (useMph) (speedKmh * 0.621371f).toInt() else speedKmh.toInt()

    val displaySpeedLimit: Int
        get() = if (useMph) (speedLimitKmh * 0.621371f).toInt() else speedLimitKmh

    val speedUnit: String
        get() = if (useMph) "MPH" else "KM/H"

    val isOverSpeedLimit: Boolean
        get() = speedLimitKmh > 0 && speedKmh > (speedLimitKmh + 3)
}
