package com.curveon.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryDataTest {
    @Test
    fun `speed limits in mph are the signs' own steps of five`() {
        // The data keeps UK limits as their km/h equivalents: 30 mph is 50 km/h, 20 mph 30, 70 mph 110.
        assertEquals(30, TelemetryData.mphLimit(50))
        assertEquals(20, TelemetryData.mphLimit(30))
        assertEquals(60, TelemetryData.mphLimit(100))
        assertEquals(70, TelemetryData.mphLimit(110))
        // Abroad in mph: 80 km/h is about 50.
        assertEquals(50, TelemetryData.mphLimit(80))
        assertEquals(30, TelemetryData(speedLimitKmh = 50, useMph = true).displaySpeedLimit)
        assertEquals(50, TelemetryData(speedLimitKmh = 50).displaySpeedLimit)
    }
}
