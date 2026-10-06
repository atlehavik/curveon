package com.curveon.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceSearchTest {
    @Test
    fun `normalises norwegian letters like the index builder`() {
        assertEquals("alesund", PlaceSearch.normalise("Ålesund"))
        assertEquals("tromso", PlaceSearch.normalise("Tromsø"))
        assertEquals("laerdal", PlaceSearch.normalise("Lærdal"))
        assertEquals("st olavs gate", PlaceSearch.normalise("St. Olavs  gate"))
    }

    @Test
    fun `splits off a house number`() {
        val q = PlaceSearch.parseQuery("Storgata 12b Lillehammer")
        assertEquals(listOf("storgata", "lillehammer"), q.terms)
        assertEquals("12b", q.houseNumber)
    }

    @Test
    fun `a leading number is part of the name`() {
        val q = PlaceSearch.parseQuery("7 eleven")
        assertNull(q.houseNumber)
        assertEquals(listOf("7", "eleven"), q.terms)
    }
}
