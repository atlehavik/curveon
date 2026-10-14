package com.curveon.data.road

import com.curveon.data.road.RoadRef.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadRefTest {
    @Test
    fun `numbers from the Norwegian road register`() {
        assertEquals(RoadRef("E39", Style.GREEN), RoadRef.fromNvdb(1_000_039))
        assertEquals(RoadRef("Rv7", Style.GREEN), RoadRef.fromNvdb(2_000_007))
        assertEquals(RoadRef("Fv5012", Style.WHITE), RoadRef.fromNvdb(3_005_012))
        assertEquals(RoadRef("Kv1209", Style.GREY), RoadRef.fromNvdb(4_001_209))
        assertNull(RoadRef.fromNvdb(9_000_001))
    }

    @Test
    fun `OpenStreetMap refs read the country's way`() {
        assertEquals(RoadRef("E39", Style.GREEN), RoadRef.fromOsm("E 39", "trunk", "NO"))
        assertEquals(RoadRef("E18", Style.GREEN), RoadRef.fromOsm("E 18;E 6", "motorway", "SE"))
        assertEquals(RoadRef("Rv7", Style.GREEN), RoadRef.fromOsm("7", "trunk", "NO"))
        assertEquals(RoadRef("Fv541", Style.WHITE), RoadRef.fromOsm("541", "secondary", "NO"))
        assertEquals(RoadRef("40", Style.BLUE), RoadRef.fromOsm("40", "trunk", "SE"))
        assertEquals(RoadRef("A7", Style.BLUE), RoadRef.fromOsm("A 7", "motorway", "DE"))
        assertEquals(RoadRef("B27", Style.YELLOW), RoadRef.fromOsm("B 27", "primary", "DE"))
        assertEquals(RoadRef("21", Style.YELLOW), RoadRef.fromOsm("21", "primary", "DK"))
        assertEquals(RoadRef("4", Style.RED), RoadRef.fromOsm("4", "trunk", "FI"))
        assertEquals(RoadRef("N7", Style.WHITE), RoadRef.fromOsm("N 7", "primary", "FR"))
        assertNull(RoadRef.fromOsm(" ", "primary", "NO"))
        assertNull(RoadRef.fromOsm(null, "primary", "NO"))
    }
}
