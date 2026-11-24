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
    private fun ride(seconds: Int, startLat: Double = 60.0): Double {
        var lat = startLat
        repeat(seconds) {
            clock += 1000
            lat += 20.0 / 111_320
            recorder.onFix(GpsFix(lat, 10.0, 100.0, 20f, 0f, 5f, clock), 72f)
        }
        return lat
    }

    @Test
    fun `starts after riding a little, ignores standing still`() {
        repeat(30) { clock += 1000; recorder.onFix(GpsFix(60.0, 10.0, 100.0, 0f, 0f, 5f, clock), 0f) }
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
        repeat(3) { clock += 1000; recorder.onFix(GpsFix(60.0, 10.0, 100.0, 0f, 0f, 5f, clock), 0f) }
        ride(30)
        assertNotNull(recorder.current)
    }

    @Test
    fun `merging joins rides in time order with times counted from the first start`() {
        val a = Ride(id = 1, title = "Voss → Flåm", startMs = 1_000_000, endMs = 1_600_000, distanceMeters = 50_000.0, movingMillis = 500_000,
            maxSpeedKmh = 90f, ascentMeters = 300.0, track = "60.00000,6.00000,10,0.0;60.10000,6.00000,20,60.0")
        val b = Ride(id = 2, title = "Flåm → Lærdal", startMs = 4_000_000, endMs = 4_500_000, distanceMeters = 40_000.0, movingMillis = 400_000,
            maxSpeedKmh = 100f, ascentMeters = 200.0, track = "60.20000,6.00000,30,0.0;60.30000,6.00000,40,30.0")
        val m = RideRecorder.merge(listOf(b, a))
        assertEquals("Voss → Lærdal", m.title)
        assertEquals(1_000_000L, m.startMs)
        assertEquals(4_500_000L, m.endMs)
        assertEquals(90_000.0, m.distanceMeters, 0.1)
        assertEquals(900_000L, m.movingMillis)
        assertEquals(100f, m.maxSpeedKmh, 0.1f)
        assertEquals(500.0, m.ascentMeters, 0.1)
        assertEquals(listOf(0.0, 60.0, 3000.0, 3030.0), RideRecorder.parseTrack(m.track).map { it.secondsFromStart })
    }

    @Test
    fun `accumulates distance, finishes after a long stop`() {
        val lat = ride(120)
        ride(30, lat)
        val r = recorder.current!!
        assertTrue("distance ${r.distanceMeters}", r.distanceMeters in 2_500.0..3_100.0)
        assertEquals(72f, r.maxSpeedKmh, 0.1f)
        clock += RideRecorder.END_AFTER_STOP_MS + 1000
        assertEquals(RideRecorder.Event.FINISHED, recorder.onFix(GpsFix(lat, 10.0, 100.0, 0f, 0f, 5f, clock), 0f))
        val done = recorder.finish()!!
        assertTrue("points ${RideRecorder.parseTrack(done.track).size}", RideRecorder.parseTrack(done.track).size > 100)
    }

    @Test
    fun `riding slower than a track point a second counts each metre once`() {
        // 36 km/h: 10 m a second, so the track keeps every second fix only.
        var lat = 60.0
        repeat(130) {
            clock += 1000
            lat += 10.0 / 111_320
            recorder.onFix(GpsFix(lat, 10.0, 100.0, 10f, 0f, 5f, clock), 36f)
        }
        val r = recorder.current!!
        val ridden = (lat - 60.0) * 111_320
        assertTrue("distance ${r.distanceMeters} of $ridden", r.distanceMeters in ridden - 150..ridden + 1.0)
        assertTrue("moving ${r.movingMillis}", r.movingMillis in 100_000L..130_000L)
    }

    private fun standStill(ms: Long, lat: Double) {
        clock += ms
        recorder.onFix(GpsFix(lat, 10.0, 100.0, 0f, 0f, 5f, clock), 0f)
    }

    @Test
    fun `a stop of five minutes ends the ride, a shorter one does not`() {
        val lat = ride(120)
        standStill(RideRecorder.END_AFTER_STOP_MS - 30_000, lat)
        assertNotNull(recorder.current)
        assertEquals(RideRecorder.Event.FINISHED, recorder.onFix(GpsFix(lat, 10.0, 100.0, 0f, 0f, 5f, clock + 31_000).also { clock += 31_000 }, 0f))
    }

    @Test
    fun `a stop is over after five minutes also with no fix to go by`() {
        val lat = ride(120)
        standStill(60_000, lat) // stopped, and then no more fixes: indoors, or Curveon out of sight
        clock += 3 * 60_000
        assertEquals(false, recorder.over())
        clock += 2 * 60_000
        assertEquals(true, recorder.over())
    }

    @Test
    fun `riding off after the time was up does not carry on the old ride`() {
        val lat = ride(120)
        standStill(60_000, lat)
        clock += 6 * 60_000
        // The first fix is at speed: without the time being looked at first, this would have looked like riding all along.
        assertEquals(RideRecorder.Event.FINISHED, recorder.onFix(GpsFix(lat, 10.0, 100.0, 20f, 0f, 5f, clock), 72f))
    }

    @Test
    fun `a long tunnel with no fixes does not end the ride`() {
        val lat = ride(120)
        clock += 20 * 60_000 // Laerdal: over 24 km with no GPS, the last fix at speed
        assertEquals(false, recorder.over())
        assertTrue(recorder.onFix(GpsFix(lat + 0.2, 10.0, 100.0, 20f, 0f, 5f, clock), 72f) != RideRecorder.Event.FINISHED)
        assertNotNull(recorder.current)
    }

    @Test
    fun `fixes in motion that stop for very long do end the ride`() {
        ride(120)
        clock += 31 * 60_000
        assertEquals(true, recorder.over())
    }

    @Test
    fun `no ride under way is never over`() {
        clock += 60 * 60_000
        assertEquals(false, recorder.over())
    }

    @Test
    fun `riding again within the break window continues the last ride automatically`() {
        val lat = ride(120)
        recorder.setId(7L)
        standStill(RideRecorder.END_AFTER_STOP_MS + 1000, lat)
        val first = recorder.finish()!!
        clock += 15 * 60_000L
        var resumed = false
        var curLat = lat
        repeat(15) {
            clock += 1000
            curLat += 20.0 / 111_320
            if (recorder.onFix(GpsFix(curLat, 10.0, 100.0, 20f, 0f, 5f, clock), 72f) == RideRecorder.Event.RESUMED) resumed = true
        }
        assertTrue(resumed)
        val continued = recorder.current!!
        assertEquals(7L, continued.id)
        assertEquals(first.startMs, continued.startMs)
        assertTrue(continued.distanceMeters > first.distanceMeters)
    }

    @Test
    fun `a hand stop, a long break, and waking hours later start a new ride`() {
        var lat = ride(120)
        recorder.setId(7L)
        assertNotNull(recorder.stop())
        standStill(5_000, lat); standStill(5_000, lat)
        lat = ride(90, lat)
        assertEquals(0L, recorder.current!!.id) // hand-stopped ride is not continued

        // Leaving the app while riding and reopening hours later: finish() stamps the real stop time, not now().
        recorder.setId(8L)
        clock += 6 * 60 * 60_000L
        assertTrue(recorder.over())
        assertNotNull(recorder.finish())
        lat = ride(90, lat)
        assertEquals(0L, recorder.current!!.id) // must be a brand-new ride, not continuing the one from 6 h ago

        // A break longer than RESUME_WINDOW_MS also starts a new ride.
        recorder.setId(9L)
        standStill(RideRecorder.END_AFTER_STOP_MS + 1000, lat)
        assertNotNull(recorder.finish())
        clock += RideRecorder.RESUME_WINDOW_MS + 1000
        ride(30, lat)
        assertEquals(0L, recorder.current!!.id)
    }

    @Test
    fun `after a restart the ride stored last is continued automatically if it was a short while ago`() {
        clock = 10 * 60 * 60_000L
        val stored = Ride(id = 42, title = "Before", startMs = clock - 3_600_000, endMs = clock - 10 * 60_000, distanceMeters = 30_000.0)
        recorder.remember(stored)
        ride(15)
        assertEquals(42L, recorder.current?.id)
        assertEquals(stored.startMs, recorder.current?.startMs)
    }

    @Test
    fun `a ride stored long ago is a ride of its own`() {
        clock = 10 * 60 * 60_000L
        recorder.remember(Ride(id = 42, startMs = 0, endMs = clock - RideRecorder.RESUME_WINDOW_MS - 60_000, distanceMeters = 30_000.0))
        ride(15)
        assertNotNull(recorder.current)
        assertEquals(0L, recorder.current?.id)
    }
}
