package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SurfaceReaderTest {
    private val segments = File(System.getProperty("curveon.segments") ?: "")

    private fun reader() = SurfaceReader(segments, File(ProfileInstaller.install(Files.createTempDirectory("p").toFile()), "lookups.dat"))

    @Test
    fun `classifies like a rider would`() {
        assertEquals(SurfaceKind.GRAVEL to 1, SurfaceReader.classify("unclassified", "gravel", null))
        assertEquals(SurfaceKind.GRAVEL to 0, SurfaceReader.classify("track", null, "grade2"))
        assertEquals(SurfaceKind.SOFT to 0, SurfaceReader.classify("track", null, null))
        assertEquals(SurfaceKind.SOFT to 0, SurfaceReader.classify("service", "dirt", null))
        assertEquals(SurfaceKind.PAVED to 1, SurfaceReader.classify("residential", null, null))
        assertEquals(SurfaceKind.PAVED to 1, SurfaceReader.classify("unclassified", "asphalt", null))
        assertEquals(SurfaceKind.ACCESS to 0, SurfaceReader.classify("service", null, null))
        assertEquals(SurfaceKind.ACCESS to 0, SurfaceReader.classify("track", "asphalt", null))
        assertEquals(null, SurfaceReader.classify("secondary", null, null))
        assertEquals(null, SurfaceReader.classify("path", "gravel", null))
    }

    @Test
    fun `keeps only the access roads that lead to gravel`() {
        fun way(kind: SurfaceKind, vararg c: Double) = SurfaceWay(kind, 0, c)
        val gravel = way(SurfaceKind.GRAVEL, 5.0, 60.0, 5.01, 60.0)
        val toGravel = way(SurfaceKind.ACCESS, 5.02, 60.0, 5.01, 60.0)
        val further = way(SurfaceKind.ACCESS, 5.03, 60.01, 5.025, 60.005, 5.02, 60.0)
        val driveway = way(SurfaceKind.ACCESS, 6.0, 61.0, 6.01, 61.0)
        assertEquals(listOf(gravel, toGravel, further), SurfaceReader.leadingToGravel(listOf(gravel, driveway, further, toGravel)).sortedBy { it.coords[0] })
    }

    @Test
    fun `reads the unpaved roads around Bomlo and Bergen`() {
        assumeTrue(File(segments, "E5_N55.rd5").exists() && File(segments, "E5_N60.rd5").exists())
        val r = reader()
        for ((name, box) in listOf(
            "Bømlo" to doubleArrayOf(59.70, 5.05, 59.85, 5.35),
            "Bergen" to doubleArrayOf(60.30, 5.20, 60.45, 5.45),
            "Hardangervidda" to doubleArrayOf(60.10, 7.30, 60.30, 7.70)
        )) {
            val t0 = System.nanoTime()
            val ways = r.ways(box[0], box[1], box[2], box[3])
            val ms = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime(); r.ways(box[0], box[1], box[2], box[3]); val cached = (System.nanoTime() - t1) / 1e6
            val byKind = ways.groupingBy { it.kind }.eachCount()
            println("$name: ${ways.size} ways $byKind, ${ways.sumOf { it.coords.size / 2 }} points, %.0f ms (cached %.1f ms)".format(ms, cached))
            assertTrue(ways.isNotEmpty())
            for (w in ways) for (k in w.coords.indices step 2) {
                assertTrue(w.coords[k] in box[1] - 0.1..box[3] + 0.1 && w.coords[k + 1] in box[0] - 0.1..box[2] + 0.1)
            }
        }
        assertEquals(0, r.syncErrors)
    }
}
