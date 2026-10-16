package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GravelHeatTest {
    private val segments = File(System.getProperty("curveon.segments") ?: "")

    @Test
    fun `names segment files`() {
        assertEquals(5 to 60, GravelHeat.origin("E5_N60"))
        assertEquals(-5 to -10, GravelHeat.origin("W5_S10"))
        assertEquals("E10_N55", GravelHeat.tileName(10, 55))
        assertEquals("W5_N40", GravelHeat.tileName(Math.floorDiv(Math.floor(-0.5).toInt(), 5) * 5, 40))
    }

    @Test
    fun `counts gravel per square and keeps it`() {
        assumeTrue(File(segments, "E5_N60.rd5").exists())
        val lookups = File(ProfileInstaller.install(Files.createTempDirectory("p").toFile()), "lookups.dat")
        val cache = Files.createTempDirectory("heat").toFile()
        val heat = GravelHeat(segments, cache, SurfaceReader(segments, lookups))
        assertEquals(listOf("E5_N60"), heat.tiles(60.2, 5.2, 60.5, 5.6))
        val t0 = System.nanoTime()
        val cells = heat.cells("E5_N60")
        val s = (System.nanoTime() - t0) / 1e9
        val w = cells.map { it.weight }.sorted()
        println("E5_N60: ${cells.size} cells in %.1f s; weight median %.1f, p90 %.1f, p99 %.1f, max %.1f".format(
            s, w[w.size / 2], w[w.size * 9 / 10], w[w.size * 99 / 100], w.last()))
        assertTrue(cells.size > 1000)
        assertTrue(cells.all { it.lon in 5.0..10.0 && it.lat in 60.0..65.0 })
        // Read back from disk by a fresh instance.
        val again = GravelHeat(segments, cache, SurfaceReader(segments, lookups))
        assertTrue(again.isReady("E5_N60"))
        assertEquals(cells.size, again.cells("E5_N60").size)
    }
}
