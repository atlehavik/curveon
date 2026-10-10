package com.curveon.data.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingTilesTest {
    @Test
    fun `bergen to hamar needs the two tiles it crosses`() {
        // Bergen 60.39 N 5.32 E, Hamar 60.79 N 11.07 E
        assertEquals(listOf("E5_N60", "E10_N60"), RoutingData.tilesFor(60.39, 5.32, 60.79, 11.07))
    }

    @Test
    fun `the margin reaches into the next tile near an edge`() {
        assertEquals(listOf("E5_N55", "E5_N60"), RoutingData.tilesFor(60.1, 6.0, 60.2, 7.0))
    }

    @Test
    fun `west and south of zero`() {
        assertEquals(listOf("W5_S5"), RoutingData.tilesFor(-3.0, -3.0, -2.0, -2.0))
    }
}
