package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CityAreasTest {
    @Test fun `a tile is town when half of it is town squares`() {
        // Bergen's z12 tile, covered by squares: town; a quarter of it: not.
        val (tx, ty) = 2108 to 1180
        val (south, west, north, east) = CityAreas.tileBounds(tx, ty, 12)
        val all = ArrayList<Long>()
        var y = Math.floor(south * CityAreas.LAT_DIV).toInt()
        while (y.toDouble() / CityAreas.LAT_DIV < north) {
            var x = Math.floor(west * CityAreas.LON_DIV).toInt()
            while (x.toDouble() / CityAreas.LON_DIV < east) { all += CityAreas.squareKey(x, y); x++ }
            y++
        }
        assertTrue((tx to ty) in CityAreas.townTiles(all, 12))
        assertFalse((tx to ty) in CityAreas.townTiles(all.take(all.size / 4), 12))
    }

    @Test fun `bergen is a town, the villages and cabin areas are not`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N60.rd5").exists())
        val lookups = File(ProfileInstaller.install(Files.createTempDirectory("p").toFile()), "lookups.dat")
        val cache = Files.createTempDirectory("town").toFile()
        val towns = CityAreas(segments, cache, SurfaceReader(segments, lookups))
        val t0 = System.nanoTime()
        val squares = towns.squares("E5_N60").toHashSet()
        println("E5_N60: ${squares.size} town squares in %.1f s".format((System.nanoTime() - t0) / 1e9))
        fun town(lat: Double, lon: Double) = CityAreas.square(lon, lat) in squares
        assertTrue("Bergen sentrum", town(60.392, 5.324))
        assertTrue("Voss", town(60.628, 6.42))
        assertFalse("Bremnes", town(59.79, 5.14))
        assertFalse("Kvinnherad", town(59.98, 6.0))
        assertFalse("Svortland", town(59.795, 5.21))
        assertFalse("Norheimsund", town(60.37, 6.145))
        assertFalse("Leirvik", town(59.78, 5.50))
        assertFalse("Geilo cabins", town(60.535, 8.20))
        // Kept: a fresh one reads it from disk.
        assertEquals(squares.size, CityAreas(segments, cache, SurfaceReader(segments, lookups)).squares("E5_N60").size)
    }
}
