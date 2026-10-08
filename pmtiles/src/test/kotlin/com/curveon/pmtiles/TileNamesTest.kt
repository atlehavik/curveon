package com.curveon.pmtiles

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TileNamesTest {
    /** Reads names from the real Norway map in ../Curveon-data (skipped when it isn't there). */
    @Test
    fun `reads places, pois and streets from the norway map`() {
        val file = File(System.getProperty("curveon.data"), "norway.pmtiles")
        assumeTrue(file.exists())
        val t0 = System.currentTimeMillis()
        val entries = TileNames(file).read()
        val byKind = entries.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
        println("${entries.size} entries in ${(System.currentTimeMillis() - t0) / 1000}s: ${byKind.take(20)}")
        println(entries.filter { it.name == "Storgata" }.take(8).joinToString("\n") { "${it.name} – ${it.area} ${it.lat},${it.lon}" })
        println(entries.filter { it.name.startsWith("Lillehammer") }.take(5))
        assertTrue(entries.any { it.kind == "street" && it.name == "Storgata" && it.area == "Lillehammer" })
        assertTrue(entries.any { it.kind == "town" && it.name == "Lillehammer" })
    }
}
