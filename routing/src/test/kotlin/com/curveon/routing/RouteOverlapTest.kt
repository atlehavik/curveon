package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

class RouteOverlapTest {
    private val kx = 111_320.0 * cos(Math.toRadians(60.0))

    /** A point [east] metres east and [north] metres north of 60°N 5°E. */
    private fun at(east: Double, north: Double = 0.0) = LatLon(60.0 + north / 110_574.0, 5.0 + east / kx)

    /** A route going east for a kilometre, a point every 10 m. */
    private val corridor = RouteCorridor(
        (0..100).map { i -> at(i * 10.0).let { RoutePoint(it.lat, it.lon, 0.0, i * 10.0, 0.0, null, null, false, false) } }
            .let { Route(it, emptyList(), 1000.0, 0.0, 0.0, 0, 0.0, 0) },
        10.0
    )

    @Test
    fun aTrackAlongPartOfTheRouteGivesThatStretch() {
        // From the north down onto the route, along it from 300 to 600 m, then off to the south.
        val track = listOf(at(200.0, 300.0), at(250.0, 150.0)) + (30..60).map { at(it * 10.0, 3.0) } + listOf(at(650.0, -200.0))
        val runs = RouteOverlap.along(corridor, track)
        assertEquals(1, runs.size)
        assertEquals(31, runs[0].size)
    }

    @Test
    fun aTrackThatOnlyCrossesTheRouteGivesNothing() {
        val track = listOf(at(500.0, 200.0), at(500.0, 0.0), at(500.0, -200.0))
        assertEquals(emptyList<List<LatLon>>(), RouteOverlap.along(corridor, track))
    }

    @Test
    fun aTrackBesideTheRouteButFurtherOffGivesNothing() {
        val track = (0..50).map { at(it * 10.0, 40.0) }
        assertEquals(emptyList<List<LatLon>>(), RouteOverlap.along(corridor, track))
    }

    @Test
    fun aTrackThatLeavesAndComesBackGivesTwoStretches() {
        val track = (0..20).map { at(it * 10.0) } + listOf(at(250.0, 100.0)) + (30..50).map { at(it * 10.0) }
        assertEquals(2, RouteOverlap.along(corridor, track).size)
    }
}
