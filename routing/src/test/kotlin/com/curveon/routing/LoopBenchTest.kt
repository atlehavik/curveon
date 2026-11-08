package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * How much road roundtrips ride twice, on real roads (run with -Dcurveon.bench=true): areas drawn round Bømlo and
 * Haugesund (the rider's, 300 km), round Bergen with Sotra and Knarvik, round Voss, and loops by direction and length
 * from Bremnes. Prints length, share ridden twice, dead ends and time; asserts nothing.
 *
 * 2026-11-08, before → after weighing road twice heavier, dropping vias and two rounds of [RoundtripPlanner.lessTwice]
 * (share ridden twice): Haugesund 7.7/9.4 → 3.9/9.4, Bergen 12.6/8.7 → 9.2/5.6, Voss 25.2/9.3 → 18.2/1.2,
 * Bømlo–Stord 7.0/4.7 → 4.4/4.7, Bremnes 4.3/4.9/7.5/14.0 → 4.3/4.2/7.5/0.3; about the same time (4–20 s).
 */
class LoopBenchTest {
    @Test fun bench() {
        assumeTrue(System.getProperty("curveon.bench") == "true")
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val opts = RouteOptions(RoadStyle.CURVY)
        fun report(name: String, t0: Long, r: Route?) = println("BENCH %-22s %s (%.1f s)".format(name,
            r?.let { "%5.0f km  twice %4.1f%%  spurs %4.1f km  ferries %d  curvy %d".format(it.distanceMeters / 1000, RoundtripPlanner.overlapFraction(it) * 100, Spurs.wastedMeters(it) / 1000, it.ferryCount, it.curviness) } ?: "none",
            (System.nanoTime() - t0) / 1e9))
        val areas = listOf(
            Triple("haugesund 300", LatLon(59.7795, 5.2049), listOf(LatLon(59.95, 5.05), LatLon(60.45, 4.95), LatLon(60.50, 5.35), LatLon(60.10, 5.55),
                LatLon(59.75, 5.55), LatLon(59.35, 5.45), LatLon(59.30, 5.15), LatLon(59.60, 5.05))) to 300.0,
            Triple("bergen", LatLon(60.392, 5.324), listOf(LatLon(60.55, 5.25), LatLon(60.55, 5.45), LatLon(60.35, 5.50), LatLon(60.20, 5.35),
                LatLon(60.25, 5.00), LatLon(60.45, 4.98))) to null,
            Triple("voss", LatLon(60.628, 6.42), listOf(LatLon(60.80, 6.20), LatLon(60.80, 6.70), LatLon(60.55, 6.80), LatLon(60.45, 6.50),
                LatLon(60.50, 6.15))) to null,
            Triple("bømlo stord", LatLon(59.794, 5.144), listOf(LatLon(59.95, 5.10), LatLon(59.95, 5.60), LatLon(59.70, 5.65), LatLon(59.60, 5.30),
                LatLon(59.65, 5.05))) to null,
        )
        for ((a, max) in areas) for (v in 0..1) {
            val (name, start, stroke) = a
            val t0 = System.nanoTime()
            report("$name v$v", t0, runCatching { RoundtripPlanner(engine).planArea(start, stroke, opts, v, max) }.onFailure { println("  $it") }.getOrNull())
        }
        val home = LatLon(59.794218, 5.144344)
        for (dir in listOf(0.0, 90.0)) for (km in listOf(80.0, 150.0)) {
            val t0 = System.nanoTime()
            report("bremnes $dir° $km km", t0, runCatching { RoundtripPlanner(engine).plan(home, km, dir, opts) }.getOrNull())
        }
    }
}
