package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The via points a loop or an adventure becomes a trip through ([Routes.viaPoints]). */
class ViaPointsTest {
    /** East along a road, a ferry stretch, a dead end ridden up and back, and on east. */
    private fun route(): Route {
        val pts = ArrayList<RoutePoint>()
        var d = 0.0
        var lat = 60.0; var lon = 5.0
        fun add(dLat: Double, dLon: Double, ferry: Boolean = false) {
            val nLat = lat + dLat; val nLon = lon + dLon
            if (pts.isNotEmpty()) d += Geo.distance(LatLon(lat, lon), LatLon(nLat, nLon))
            lat = nLat; lon = nLon
            pts += RoutePoint(lat, lon, 0.0, d, d / 20, "secondary", null, false, ferry)
        }
        val east = 0.0018 // about 100 m at 60° north
        val north = 0.0009 // about 100 m
        add(0.0, 0.0)
        repeat(60) { add(0.0, east) }              //  0 –  6 km: road
        repeat(40) { add(0.0, east, ferry = true) } //  6 – 10 km: ferry
        repeat(20) { add(0.0, east) }              // 10 – 12 km: road
        repeat(30) { add(north, 0.0) }             // 12 – 15 km: up a dead end…
        repeat(30) { add(-north, 0.0) }            // 15 – 18 km: …and back
        repeat(60) { add(0.0, east) }              // 18 – 24 km: road
        return Route(pts, emptyList(), d, d / 20, 0.0, 0, 0.0, 1)
    }

    @Test fun `never on the ferry nor down the dead end, and not clumped after them`() {
        val r = route()
        val vias = Routes.viaPoints(r, 11)
        val along = vias.map { v -> r.points.first { it.lat == v.lat && it.lon == v.lon }.distanceFromStart }
        assertTrue("found the dead end", Spurs.find(r).isNotEmpty())
        for (a in along) {
            assertTrue("on the ferry at %.0f m".format(a), a !in 6000.0..10_000.0)
            assertTrue("down the dead end at %.0f m".format(a), a !in 12_000.0..18_000.0)
        }
        // The places that fell on the ferry and the dead end are left out, not crowded in after them.
        assertTrue(along.zipWithNext().all { (a, b) -> b - a >= 1500 })
        assertTrue(vias.size in 5..8)
    }

    @Test fun `a plain road gets them evenly`() {
        val pts = (0..100).map { RoutePoint(60.0, 5.0 + it * 0.0018, 0.0, it * 100.0, it * 5.0, "secondary", null, false, false) }
        val vias = Routes.viaPoints(Route(pts, emptyList(), 10_000.0, 500.0, 0.0, 0, 0.0, 0), 4)
        assertEquals(listOf(2000.0, 4000.0, 6000.0, 8000.0), vias.map { v -> pts.first { it.lon == v.lon }.distanceFromStart })
    }
}
