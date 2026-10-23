package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoadSnapTest {
    private val start = LatLon(59.79, 5.15)
    /** A road east from [start], and one crossing it northwards 200 m along. */
    private val eastward = listOf(start, Geo.destination(start, 90.0, 400.0))
    private val crossing = Geo.destination(start, 90.0, 200.0).let { listOf(Geo.destination(it, 180.0, 200.0), Geo.destination(it, 0.0, 200.0)) }
    private val roads = listOf(eastward, crossing)

    @Test
    fun drift_beside_the_road_is_drawn_on_it_pointing_the_way_ridden() {
        val gps = Geo.destination(Geo.destination(start, 90.0, 100.0), 0.0, 10.0)
        val hit = RoadSnap.near(gps, roads, 95.0, 15.0, 30.0)
        assertNotNull(hit)
        val (on, bearing) = hit!!
        assertEquals(0.0, Geo.distance(on, Geo.destination(start, 90.0, 100.0)), 1.0)
        assertEquals(90.0, bearing, 1.0)
        // Riding the other way along it: the same road, pointing west.
        assertEquals(270.0, RoadSnap.near(gps, roads, 265.0, 15.0, 30.0)!!.second, 1.0)
    }

    @Test
    fun at_a_junction_the_road_the_rider_rides_wins_not_the_one_across() {
        // 4 m from the crossing road and 6 m from the eastward one, riding east.
        val gps = Geo.destination(Geo.destination(start, 90.0, 196.0), 0.0, 6.0)
        assertEquals(90.0, RoadSnap.near(gps, roads, 90.0, 15.0, 30.0)!!.second, 1.0)
    }

    @Test
    fun too_far_or_across_every_road_is_raw_gps() {
        assertNull(RoadSnap.near(Geo.destination(Geo.destination(start, 90.0, 100.0), 0.0, 30.0), roads, 90.0, 15.0, 30.0))
        assertNull(RoadSnap.near(Geo.destination(Geo.destination(start, 90.0, 100.0), 0.0, 5.0), listOf(eastward), 0.0, 15.0, 30.0))
    }
}
