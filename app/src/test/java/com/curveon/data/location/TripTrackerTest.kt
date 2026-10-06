package com.curveon.data.location

import org.junit.Assert.assertEquals
import org.junit.Test

class TripTrackerTest {

    private fun fix(lat: Double, lon: Double, t: Long, speed: Float? = null, acc: Float = 5f) =
        GpsFix(lat, lon, 100.0, speed, null, acc, t)

    @Test
    fun `accumulates distance and moving time while riding`() {
        val tracker = TripTracker()
        // ~111 m north per step, one step per 5 s => ~80 km/h
        for (i in 0..10) tracker.onFix(fix(60.0 + i * 0.001, 10.0, i * 5_000L))
        assertEquals(1112.0, tracker.stats.distanceMeters, 5.0)
        assertEquals(50_000L, tracker.stats.movingTimeMillis)
        assertEquals(80f, tracker.speedKmh, 1f)
        assertEquals(0f, tracker.headingDeg, 0.5f)
    }

    @Test
    fun `ignores jitter while standing still and keeps heading`() {
        val tracker = TripTracker()
        tracker.onFix(fix(60.0, 10.0, 0L))
        tracker.onFix(fix(60.0, 10.001, 5_000L)) // heading east at ~40 km/h
        val heading = tracker.headingDeg
        for (i in 2..10) tracker.onFix(fix(60.0, 10.001 + (i % 2) * 0.000005, i * 5_000L, speed = 0f))
        assertEquals(heading, tracker.headingDeg, 0.01f)
        assertEquals(0f, tracker.speedKmh, 0.01f)
        assertEquals(TripTracker.distanceMeters(60.0, 10.0, 60.0, 10.001), tracker.stats.distanceMeters, 1.0)
    }

    @Test
    fun `inaccurate fixes do not add distance`() {
        val tracker = TripTracker()
        tracker.onFix(fix(60.0, 10.0, 0L))
        tracker.onFix(fix(60.01, 10.0, 5_000L, acc = 200f))
        assertEquals(0.0, tracker.stats.distanceMeters, 0.01)
    }
}
