package com.vibenav.data.model

data class TelemetryData(
    val speedKmh: Float = 0f,
    val speedLimitKmh: Int = 80,
    val rpm: Int = 1200,
    val maxRpm: Int = 12000,
    val redlineRpm: Int = 10000,
    val gear: String = "N",
    val coolantTempC: Int = 88,
    val batteryVoltage: Float = 14.1f,
    val fuelPercent: Int = 78,
    val leanAngleDeg: Float = 0f,
    val headingDeg: Float = 45f,
    val tripDistanceKm: Float = 14.2f,
    val tripDurationMinutes: Int = 22,
    val altitudeMeters: Int = 340,
    val isObdEnabled: Boolean = true,
    val isObdConnected: Boolean = true,
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
