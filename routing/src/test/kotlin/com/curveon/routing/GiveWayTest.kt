package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GiveWayTest {

    /** A route through [corners], with a point every ~10 m. */
    private fun route(vararg corners: LatLon): Route {
        val pts = ArrayList<RoutePoint>()
        var along = 0.0
        for (i in 0 until corners.size - 1) {
            val a = corners[i]; val b = corners[i + 1]
            val len = Geo.distance(a, b)
            val n = (len / 10).toInt().coerceAtLeast(1)
            for (k in 0 until n) {
                val f = k.toDouble() / n
                pts += RoutePoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, 0.0, along + len * f, 0.0, null, null, false, false)
            }
            along += len
        }
        val last = corners.last()
        pts += RoutePoint(last.lat, last.lon, 0.0, along, 0.0, null, null, false, false)
        return Route(pts, emptyList(), along, 0.0, 0.0, 0, 0.0, 0)
    }

    // A main road running north along 5.0° E, a side road coming in from the east at 60.005° N.
    private val junction = LatLon(60.005, 5.0)
    private val south = LatLon(60.0, 5.0)
    private val north = LatLon(60.01, 5.0)
    private val east = LatLon(60.005, 5.003)
    private val tenNorth = LatLon(60.005 + 10 / 110_574.0, 5.0)

    private fun sign(sign: Int, kind: Int, at: LatLon = junction, on: LatLon = tenNorth) =
        GiveWaySign(at.lat, at.lon, on.lat, on.lon, sign * 10 + kind)

    @Test
    fun signFacingTheRiderOnTheRoadCounts() {
        val r = route(south, north)
        val found = GiveWay.alongRoute(r, listOf(sign(202, 0)))
        assertEquals(1, found.size)
        assertEquals(Geo.distance(south, junction), found[0].along, 3.0)
        // One facing the other way is for oncoming traffic.
        val back = LatLon(60.005 - 10 / 110_574.0, 5.0)
        assertTrue(GiveWay.alongRoute(r, listOf(sign(202, 0, on = back))).isEmpty())
    }

    @Test
    fun sideRoadSignCountsComingOutOfTheSideRoad() {
        val fromEast = route(east, junction, north)
        // The side road is on the right of the main road's direction (north).
        assertEquals(1, GiveWay.alongRoute(fromEast, listOf(sign(202, 1))).size)
        assertEquals(1, GiveWay.alongRoute(fromEast, listOf(sign(202, 3))).size)
        // A sign for a side road on the left (west) is not this one.
        assertTrue(GiveWay.alongRoute(fromEast, listOf(sign(202, 2))).isEmpty())
    }

    @Test
    fun sideRoadSignDoesNotCountTurningOffTheMainRoad() {
        val intoSideRoad = route(south, junction, east)
        assertTrue(GiveWay.alongRoute(intoSideRoad, listOf(sign(202, 1))).isEmpty())
        // Nor riding straight past it.
        assertTrue(GiveWay.alongRoute(route(south, north), listOf(sign(202, 1))).isEmpty())
    }

    @Test
    fun oneSignPerJunctionAndStopWins() {
        val fromEast = route(east, junction, north)
        val found = GiveWay.alongRoute(fromEast, listOf(sign(202, 1), sign(204, 3)))
        assertEquals(1, found.size)
        assertTrue(found[0].stop)
    }

    @Test
    fun signFarFromTheRouteIsIgnored() {
        val elsewhere = LatLon(60.005, 5.01)
        assertTrue(GiveWay.alongRoute(route(south, north), listOf(sign(202, 0, at = elsewhere, on = LatLon(elsewhere.lat + 0.0001, elsewhere.lon)))).isEmpty())
    }

    /** The main road as a priority road (Norway: forkjørsveg), found within 20 m. */
    private val priority: (Double, Double) -> RoadPiece? = { lat, lon ->
        if (lat in 59.999..60.011 && Geo.distance(lat, lon, lat, 5.0) < 20) RoadPiece(south.lat, south.lon, north.lat, north.lon) else null
    }

    @Test
    fun comingOntoAPriorityRoadGivesWayWithoutASign() {
        val found = GiveWay.alongRoute(route(east, junction, north), emptyList(), priority)
        assertEquals(1, found.size)
        assertEquals(Geo.distance(east, junction), found[0].along, 5.0)
        // Where it is and the way the route comes to it (west), for its sign on the map.
        assertEquals(0.0, Geo.distance(LatLon(found[0].lat, found[0].lon), junction), 5.0)
        assertEquals(270.0, found[0].bearingDeg, 10.0)
    }

    @Test
    fun ridingAlongOrOffAPriorityRoadDoesNot() {
        assertTrue(GiveWay.alongRoute(route(south, north), emptyList(), priority).isEmpty())
        assertTrue(GiveWay.alongRoute(route(south, junction, east), emptyList(), priority).isEmpty())
        // Straight across (or under a bridge): left out.
        assertTrue(GiveWay.alongRoute(route(east, LatLon(60.005, 4.997)), emptyList(), priority).isEmpty())
    }

    @Test
    fun aPriorityRoadAndItsOwnSignAreOneJunction() {
        assertEquals(1, GiveWay.alongRoute(route(east, junction, north), listOf(sign(202, 1)), priority).size)
    }
}
