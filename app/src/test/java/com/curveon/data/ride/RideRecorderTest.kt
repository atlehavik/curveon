package com.curveon.data.ride

import com.curveon.data.location.GpsFix
import com.curveon.data.model.Ride
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
    fun `a ride stopped by hand does not start again until the bike has stood still`() {
        ride(90)
        assertNotNull(recorder.stop())
        assertNull(recorder.current)
        ride(30)
        assertNull(recorder.current)
        repeat(3) { clock += 1000; recorder.onFix(GpsFix(60.0, 10.0, 100.0, 0f, 0f, 5f, clock), 0f, 0f) }
        ride(30)
        assertNotNull(recorder.current)
    }

    @Test
    fun `merging joins rides in time order with times counted from the first start`() {
        val a = Ride(id = 1, title = "Voss → Flåm", startMs = 1_000_000, endMs = 1_600_000, distanceMeters = 50_000.0, movingMillis = 500_000,
            maxSpeedKmh = 90f, ascentMeters = 300.0, maxLeanLeftDeg = 30f, track = "60.00000,6.00000,10,0.0;60.10000,6.00000,20,60.0")
        val b = Ride(id = 2, title = "Flåm → Lærdal", startMs = 4_000_000, endMs = 4_500_000, distanceMeters = 40_000.0, movingMillis = 400_000,
            maxSpeedKmh = 100f, ascentMeters = 200.0, maxLeanRightDeg = 40f, track = "60.20000,6.00000,30,0.0;60.30000,6.00000,40,30.0")
        val m = RideRecorder.merge(listOf(b, a))
        assertEquals("Voss → Lærdal", m.title)
        assertEquals(1_000_000L, m.startMs)
        assertEquals(4_500_000L, m.endMs)
        assertEquals(90_000.0, m.distanceMeters, 0.1)
        assertEquals(900_000L, m.movingMillis)
        assertEquals(100f, m.maxSpeedKmh, 0.1f)
        assertEquals(500.0, m.ascentMeters, 0.1)
        assertEquals(30f, m.maxLeanLeftDeg, 0.1f)
        assertEquals(40f, m.maxLeanRightDeg, 0.1f)
        assertEquals(listOf(0.0, 60.0, 3000.0, 3030.0), RideRecorder.parseTrack(m.track).map { it.secondsFromStart })
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
    }

    private fun standStill(ms: Long, lat: Double) {
        clock += ms
        recorder.onFix(GpsFix(lat, 10.0, 100.0, 0f, 0f, 5f, clock), 0f, 0f)
    }

    @Test
    fun `a stop of five minutes ends the ride, a shorter one does not`() {
        val lat = ride(120)
        standStill(RideRecorder.END_AFTER_STOP_MS - 30_000, lat)
        assertNotNull(recorder.current)
        assertEquals(RideRecorder.Event.FINISHED, recorder.onFix(GpsFix(lat, 10.0, 100.0, 0f, 0f, 5f, clock + 31_000).also { clock += 31_000 }, 0f, 0f))
    }

    @Test
    fun `riding again after a finished ride offers to go on with it, and going on joins the two`() {
        val lat = ride(120)
        standStill(RideRecorder.END_AFTER_STOP_MS + 1000, lat)
        val first = recorder.finish()!!.copy(id = 7)
        assertNull(recorder.resumeOffer)
        clock += 30 * 60_000L
        ride(30, lat)
        assertEquals(first.startMs, recorder.resumeOffer!!.startMs)
        val merged = recorder.resume(first)!!
        assertEquals(7L, merged.id)
        assertEquals(first.startMs, merged.startMs)
        assertTrue(merged.distanceMeters > first.distanceMeters)
        assertNull(recorder.resumeOffer)
        ride(10, lat)
        assertEquals(7L, recorder.current!!.id)
    }

    @Test
    fun `a new ride, a hand stop and a long wait are not offered`() {
        var lat = ride(120)
        standStill(RideRecorder.END_AFTER_STOP_MS + 1000, lat)
        recorder.finish()
        clock += 30 * 60_000L
        lat = ride(90, lat)
        recorder.declineResume()
        assertNull(recorder.resumeOffer)
        assertNotNull(recorder.stop())
        standStill(5_000, lat); standStill(5_000, lat)
        lat = ride(60, lat)
        assertNull(recorder.resumeOffer)
        standStill(RideRecorder.END_AFTER_STOP_MS + 1000, lat)
        recorder.finish()
        clock += RideRecorder.RESUME_WINDOW_MS + 1000
        ride(30, lat)
        assertNull(recorder.resumeOffer)
    }
}
