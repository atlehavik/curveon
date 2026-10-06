package com.vibenav.pmtiles

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
    fun `directories round trip`() {
        val e = listOf(Pmtiles.Entry(5, 0, 100, 1), Pmtiles.Entry(6, 100, 50, 3), Pmtiles.Entry(20, 0, 100, 1))
        assertEquals(e, Pmtiles.deserializeDirectory(Pmtiles.serializeDirectory(e)))
    }

    /** Cuts Bømlo (zoom 0–11) from the current Protomaps build. Run with -Dvibenav.online=true. */
    @Test
    fun `extracts a small region from the live build`() {
        assumeTrue(System.getProperty("vibenav.online") == "true")
        val build = java.net.URL("https://build-metadata.protomaps.dev/builds.json").readText()
            .let { Regex("\"key\":\"(\\d{8}\\.pmtiles)\"").findAll(it).last().groupValues[1] }
        val region = Region(listOf(listOf(
            doubleArrayOf(5.0, 59.6), doubleArrayOf(5.4, 59.6), doubleArrayOf(5.4, 59.9), doubleArrayOf(5.0, 59.9), doubleArrayOf(5.0, 59.6)
        )))
        val out = File(System.getProperty("vibenav.data"), "test-bomlo.pmtiles")
        RegionExtractor().extract("https://build.protomaps.com/$build", region, 11, out, onProgress = { println("  ${it.phase} ${it.doneBytes}/${it.totalBytes}") })
        assertTrue(out.length() > 10_000)
        println("wrote ${out.length()} bytes to $out from $build")
    }

    @Test
    fun `norway cover matches the official tool`() {
        val f = File(System.getProperty("vibenav.data"), "../VibeNav/tools/map/norway.geojson")
        assumeTrue(f.exists())
        val ids = RegionExtractor.coverTiles(Region.fromGeoJson(f.readText()), 0, 14)
        println("norway cover: ${ids.size} tiles (pmtiles extract reported 6024814)")
        // Within 2%: the official tool adds a margin at the outline; we follow it exactly.
        assertTrue(kotlin.math.abs(ids.size - 6_024_814) < 6_024_814 * 0.02)
    }

    /** Western Norway at full detail: exercises leaf directories on both sides. -Dvibenav.online=true */
    @Test
    fun `extracts western norway at zoom 14`() {
        assumeTrue(System.getProperty("vibenav.online") == "true")
        val build = java.net.URL("https://build-metadata.protomaps.dev/builds.json").readText()
            .let { Regex("\"key\":\"(\\d{8}\\.pmtiles)\"").findAll(it).last().groupValues[1] }
        val region = Region(listOf(listOf(
            doubleArrayOf(4.8, 59.4), doubleArrayOf(7.0, 59.4), doubleArrayOf(7.0, 61.2), doubleArrayOf(4.8, 61.2), doubleArrayOf(4.8, 59.4)
        )))
        val out = File(System.getProperty("vibenav.data"), "test-vestland.pmtiles")
        val t0 = System.currentTimeMillis()
        var last = 0L
        RegionExtractor().extract("https://build.protomaps.com/$build", region, 14, out, onProgress = {
            if (System.currentTimeMillis() - last > 5000) { last = System.currentTimeMillis(); println("  ${it.phase} ${it.doneBytes / 1_000_000}/${it.totalBytes / 1_000_000} MB") }
        })
        println("wrote ${out.length() / 1_000_000} MB in ${(System.currentTimeMillis() - t0) / 1000} s")
    }
}
