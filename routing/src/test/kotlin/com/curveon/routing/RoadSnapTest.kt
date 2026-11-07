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
    private val roads = listOf(eastward, crossing).map { RoadSnap.Line(it) }

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
    fun the_path_follows_the_bend_on_into_the_next_line() {
        // The bend in two lines, the second starting a little before the first ends, as at the edge of a map tile.
        val first = bend(0, 60)
        val second = bend(57, 180)
        val (route, along) = RoadSnap.path(start, listOf(second, first).map { RoadSnap.Line(it) }, 0.0, 30.0, 300.0)
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
    fun at_a_junction_the_path_goes_on_straight_instead_of_guessing_the_way() {
        // The same bend, with a road leaving where the two lines meet: which way the rider goes there isn't known.
        val first = bend(0, 60)
        val second = bend(57, 180)
        val joint = first.last()
        val side = listOf(joint, Geo.destination(joint, 20.0, 200.0))
        val (route, along) = RoadSnap.path(start, listOf(side, second, first).map { RoadSnap.Line(it) }, 0.0, 30.0, 300.0)
        val centre = Geo.destination(start, 90.0, 80.0)
        val toJoint = 80.0 * Math.toRadians(60.0)
        for (p in route.points) {
            val ll = LatLon(p.lat, p.lon)
            if (p.distanceFromStart in along..along + toJoint - 1) assertEquals("at ${p.distanceFromStart}", 80.0, Geo.distance(centre, ll), 1.0)
        }
        // On from the junction straight the way the bend ran there (60°), neither down the bend nor up the side road.
        val end = route.points.last()
        assertEquals(along + 300.0, end.distanceFromStart, 1.0)
        assertEquals(300.0 - toJoint, Geo.distance(joint, LatLon(end.lat, end.lon)), 2.0)
        assertEquals(60.0, Geo.bearing(joint.lat, joint.lon, end.lat, end.lon), 3.0)
    }

    @Test
    fun a_path_or_a_way_to_a_yard_turning_off_is_no_junction() {
        val joint = Geo.destination(start, 90.0, 150.0)
        val footway = RoadSnap.Line(listOf(joint, Geo.destination(joint, 0.0, 100.0)), RoadSnap.Kind.PATH)
        val yard = RoadSnap.Line(listOf(joint, Geo.destination(joint, 180.0, 50.0)), RoadSnap.Kind.SERVICE)
        val (route, along) = RoadSnap.path(Geo.destination(start, 90.0, 100.0), listOf(RoadSnap.Line(eastward), footway, yard), 90.0, 30.0, 150.0)
        // Carried along the road past both, up to the junction 200 m along.
        val (at, _) = Routes.pointAt(route, along + 100.0)
        assertEquals(0.0, Geo.distance(at, Geo.destination(start, 90.0, 200.0)), 0.5)
    }

    @Test
    fun a_road_wins_over_the_cycleway_beside_it() {
        // A cycleway 6 m north of the eastward road; GPS 4 m north of the road, nearer the cycleway.
        val cycleway = RoadSnap.Line(listOf(Geo.destination(start, 0.0, 6.0), Geo.destination(Geo.destination(start, 0.0, 6.0), 90.0, 400.0)), RoadSnap.Kind.PATH)
        val gps = Geo.destination(Geo.destination(start, 90.0, 100.0), 0.0, 4.0)
        val (on, _) = RoadSnap.near(gps, listOf(cycleway, RoadSnap.Line(eastward)), 90.0, 15.0, 30.0)!!
        assertEquals(0.0, Geo.distance(on, Geo.destination(start, 90.0, 100.0)), 0.5)
        // With no road near, the cycleway is taken.
        assertEquals(6.0, Geo.distance(RoadSnap.near(gps, listOf(cycleway), 90.0, 15.0, 30.0)!!.first, Geo.destination(start, 90.0, 100.0)), 0.5)
    }

    /** A ring of [radiusM] round [centre], in [pieces] lines, driven anticlockwise as in right-hand traffic, a point every [stepDeg]. */
    private fun ring(centre: LatLon, radiusM: Double, pieces: Int, stepDeg: Int = 10): List<List<LatLon>> =
        (0 until pieces).map { k ->
            val from = 360 / pieces * k
            (from..from + 360 / pieces step stepDeg).map { Geo.destination(centre, (360.0 - it) % 360, radiusM) }
        }

    @Test
    fun a_roundabout_is_known_in_one_line_or_several_and_with_roads_leaving_it() {
        val centre = Geo.destination(start, 90.0, 300.0)
        for (pieces in listOf(1, 4)) {
            val rings = ring(centre, 20.0, pieces).map { RoadSnap.Line(it) }
            // Roads in and out at each quarter, leaving straight out from the ring.
            val arms = (0 until 4).map { k ->
                val at = Geo.destination(centre, 90.0 * k, 20.0)
                RoadSnap.Line(listOf(at, Geo.destination(at, 90.0 * k, 150.0)))
            }
            // On the ring due south of the middle, going east (anticlockwise).
            val on = Geo.destination(centre, 185.0, 20.0)
            val hit = RoadSnap.roundabout(on, rings + arms, 95.0)
            assertNotNull("$pieces pieces", hit)
            assertEquals(0.0, Geo.distance(hit!!.first, centre), 3.0)
            assertEquals(20.0, hit.second, 2.0)
        }
        // A small one drawn with few points.
        assertNotNull(RoadSnap.roundabout(Geo.destination(centre, 180.0, 12.0), ring(centre, 12.0, 1, 45).map { RoadSnap.Line(it) }, 90.0))
    }

    @Test
    fun a_hairpin_or_a_block_of_streets_is_no_roundabout() {
        // A hairpin of radius 20 m: 180° and on.
        val centre = Geo.destination(start, 90.0, 20.0)
        val hairpin = listOf(Geo.destination(start, 180.0, 200.0)) + (0..180 step 10).map { Geo.destination(centre, (270.0 + it) % 360, 20.0) } +
            listOf(Geo.destination(Geo.destination(start, 90.0, 40.0), 180.0, 200.0))
        assertNull(RoadSnap.roundabout(start, listOf(RoadSnap.Line(hairpin)), 0.0))
        // Four streets round a block 60 m square, each its own line.
        val a = start; val b = Geo.destination(a, 0.0, 60.0); val c = Geo.destination(b, 90.0, 60.0); val d = Geo.destination(a, 90.0, 60.0)
        val block = listOf(listOf(a, b), listOf(b, c), listOf(c, d), listOf(d, a)).map { RoadSnap.Line(it) }
        assertNull(RoadSnap.roundabout(Geo.destination(a, 0.0, 30.0), block, 0.0))
        // A straight road.
        assertNull(RoadSnap.roundabout(Geo.destination(start, 90.0, 100.0), roads, 90.0))
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
        assertNull(RoadSnap.near(Geo.destination(Geo.destination(start, 90.0, 100.0), 0.0, 5.0), listOf(RoadSnap.Line(eastward)), 0.0, 15.0, 30.0))
    }
}
