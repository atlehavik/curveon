package com.curveon.data.routing

import com.curveon.routing.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackToTripTest {
    /** A line due north from 60°N, [km] long, a point every 100 m. */
    private fun line(km: Int) = (0..km * 10).map { LatLon(60.0 + it * 100.0 / 111_195.0, 10.0) }

    @Test
    fun `a short track needs no via points`() {
        assertTrue(Navigator.pointsAlong(line(3), 5_000.0).isEmpty())
    }

    @Test
    fun `via points are spread along a longer track, never at its ends`() {
        val l = line(52)
        val vias = Navigator.pointsAlong(l, 5_000.0)
        assertEquals(10, vias.size)
        assertTrue(vias.none { it == l.first() || it == l.last() })
        assertEquals(vias.sortedBy { it.lat }, vias)
        // 52 km in 11 parts: about 4.7 km apart.
        assertEquals(4.7, (vias[1].lat - vias[0].lat) * 111.195, 0.3)
    }

    @Test
    fun `a very long track is capped`() {
        assertEquals(30, Navigator.pointsAlong(line(400), 5_000.0).size)
    }
}
