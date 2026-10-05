package com.vibenav.ui.viewmodel

import com.vibenav.data.model.CompassDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionTest {
    @Test
    fun `mean of north and east is north-east`() {
        assertEquals(45.0, DashboardViewModel.meanDirection(setOf(CompassDirection.NORTH, CompassDirection.EAST)), 0.01)
    }

    @Test
    fun `mean wraps around north`() {
        assertEquals(0.0, DashboardViewModel.meanDirection(setOf(CompassDirection.NORTH_WEST, CompassDirection.NORTH_EAST)) % 360, 0.01)
    }

    @Test
    fun `distance rounding`() {
        assertEquals(340, DashboardViewModel.roundDistance(337.0))
        assertEquals(1300, DashboardViewModel.roundDistance(1349.0))
    }
}
