package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stops added one by one on the road (2026-10-19, Bømlo: four stops, the route kept only some of them
 * because the way "that doesn't turn round" was routed without visiting every stop). Skipped without segments.
 */
class StopsVisitedTest {
    private lateinit var engine: BRouterEngine

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E5_N55.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
    }

    /** How far [p] is from the line of [route], in metres. */
    private fun offRoute(p: LatLon, route: Route): Double {
        val line = route.points
        var best = Double.MAX_VALUE
        val kx = Math.cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_540.0
        for (i in 0 until line.size - 1) {
            val ax = (line[i].lon - p.lon) * kx; val ay = (line[i].lat - p.lat) * ky
            val bx = (line[i + 1].lon - p.lon) * kx; val by = (line[i + 1].lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            best = minOf(best, Math.hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    @Test
    fun everyStopIsOnTheRouteFromHereAndOnTheWayAhead() {
        val dest = LatLon(59.797243, 5.188452)
        val adds = listOf(
            Triple(LatLon(59.7936362, 5.1442255), 74.0, LatLon(59.79431, 5.17992)),
            Triple(LatLon(59.7937340, 5.1504260), 94.6, LatLon(59.79345, 5.17164)),
            Triple(LatLon(59.7931190, 5.1549500), 113.2, LatLon(59.79297, 5.17571)),
            Triple(LatLon(59.7921430, 5.1651730), 7.5, LatLon(59.79392, 5.17009)))
        for (style in RoadStyle.entries) {
            val options = RouteOptions(roadStyle = style)
            var stops = listOf<LatLon>()
            for ((pos, heading, stop) in adds) {
                val chain = listOf(pos) + stops + dest
                val at = (0 until chain.size - 1).minBy { Geo.distance(chain[it], stop) + Geo.distance(stop, chain[it + 1]) - Geo.distance(chain[it], chain[it + 1]) }
                stops = stops.toMutableList().also { it.add(at.coerceIn(0, stops.size), stop) }
                val free = engine.route(listOf(pos) + stops + dest, options, visitEveryVia = true)
                // The way the rider is heading, as the app tries before it settles for turning round.
                val ahead = OffRoutePlanner(engine).forwardOnly(pos, heading, stops + dest, options).second
                for (route in listOfNotNull(free, ahead)) for (s in stops) {
                    val off = offRoute(s, route)
                    assertTrue("$style: a stop is $off m from the route", off < 40.0)
                }
            }
        }
    }
}
