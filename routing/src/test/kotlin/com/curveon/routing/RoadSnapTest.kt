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

    /** A right-hand bend of radius 80 m from [start], heading north, a point every 3°, out to [toDeg] degrees. */
    private fun bend(fromDeg: Int, toDeg: Int): List<LatLon> {
        val centre = Geo.destination(start, 90.0, 80.0)
        return (fromDeg..toDeg step 3).map { Geo.destination(centre, (270.0 + it) % 360, 80.0) }
    }

    @Test
    fun the_path_follows_the_bend_on_into_the_next_line_and_not_the_road_turning_off() {
        // The bend in two lines meeting at 60°, a side road leaving there to the north-west, and the second line
        // starting a little before the first ends, as at the edge of a map tile.
        val first = bend(0, 60)
        val second = bend(57, 180)
        val joint = first.last()
        val side = listOf(joint, Geo.destination(joint, 320.0, 200.0))
        val (route, along) = RoadSnap.path(start, listOf(side, second, first), 0.0, 30.0, 300.0)
        assertEquals(30.0, along, 0.5)
        val centre = Geo.destination(start, 90.0, 80.0)
        // Everything on the bend (some 250 m of it), off it only straight on past where the lines end.
        for (p in route.points) {
            val ll = LatLon(p.lat, p.lon)
            if (p.distanceFromStart in along..along + 245) assertEquals("at ${p.distanceFromStart}", 80.0, Geo.distance(centre, ll), 1.0)
        }
        val end = route.points.last()
        assertEquals(along + 300.0, end.distanceFromStart, 1.0)
        // Past the bend's end (heading south) it goes on straight south.
        assertEquals(180.0, Geo.bearing(second.last().lat, second.last().lon, end.lat, end.lon), 2.0)
    }

    @Test
    fun the_path_behind_runs_back_along_the_road() {
        val (route, along) = RoadSnap.path(Geo.destination(start, 90.0, 100.0), roads, 90.0, 30.0, 50.0)
        val (back, _) = Routes.pointAt(route, 0.0)
        assertEquals(0.0, Geo.distance(back, Geo.destination(start, 90.0, 70.0)), 0.5)
        assertEquals(80.0, route.distanceMeters, 0.5)
        assertEquals(30.0, along, 0.5)
    }

    @Test
    fun too_far_or_across_every_road_is_raw_gps() {
        assertNull(RoadSnap.near(Geo.destination(Geo.destination(start, 90.0, 100.0), 0.0, 30.0), roads, 90.0, 15.0, 30.0))
        assertNull(RoadSnap.near(Geo.destination(Geo.destination(start, 90.0, 100.0), 0.0, 5.0), listOf(eastward), 0.0, 15.0, 30.0))
    }
}
