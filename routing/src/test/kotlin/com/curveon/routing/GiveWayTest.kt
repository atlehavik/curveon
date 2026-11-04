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
    fun turningOffAPriorityRoadPastASignFacingTheRiderDoesNot() {
        // The side road's sign registered facing away from the junction (as at KV1030 by Stavlandsvegen).
        val fiveEast = LatLon(60.005, 5.0 + 5 / (111_320.0 * kotlin.math.cos(Math.toRadians(60.005))))
        val fifteenEast = LatLon(60.005, 5.0 + 15 / (111_320.0 * kotlin.math.cos(Math.toRadians(60.005))))
        val intoSideRoad = route(south, junction, east)
        val wrongWay = sign(202, 0, at = fiveEast, on = fifteenEast)
        assertEquals(1, GiveWay.alongRoute(intoSideRoad, listOf(wrongWay)).size)
        assertTrue(GiveWay.alongRoute(intoSideRoad, listOf(wrongWay), priority).isEmpty())
    }

    @Test
    fun aSignOnTheRidersOwnRoadCountsAlsoOnAPriorityRoad() {
        // Those that contradict the road's priority signs are left out when the file is built. Hiding the
        // rest by the road being a priority road hid real give ways: OSM has one on them as often as anywhere.
        assertEquals(1, GiveWay.alongRoute(route(south, north), listOf(sign(202, 0)), priority).size)
    }

    @Test
    fun aSignOnARampBesideTheRoadDoesNotCount() {
        val beside = LatLon(60.005, 5.0 + 12 / (111_320.0 * kotlin.math.cos(Math.toRadians(60.005))))
        val on = LatLon(beside.lat + 10 / 110_574.0, beside.lon)
        assertTrue(GiveWay.alongRoute(route(south, north), listOf(sign(202, 0, at = beside, on = on)), priority).isEmpty())
    }

    @Test
    fun aStopSignIsNeverArguedAway() {
        // Facing the rider along what the register calls a priority road.
        assertEquals(1, GiveWay.alongRoute(route(south, north), listOf(sign(204, 0)), priority).size)
        // A side road's, riding out of it and turning off a priority road (the slip from FV47 to E39 south at Haukås).
        val fiveEast = LatLon(60.005, 5.0 + 5 / (111_320.0 * kotlin.math.cos(Math.toRadians(60.005))))
        val fifteenEast = LatLon(60.005, 5.0 + 15 / (111_320.0 * kotlin.math.cos(Math.toRadians(60.005))))
        assertEquals(1, GiveWay.alongRoute(route(south, junction, east), listOf(sign(204, 0, at = fiveEast, on = fifteenEast)), priority).size)
    }

    @Test
    fun aSideRoadsStopDoesNotCountRidingAlongTheRoad() {
        assertTrue(GiveWay.alongRoute(route(south, north), listOf(sign(204, 1)), priority).isEmpty())
    }

    @Test
    fun aRoundaboutOnAPriorityRoadIsAGiveWayOnce() {
        val r = route(south, north).let { it.copy(maneuvers = listOf(Maneuver(ManeuverType.ROUNDABOUT, 0, Geo.distance(south, junction) + 20, 2))) }
        val onTheWayOut = LatLon(60.005 + 40 / 110_574.0, 5.0)
        val found = GiveWay.alongRoute(r, listOf(sign(202, 0), sign(202, 0, at = onTheWayOut, on = LatLon(onTheWayOut.lat + 10 / 110_574.0, 5.0))), priority)
        assertEquals(1, found.size)
        assertEquals(Geo.distance(south, junction), found[0].along, 3.0)
    }

    @Test
    fun aRoundaboutWithoutASignInTheRegisterIsStillAGiveWay() {
        val elsewhere = LatLon(60.5, 5.0)
        val r = route(south, north).let { it.copy(maneuvers = listOf(Maneuver(ManeuverType.ROUNDABOUT, 0, 500.0, 2))) }
        val found = GiveWay.alongRoute(r, listOf(sign(202, 0, at = elsewhere, on = LatLon(60.5001, 5.0))))
        assertEquals(1, found.size)
        assertEquals(500.0, found[0].along, 1.0)
        // A country's file without signs says nothing about give way at all.
        assertTrue(GiveWay.alongRoute(r, emptyList()).isEmpty())
    }

    /** A priority road that comes from the east and ends at the main road, at [junction]. */
    private val endsAtJunction: (Double, Double) -> RoadPiece? = { lat, lon ->
        if (lon in 5.0..5.0031 && Geo.distance(lat, lon, 60.005, lon) < 20) RoadPiece(junction.lat, junction.lon, east.lat, east.lon) else null
    }

    @Test
    fun aPriorityRoadThatEndsAtTheJunctionHasItsSignThere() {
        // FV47 at Haukås: a priority road to its end, then the stop signs for E39. They were dropped as a wrong way round sign.
        val found = GiveWay.alongRoute(route(east, junction, north), listOf(sign(204, 1)), endsAtJunction)
        assertEquals(1, found.size)
        assertTrue(found[0].stop)
    }

    @Test
    fun aSlipLaneFlowingOntoAPriorityRoadGivesWayToNobody() {
        // Coming in at a shallow angle from the south-east, then north along the priority road.
        val slipStart = LatLon(60.0045, 5.0006)
        assertTrue(GiveWay.alongRoute(route(slipStart, junction, north), emptyList(), priority).isEmpty())
    }

    @Test
    fun backOnAPriorityRoadAfterAMomentAwayIsTheSameRoad() {
        // Round an island between the lanes: off the register's line for a few metres, and back.
        val island = LatLon(60.005, 5.0 + 30 / (111_320.0 * kotlin.math.cos(Math.toRadians(60.005))))
        val back = LatLon(60.0053, 5.0)
        assertTrue(GiveWay.alongRoute(route(south, junction, island, back, north), emptyList(), priority).isEmpty())
    }

    @Test
    fun aPriorityRoadAndItsOwnSignAreOneJunction() {
        assertEquals(1, GiveWay.alongRoute(route(east, junction, north), listOf(sign(202, 1)), priority).size)
    }

    /** A road going north, bending hard east at 300 m, as metres from a start. */
    private fun bendingRoad(): Route {
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(60.0))
        val pts = listOf(0.0 to 0.0, 0.0 to 300.0, 200.0 to 300.0).let { c ->
            var along = 0.0
            c.mapIndexed { i, (x, y) ->
                if (i > 0) along += kotlin.math.hypot(x - c[i - 1].first, y - c[i - 1].second)
                RoutePoint(60.0 + y / 110_574.0, 5.0 + x / kx, 0.0, along, 0.0, null, null, false, false)
            }
        }
        return Route(pts, emptyList(), pts.last().distanceFromStart, 0.0, 0.0, 0, 0.0, 0)
    }

    private fun bendSign(sign: Int): GiveWaySign {
        // On the bend, for traffic out of a side road: its road's direction is east, and the rider comes from the south of it, on its right.
        val lat = 60.0 + 300.0 / 110_574.0
        return GiveWaySign(lat, 5.0, lat, 5.0 + 10.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(60.0))), sign * 10 + 1)
    }

    private val wholeRoadIsPriority: (Double, Double) -> RoadPiece? = { lat, lon ->
        val corner = 60.0 + 300.0 / 110_574.0
        if (lat < corner - 1e-6) RoadPiece(60.0, 5.0, corner, 5.0) else RoadPiece(corner, 5.0, corner, lon + 0.001)
    }

    @Test
    fun aSideRoadsSignIsNotForARiderStayingOnThePriorityRoadThroughABend() {
        assertEquals(emptyList<GiveWayOnRoute>(), GiveWay.alongRoute(bendingRoad(), listOf(bendSign(202)), wholeRoadIsPriority))
    }

    @Test
    fun theSameSignIsForARiderComingFromAnOrdinaryRoad() {
        assertEquals(1, GiveWay.alongRoute(bendingRoad(), listOf(bendSign(202))) { _, _ -> null }.size)
    }

    @Test
    fun aStopSignOutOfASideRoadIsNeverArguedAwayOnAPriorityRoad() {
        assertEquals(1, GiveWay.alongRoute(bendingRoad(), listOf(bendSign(204)), wholeRoadIsPriority).size)
    }

    @Test
    fun onTheRoadAheadNoneThatTheRiderIsAtAlreadyNorPastWhereItTurnsOff() {
        val pts = listOf(0.0, 100.0, 400.0, 800.0).map { m -> RoutePoint(60.0, 5.0 + m / (111_320.0 * kotlin.math.cos(Math.toRadians(60.0))), 0.0, m, 0.0, null, null, false, false) }
        val sign = pts.last().let { GiveWaySign(60.0, 5.0, 60.0, 5.0, 0) }
        val turnOff = Maneuver(ManeuverType.LEFT, 2, 400.0)
        val rings = listOf(Maneuver(ManeuverType.ROUNDABOUT, 0, 5.0, exitNumber = 1), Maneuver(ManeuverType.ROUNDABOUT, 1, 100.0, exitNumber = 1), turnOff, Maneuver(ManeuverType.ROUNDABOUT, 3, 700.0, exitNumber = 1))
        val found = GiveWay.onRoadAhead(Route(pts, rings, 800.0, 0.0, 0.0, 0, 0.0, 0), listOf(sign))
        assertEquals(listOf(100), found.map { it.along.toInt() })
    }
}
