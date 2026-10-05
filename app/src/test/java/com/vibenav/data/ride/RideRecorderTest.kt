package com.vibenav.data.ride

import com.vibenav.data.location.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRecorderTest {
    private var clock = 0L
    private val recorder = RideRecorder { clock }

    /** Rides north at 72 km/h (20 m/s) for [seconds], one fix per second. */
    private fun ride(seconds: Int, startLat: Double = 60.0, lean: Float = 0f): Double {
        var lat = startLat
        repeat(seconds) {
            clock += 1000
            lat += 20.0 / 111_320
            recorder.onFix(GpsFix(lat, 10.0, 100.0, 20f, 0f, 5f, clock), 72f, lean)
        }
        return lat
    }

    @Test
    fun `starts after riding a little, ignores standing still`() {
        repeat(30) { clock += 1000; recorder.onFix(GpsFix(60.0, 10.0, 100.0, 0f, 0f, 5f, clock), 0f, 0f) }
        assertNull(recorder.current)
        ride(5)
        assertNull(recorder.current)
        ride(10)
        assertNotNull(recorder.current)
    }

    @Test
    fun `accumulates distance and lean, finishes after a long stop`() {
        val lat = ride(120, lean = -35f)
        ride(30, lat, lean = 20f)
        val r = recorder.current!!
        assertTrue("distance ${r.distanceMeters}", r.distanceMeters in 2_500.0..3_100.0)
        assertEquals(35f, r.maxLeanLeftDeg, 0.1f)
        assertEquals(20f, r.maxLeanRightDeg, 0.1f)
        assertEquals(72f, r.maxSpeedKmh, 0.1f)
        clock += RideRecorder.END_AFTER_STOP_MS + 1000
        assertEquals(RideRecorder.Event.FINISHED, recorder.onFix(GpsFix(lat, 10.0, 100.0, 0f, 0f, 5f, clock), 0f, 0f))
        val done = recorder.finish()!!
        assertTrue("points ${RideRecorder.parseTrack(done.track).size}", RideRecorder.parseTrack(done.track).size > 100)
        assertTrue(RideRecorder.toGpx(done).contains("<trkpt"))
    }
}
