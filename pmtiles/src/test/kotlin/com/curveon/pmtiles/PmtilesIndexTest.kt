package com.curveon.pmtiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

class PmtilesIndexTest {
    /** Where the real Norway map has tiles: the map uses this to know where it needs online tiles. */
    @Test
    fun `norway map covers norway and not sweden`() {
        val file = File(System.getProperty("curveon.data"), "norway.pmtiles")
        assumeTrue(file.exists())
        val index = PmtilesIndex(file)
        fun has(lat: Double, lon: Double, z: Int): Boolean {
            val n = 1 shl z
            val r = Math.toRadians(lat)
            return index.has(z, ((lon + 180) / 360 * n).toInt(), ((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt())
        }
        for (z in listOf(6, 10, index.maxZoom)) {
            assertTrue(has(59.794, 5.144, z)) // Bømlo
            assertTrue(has(69.65, 18.96, z)) // Tromsø
            assertFalse(has(59.33, 18.07, z)) // Stockholm
        }
        assertFalse(has(59.55, 12.25, index.maxZoom)) // Sweden, 30 km past the border
    }
}
