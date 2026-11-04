package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

class SignsAheadTest {
    /** A rider going east along 60°N, at [metres] from the start. */
    private fun at(metres: Double, northM: Double = 0.0) =
        LatLon(60.0 + northM / 110_574.0, 5.0 + metres / (111_320.0 * cos(Math.toRadians(60.0))))

    private fun sign(metres: Double, limit: Int = 50, bearing: Double = 90.0) = at(metres).let { SpeedChange(metres, it.lat, it.lon, bearing, limit) }

    private fun signs() = SignsAhead<SpeedChange>({ it.lat }, { it.lon }, { it.bearingDeg }) { a, b ->
        a.limit == b.limit && Geo.distance(a.lat, a.lon, b.lat, b.lon) < 60.0
    }

    @Test
    fun aSignStaysWhenALookDoesNotHaveIt() {
        val s = signs()
        val sign = sign(500.0)
        s.add(listOf(sign), at(0.0))
        s.add(emptyList(), at(150.0))
        assertEquals(listOf(sign), s.ahead(at(150.0)))
        assertEquals(listOf(sign), s.ahead(at(480.0)))
    }

    @Test
    fun aSignGoesOnceRiddenPast() {
        val s = signs()
        s.add(listOf(sign(500.0)), at(0.0))
        s.ahead(at(495.0))
        assertEquals(1, s.ahead(at(505.0)).size)
        assertEquals(0, s.ahead(at(530.0)).size)
    }

    @Test
    fun aSignGoesOnceRiddenAwayFrom() {
        val s = signs()
        s.add(listOf(sign(500.0)), at(0.0))
        s.ahead(at(400.0))
        // Turned off north at 400 m, before reaching it.
        assertEquals(1, s.ahead(at(400.0, northM = 40.0)).size)
        assertEquals(0, s.ahead(at(400.0, northM = 200.0)).size)
    }

    @Test
    fun aSignSeenAgainStaysWhereItFirstStood() {
        val s = signs()
        val first = sign(500.0)
        s.add(listOf(first), at(0.0))
        s.add(listOf(sign(503.0)), at(150.0))
        assertEquals(listOf(first), s.ahead(at(150.0)))
    }

    @Test
    fun anotherLimitAtTheSamePlaceIsAnotherSign() {
        val s = signs()
        s.add(listOf(sign(500.0, 50)), at(0.0))
        s.add(listOf(sign(505.0, 80)), at(150.0))
        assertEquals(2, s.ahead(at(150.0)).size)
    }

    /** In a roundabout the way the road goes at the sign can point back at the rider; that is not ridden past. */
    @Test
    fun aSignInARoundaboutIsNotPassedBeforeTheRiderGetsThere() {
        val s = signs()
        val sign = sign(300.0, bearing = 270.0)
        s.add(listOf(sign), at(100.0))
        assertEquals(listOf(sign), s.ahead(at(100.0)))
        assertEquals(listOf(sign), s.ahead(at(200.0)))
    }

    @Test
    fun clearForgetsThem() {
        val s = signs()
        s.add(listOf(sign(500.0)), at(0.0))
        s.clear()
        assertEquals(0, s.ahead(at(0.0)).size)
    }
}
