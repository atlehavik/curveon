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

    @Test fun `a negative lead puts the arrow behind the fix`() {
        val m = RiderMotion()
        val old = RiderMotion.LEAD_S
        try {
            RiderMotion.LEAD_S = -1.0
            m.onFix(fixAt(2.0, 20.0, true), 2_000_000_000L)
            // A quarter second after the fix (40 m on), a second behind: 15 m back from the fix.
            assertEquals(40.0 + 20.0 * (0.25 - 1.0), distanceAlong(m.sample(2_250_000_000L)!!), 0.5)
        } finally { RiderMotion.LEAD_S = old }
    }

    @Test fun `does not run away when the fixes stop`() {
        val m = RiderMotion()
        m.onFix(fixAt(0.0, 25.0, false), 0L)
        val d = distanceAlong(m.sample(30_000_000_000L)!!)
        assertEquals(25.0 * (RiderMotion.MAX_AHEAD_S + RiderMotion.LEAD_S), d, 0.5)
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

    @Test fun `without a route the arrow stays on the road through a bend`() {
        // A bend of radius 60 m, as the map's road lines round the rider, ridden at 55 km/h with a fix a second.
        val centre = Geo.destination(start, 90.0, 60.0)
        val road = (0..180 step 3).map { Geo.destination(centre, (270.0 + it) % 360, 60.0) }
        val speed = 55 / 3.6
        val m = RiderMotion()
        var worst = 0.0
        var t = 0.0
        var nextFix = 0.0
        while (t < 6.0) {
            if (t >= nextFix - 1e-9) {
                val angle = Math.toDegrees(speed * nextFix / 60.0)
                val on = Geo.destination(centre, (270.0 + angle) % 360, 60.0)
                val bearing = (angle + 360) % 360
                val (path, along) = com.curveon.routing.RoadSnap.path(on, listOf(com.curveon.routing.RoadSnap.Line(road)), bearing, 120.0, 250.0)
                m.onFix(RiderMotion.Fix(on.lat, on.lon, bearing, speed, (nextFix * 1e9).toLong(), path, along), (t * 1e9).toLong())
                nextFix += 1.0
            }
            val p = m.sample((t * 1e9).toLong())!!
            if (t > 0.5) worst = maxOf(worst, kotlin.math.abs(Geo.distance(centre, LatLon(p.lat, p.lon)) - 60.0))
            t += 1.0 / 60
        }
        // Carried straight on it was ~10 m outside the bend before each fix pulled it back.
        assertTrue("worst $worst m off the road", worst < 1.0)
    }
}
