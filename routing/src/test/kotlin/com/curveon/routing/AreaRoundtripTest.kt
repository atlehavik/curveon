package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Roundtrips in an area drawn on the map. */
class AreaRoundtripTest {
    private val home = LatLon(59.794218, 5.144344) // Bremnes, on Bømlo
    private val bomlo = listOf(LatLon(59.86, 5.10), LatLon(59.86, 5.25), LatLon(59.72, 5.25), LatLon(59.62, 5.20), LatLon(59.62, 5.08), LatLon(59.74, 5.05))
    private val stord = listOf(LatLon(59.88, 5.40), LatLon(59.88, 5.55), LatLon(59.78, 5.62), LatLon(59.70, 5.55), LatLon(59.70, 5.40), LatLon(59.78, 5.35))

    @Test
    fun `the area's geometry`() {
        val a = SketchArea(bomlo + bomlo.first())
        assertEquals(6, a.outline.size)
        assertTrue(a.contains(home))
        assertFalse(a.contains(LatLon(59.79, 5.50)))
        val vias = a.vias(4, 0.8, 0.0, home)
        assertEquals(4, vias.size)
        assertTrue(vias.all { a.contains(it) })
        // Ridden from the via nearest the start.
        assertEquals(vias.minBy { Geo.distance(it, home) }, vias.first())
    }

    @Test
    fun `loops stay in the drawn area`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        for ((name, stroke) in listOf("bømlo" to bomlo, "stord" to stord)) for (variant in 0..2) {
            val t0 = System.nanoTime()
            val r = RoundtripPlanner(engine) { println(it) }.planArea(home, stroke, RouteOptions(RoadStyle.CURVY), variant)
            val outside = SketchArea(stroke).outsideShare(r)
            println("%s v%d -> %.1f km curvy=%d ferries=%d outside=%.0f%% (%.1f s)".format(name, variant, r.distanceMeters / 1000,
                r.curviness, r.ferryCount, outside * 100, (System.nanoTime() - t0) / 1e9))
            assertTrue("$name v$variant rides outside the area", outside < 0.25)
        }
    }
}
