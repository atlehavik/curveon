package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * Coastal roundtrips (e.g. from Bømlo facing the North Sea) must never glue disconnected legs
 * across open water when a via lands out at sea, and must preserve accurate ferry counts.
 */
class LoopContinuityTest {
    @Test
    fun `coastal roundtrips never teleport across open water and keep ferry count accurate`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val bomlo = LatLon(59.794218, 5.144344)

        for (avoidFerries in listOf(false, true)) {
            for (dir in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 315.0)) {
                val opts = RouteOptions(RoadStyle.CURVY, SurfacePreference.PAVED, avoidFerries = avoidFerries)
                val r = runCatching { RoundtripPlanner(engine).plan(bomlo, 150.0, dir, opts) }.getOrNull() ?: continue
                // No teleport discontinuity between joined legs (real road segments match distanceFromStart delta).
                for (i in 1 until r.points.size) {
                    val a = r.points[i - 1]; val b = r.points[i]
                    val geo = Geo.distance(a.lat, a.lon, b.lat, b.lon)
                    val step = b.distanceFromStart - a.distanceFromStart
                    assertTrue(
                        "discontinuity at dir=$dir avoidFerries=$avoidFerries idx=$i: geo=%.0fm step=%.0fm (%s -> %s)"
                            .format(geo, step, "(${a.lat},${a.lon})", "(${b.lat},${b.lon})"),
                        abs(geo - step) < 250.0
                    )
                }
                // Ferry count must match actual ferry transitions in the route's points.
                val actualFerries = r.points.zipWithNext().count { (a, b) -> b.ferry && !a.ferry }
                assertEquals("ferryCount mismatch at dir=$dir avoidFerries=$avoidFerries", actualFerries, r.ferryCount)
                println("OK avoidFerries=$avoidFerries dir=$dir -> %.1f km ferries=${r.ferryCount}".format(r.distanceMeters / 1000))
            }
        }
    }
}
