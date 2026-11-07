package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roads round a roundabout at Heiane, Stord, as the map's tiles draw them (src/test/resources/roadsnap: the
 * roads layer of the three by three z14 tiles round it, as the app reads it; © OpenStreetMap contributors, ODbL).
 */
class RoadSnapRealRoadsTest {
    private val lines = javaClass.getResourceAsStream("/roadsnap/stord-roundabout.txt")!!.bufferedReader().readLines().map { l ->
        val parts = l.split(' ')
        RoadSnap.Line(parts.drop(1).map { val (a, b) = it.split(','); LatLon(a.toDouble(), b.toDouble()) }, RoadSnap.Kind.valueOf(parts[0]))
    }
    /** The ring, drawn as one closed line. */
    private val ring = lines.first { it.kind == RoadSnap.Kind.ROAD && it.points.size > 3 && Geo.distance(it.points.first(), it.points.last()) < 1.0 }.points
    private val middle = LatLon(ring.sumOf { it.lat } / ring.size, ring.sumOf { it.lon } / ring.size)

    @Test
    fun everywhere_round_the_roundabout_it_is_known() {
        for (i in 0 until ring.size - 1) {
            val a = ring[i]; val b = ring[i + 1]
            val hit = RoadSnap.roundabout(LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2), lines, Geo.bearing(a.lat, a.lon, b.lat, b.lon))
            assertNotNull("piece $i", hit)
            assertEquals("piece $i", 0.0, Geo.distance(hit!!.first, middle), 3.0)
            assertTrue("piece $i: radius ${hit.second}", hit.second in 12.0..20.0)
        }
    }

    @Test
    fun riding_up_to_it_the_arrow_is_carried_to_the_ring_and_then_straight_on() {
        // The road from the south, some 150 m before the ring, riding towards it.
        val road = lines[2].points
        val atRing = road.first()
        val (on, bearing) = RoadSnap.near(road[10], lines, Geo.bearing(road[10].lat, road[10].lon, road[9].lat, road[9].lon), 15.0, 30.0)!!
        assertNull(RoadSnap.roundabout(on, lines, bearing))
        val (route, along) = RoadSnap.path(on, lines, bearing, 120.0, 250.0)
        // Along the road (its points) to where it meets the ring, not round it nor out of another exit: from there
        // on straight, the way the road ran into it.
        val reach = route.points.first { Geo.distance(LatLon(it.lat, it.lon), atRing) < 1.0 }.distanceFromStart - along
        assertEquals(154.0, reach, 3.0)
        for (p in route.points.filter { it.distanceFromStart > along + 1 && it.distanceFromStart < along + reach - 1 }) {
            assertTrue("${p.distanceFromStart}", road.any { Geo.distance(it, LatLon(p.lat, p.lon)) < 0.5 })
        }
        val end = route.points.last()
        assertEquals(250.0 - reach, Geo.distance(atRing, LatLon(end.lat, end.lon)), 1.0)
    }
}
