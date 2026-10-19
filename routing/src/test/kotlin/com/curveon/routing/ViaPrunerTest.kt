package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViaPrunerTest {
    /** A straight road of 100 nodes; the "router" only has to be sent through the vias in [needed]. */
    private val road = (0 until 100).map { LatLon(60.0, 5.0 + it * 0.001) }
    private val needed = setOf(road[30], road[71])

    private fun routeOf(points: List<LatLon>, detour: Boolean = false): Route {
        val pts = points.mapIndexed { i, p -> RoutePoint(p.lat, p.lon, 0.0, i * 55.0, 0.0, null, null, false, false) }
        return Route(pts, emptyList(), if (detour) 99_999.0 else 5_445.0, 0.0, 0.0, 0, 0.0, 0)
    }

    /** Through the road, except that without a needed via the route takes another road. */
    private fun fake(vias: List<LatLon>): Route = if (vias.containsAll(needed)) routeOf(road) else routeOf(road, detour = true)

    @Test
    fun keepsOnlyTheViasTheRouteNeeds() {
        val vias = (5 until 95 step 3).map { road[it] } + needed
        val kept = ViaPruner.prune(vias, fake(vias)) { fake(it) }
        assertEquals(needed, kept.toSet())
    }

    @Test
    fun stopsAtTheRouteLimitAndWhenCancelled() {
        val vias = road.subList(5, 95)
        var made = 0
        ViaPruner.prune(vias, fake(vias), maxRoutes = 7) { made++; fake(it) }
        assertEquals(7, made)
        assertEquals(vias, ViaPruner.prune(vias, fake(vias), cancelled = { true }) { fake(it) })
    }

    @Test
    fun aDifferentRoadIsNotTheSame() {
        assertTrue(ViaPruner.sameRoad(routeOf(road), routeOf(road), 0))
        assertTrue(!ViaPruner.sameRoad(routeOf(road), routeOf(road.filterIndexed { i, _ -> i % 2 == 0 }), 0))
    }
}
