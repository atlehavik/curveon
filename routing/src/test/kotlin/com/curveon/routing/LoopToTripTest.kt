package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A long loop drawn round Bømlo, Sotra and Haugesund (as the rider drew one, 2026-11-08, max 300 km), made into a trip
 * through its via points as the app does: the old way (evenly, ferries and dead ends too) against [Routes.viaPoints].
 * Prints what each rides twice and on ferries; the new way must never be worse.
 */
class LoopToTripTest {
    @Test fun `a loop made a trip keeps off the ferry stubs and dead ends`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val start = LatLon(59.7795, 5.2049) // Svortland
        val area = listOf(LatLon(59.95, 5.05), LatLon(60.45, 4.95), LatLon(60.50, 5.35), LatLon(60.10, 5.55),
            LatLon(59.75, 5.55), LatLon(59.35, 5.45), LatLon(59.30, 5.15), LatLon(59.60, 5.05))
        val opts = RouteOptions(RoadStyle.CURVY)
        val loop = RoundtripPlanner(engine).planArea(start, area, opts, maxKm = 300.0)
        fun report(what: String, r: Route) = println("%-10s %5.0f km  ferries=%d  twice=%.0f%%  spurs=%.1f km".format(
            what, r.distanceMeters / 1000, r.ferryCount, RoundtripPlanner.overlapFraction(r) * 100, Spurs.wastedMeters(r) / 1000))
        report("loop", loop)
        val count = (loop.distanceMeters / 8_000.0).toInt().coerceIn(4, 12)
        var next = 0
        val old = buildList { for (pt in loop.points) if (next < count && pt.distanceFromStart >= loop.distanceMeters * (next + 1) / (count + 1)) { add(LatLon(pt.lat, pt.lon)); next++ } }
        val new = Routes.viaPoints(loop, count)
        fun trip(vias: List<LatLon>) = engine.route(listOf(start) + vias + start, opts, visitEveryVia = true)
        val a = trip(old); val b = trip(new)
        report("old vias", a)
        report("new vias", b)
        println("old on ferry: ${old.count { v -> loop.points.any { it.ferry && it.lat == v.lat && it.lon == v.lon } }}")
        org.junit.Assert.assertTrue(Spurs.wastedMeters(b) <= Spurs.wastedMeters(a) + 500)
    }
}
