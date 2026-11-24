package com.curveon.pmtiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RegionExtractorTest {
    @Test
    fun `hilbert tile ids match the spec examples`() {
        assertEquals(0L, Pmtiles.tileId(0, 0, 0))
        assertEquals(1L, Pmtiles.tileId(1, 0, 0))
        assertEquals(2L, Pmtiles.tileId(1, 0, 1))
        assertEquals(3L, Pmtiles.tileId(1, 1, 1))
        assertEquals(4L, Pmtiles.tileId(1, 1, 0))
        assertEquals(5L, Pmtiles.tileId(2, 0, 0))
    }

    @Test
    fun `a zoom-10 mask covers exactly its descendants and ancestors`() {
        val (x, y) = 545 to 290 // somewhere in southern Norway
        val d = Pmtiles.tileId(10, x, y) - Pmtiles.zoomBase(10)
        assertEquals(x to y, Pmtiles.tileXY(10, d))
        val r = TileMask(longArrayOf(d, d + 1), 0.0, 0.0, 0.0, 0.0).ranges(8, 12)
        val expected = mutableListOf(Pmtiles.tileId(8, x / 4, y / 4), Pmtiles.tileId(9, x / 2, y / 2), Pmtiles.tileId(10, x, y))
        for (i in 0 until 2) for (j in 0 until 2) expected += Pmtiles.tileId(11, 2 * x + i, 2 * y + j)
        for (i in 0 until 4) for (j in 0 until 4) expected += Pmtiles.tileId(12, 4 * x + i, 4 * y + j)
        val covered = (0 until r.size).flatMap { (r.starts[it] until r.ends[it]).toList() }
        assertEquals(expected.sorted(), covered)
    }

    @Test
    fun `directories round trip`() {
        val e = listOf(Pmtiles.Entry(5, 0, 100, 1), Pmtiles.Entry(6, 100, 50, 3), Pmtiles.Entry(20, 0, 100, 1))
        assertEquals(e, Pmtiles.deserializeDirectory(Pmtiles.serializeDirectory(e)))
    }

    /** Cuts Bømlo (zoom 0–11) from the current Protomaps build. Run with -Dcurveon.online=true. */
    @Test
    fun `extracts a small region from the live build`() {
        assumeTrue(System.getProperty("curveon.online") == "true")
        val build = java.net.URL("https://build-metadata.protomaps.dev/builds.json").readText()
            .let { Regex("\"key\":\"(\\d{8}\\.pmtiles)\"").findAll(it).last().groupValues[1] }
        val region = Region(listOf(listOf(
            doubleArrayOf(5.0, 59.6), doubleArrayOf(5.4, 59.6), doubleArrayOf(5.4, 59.9), doubleArrayOf(5.0, 59.9), doubleArrayOf(5.0, 59.6)
        )))
        val out = File(System.getProperty("curveon.data"), "test-bomlo.pmtiles")
        RegionExtractor().extract("https://build.protomaps.com/$build", region, 11, out, onProgress = { println("  ${it.phase} ${it.doneBytes}/${it.totalBytes}") })
        assertTrue(out.length() > 10_000)
        println("wrote ${out.length()} bytes to $out from $build")
    }

    @Test
    fun `norway cover matches the official tool`() {
        val f = File(System.getProperty("curveon.data"), "../Curveon/tools/map/norway.geojson")
        assumeTrue(f.exists())
        val ids = RegionExtractor.coverTiles(Region.fromGeoJson(f.readText()), 0, 14)
        println("norway cover: ${ids.size} tiles (pmtiles extract reported 6024814)")
        // Within 2%: the official tool adds a margin at the outline; we follow it exactly.
        assertTrue(kotlin.math.abs(ids.size - 6_024_814) < 6_024_814 * 0.02)
    }

    /** Western Norway at full detail: exercises leaf directories on both sides. -Dcurveon.online=true */
    @Test
    fun `extracts western norway at zoom 14`() {
        assumeTrue(System.getProperty("curveon.online") == "true")
        val build = java.net.URL("https://build-metadata.protomaps.dev/builds.json").readText()
            .let { Regex("\"key\":\"(\\d{8}\\.pmtiles)\"").findAll(it).last().groupValues[1] }
        val region = Region(listOf(listOf(
            doubleArrayOf(4.8, 59.4), doubleArrayOf(7.0, 59.4), doubleArrayOf(7.0, 61.2), doubleArrayOf(4.8, 61.2), doubleArrayOf(4.8, 59.4)
        )))
        val out = File(System.getProperty("curveon.data"), "test-vestland.pmtiles")
        val t0 = System.currentTimeMillis()
        var last = 0L
        RegionExtractor().extract("https://build.protomaps.com/$build", region, 14, out, onProgress = {
            if (System.currentTimeMillis() - last > 5000) { last = System.currentTimeMillis(); println("  ${it.phase} ${it.doneBytes / 1_000_000}/${it.totalBytes / 1_000_000} MB") }
        })
        println("wrote ${out.length() / 1_000_000} MB in ${(System.currentTimeMillis() - t0) / 1000} s")
    }

    @Test
    fun `sortIndicesBy handles offsets past 128 GiB without signed overflow`() {
        val offsets = longArrayOf(200_000_000_000L, 100L, 140_000_000_000L, 137_438_953_472L, 0L)
        val sorted = RegionExtractor.sortIndicesBy(offsets.size) { offsets[it] }
        assertArrayEquals(intArrayOf(4, 1, 3, 2, 0), sorted)
    }

    @Test
    fun `rowIntervals cuts out interior hole rings using even-odd fill`() {
        val outer = listOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0),
            doubleArrayOf(10.0, 10.0), doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0)
        )
        val hole = listOf(
            doubleArrayOf(3.0, 3.0), doubleArrayOf(7.0, 3.0),
            doubleArrayOf(7.0, 7.0), doubleArrayOf(3.0, 7.0), doubleArrayOf(3.0, 3.0)
        )
        val spans = RegionExtractor.rowIntervals(Region(listOf(outer, hole)), 4.5, 5.5)
        // Band 4.5..5.5 passes through the hole: no interior span should cover x = 5.0.
        assertTrue("expected hole at lon=5.0 to be excluded, got $spans", spans.none { (a, b) -> a < 5.0 && b > 5.0 })
        assertTrue("expected left side [0,3] to be covered, got $spans", spans.any { (a, b) -> a <= 1.0 && b >= 2.0 })
        assertTrue("expected right side [7,10] to be covered, got $spans", spans.any { (a, b) -> a <= 8.0 && b >= 9.0 })
    }
}
