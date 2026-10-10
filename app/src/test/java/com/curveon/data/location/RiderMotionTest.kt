package com.curveon.data.location

import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.Route
import com.curveon.routing.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderMotionTest {

    private val start = LatLon(59.4, 5.3)

    /** A straight line north, a point every 50 m. */
    private val route: Route = run {
        val pts = (0..40).map { i ->
            val p = Geo.destination(start, 0.0, i * 50.0)
            RoutePoint(p.lat, p.lon, 0.0, i * 50.0, i * 2.5, null, null, false, false)
        }
        Route(pts, emptyList(), 2000.0, 100.0, 0.0, 0, 0.0, 0)
    }

    private fun fixAt(t: Double, speed: Double, onRoute: Boolean, lagS: Double = 0.0): RiderMotion.Fix {
        val along = speed * (t - lagS)
        val p = Geo.destination(start, 0.0, along)
        return RiderMotion.Fix(p.lat, p.lon, 0.0, speed, (t * 1e9).toLong(), if (onRoute) route else null, if (onRoute) along else null)
    }

    private fun distanceAlong(p: RiderMotion.Pose) = Geo.distance(start, LatLon(p.lat, p.lon))

    @Test fun `glides between fixes without hopping`() {
        for (onRoute in listOf(true, false)) {
            val m = RiderMotion()
            val speed = 20.0
            var prev: Double? = null
            var maxStep = 0.0
            var t = 0.0
            var nextFix = 0.0
            while (t < 10.0) {
                if (t >= nextFix - 1e-9) { m.onFix(fixAt(nextFix, speed, onRoute), (t * 1e9).toLong()); nextFix += 1.0 }
                val d = distanceAlong(m.sample((t * 1e9).toLong())!!)
                prev?.let { maxStep = maxOf(maxStep, d - it) }
                prev = d
                t += 1.0 / 60
            }
            // 20 m/s at 60 fps is 0.33 m a frame; a hop to the next fix would be 20 m.
            assertTrue("onRoute=$onRoute max step $maxStep", maxStep < 0.6)
        }
    }

    @Test fun `shows where the rider is now, not where the fix was`() {
        val m = RiderMotion()
        m.onFix(fixAt(0.0, 25.0, true), 0L)
        // Half a second after the fix the rider is 12.5 m on, plus the receiver delay.
        val d = distanceAlong(m.sample(500_000_000L)!!)
        assertEquals(25.0 * (0.5 + RiderMotion.LEAD_S), d, 0.5)
    }

    @Test fun `does not run away when the fixes stop`() {
        val m = RiderMotion()
        m.onFix(fixAt(0.0, 25.0, false), 0L)
        val d = distanceAlong(m.sample(30_000_000_000L)!!)
        assertEquals(25.0 * RiderMotion.MAX_AHEAD_S, d, 0.5)
    }

    @Test fun `a slightly wrong guess fades out instead of jumping`() {
        val m = RiderMotion()
        m.onFix(fixAt(0.0, 20.0, true), 0L)
        val before = distanceAlong(m.sample(1_000_000_000L)!!)
        // The bike braked: the next fix is 4 m short of the guess.
        val slower = RiderMotion.Fix(fixAt(1.0, 20.0, true).lat, fixAt(1.0, 20.0, true).lon, 0.0, 16.0, 1_000_000_000L, route, 16.0)
        m.onFix(slower.copy(lat = Geo.destination(start, 0.0, 16.0).lat, along = 16.0), 1_000_000_000L)
        val after = distanceAlong(m.sample(1_000_000_000L)!!)
        assertEquals(before, after, 0.01)
    }
}
