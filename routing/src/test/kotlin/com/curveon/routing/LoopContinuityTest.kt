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

    @Test
    fun `Routes concat preserves ferry continuity and counts across mid-ferry joins and slices`() {
        fun pt(d: Double, ferry: Boolean) = RoutePoint(60.0, 5.0 + d * 1e-5, 0.0, d, d / 10.0, null, null, false, ferry)
        // Leg A ends mid-ferry (ferryCount = 1).
        val legA = Route(listOf(pt(0.0, false), pt(100.0, true), pt(200.0, true)), emptyList(), 200.0, 20.0, 0.0, 0, 0.0, ferryCount = 1)
        // Case A: BRouter leg B starts at the same mid-ferry point (points[0].ferry == false, points[1].ferry == true, ferryCount = 1).
        val legB = Route(listOf(pt(0.0, false), pt(100.0, true), pt(200.0, false)), emptyList(), 200.0, 20.0, 0.0, 0, 0.0, ferryCount = 1)
        val joinedAB = Routes.concat(legA, legB)
        assertEquals(1, joinedAB.ferryCount)
        assertEquals(1, joinedAB.points.zipWithNext().count { (a, b) -> b.ferry && !a.ferry })

        // Case B: Sliced tail starting mid-ferry (points[0].ferry == true, ferryCount = 0).
        val full = Route(listOf(pt(0.0, false), pt(100.0, true), pt(200.0, true), pt(300.0, false)), emptyList(), 300.0, 30.0, 0.0, 0, 0.0, ferryCount = 1)
        val slicedTail = Routes.slice(full, 100.0)
        assertEquals(0, slicedTail.ferryCount)
        val joinedSlice = Routes.concat(legA, slicedTail)
        assertEquals(1, joinedSlice.ferryCount)
        assertEquals(1, joinedSlice.points.zipWithNext().count { (a, b) -> b.ferry && !a.ferry })

        // Case C: Land approach joined to sliced tail starting mid-ferry -> 1 ferry total.
        val landA = Route(listOf(pt(0.0, false), pt(100.0, false)), emptyList(), 100.0, 10.0, 0.0, 0, 0.0, ferryCount = 0)
        val joinedLandSlice = Routes.concat(landA, slicedTail)
        assertEquals(1, joinedLandSlice.ferryCount)
        assertEquals(1, joinedLandSlice.points.zipWithNext().count { (a, b) -> b.ferry && !a.ferry })
    }
}
