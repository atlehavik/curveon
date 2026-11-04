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

    @Test
    fun theLimitChangesAsTheRiderPassesTheSign() {
        val free = FreeRideSigns()
        free.look(RouteLimits(60, listOf(sign(300.0, 80))), at(0.0))
        assertEquals(60, free.limit)
        free.ride(at(290.0))
        assertEquals(60, free.limit)
        free.ride(at(305.0))
        assertEquals(80, free.limit)
    }

    @Test
    fun aSignStaysALittleAfterTheLimitHasChanged() {
        val free = FreeRideSigns()
        free.look(RouteLimits(60, listOf(sign(300.0, 80))), at(0.0))
        assertEquals(1, free.ride(at(305.0)).size)
        assertEquals(0, free.ride(at(330.0)).size)
    }

    @Test
    fun aLookThatStartsJustBeforeASignWaitsForTheSign() {
        val free = FreeRideSigns()
        free.look(RouteLimits(60, listOf(sign(300.0, 80))), at(0.0))
        // Its first sample lies past the place where the limit begins, so this look says 80 from the start.
        free.look(RouteLimits(80, emptyList()), at(280.0))
        assertEquals(60, free.limit)
        free.ride(at(305.0))
        assertEquals(80, free.limit)
    }

    @Test
    fun aLookPutsRightWhatNoSignSaid() {
        val free = FreeRideSigns()
        free.look(RouteLimits(60, emptyList()), at(0.0))
        free.look(RouteLimits(50, emptyList()), at(150.0))
        assertEquals(50, free.limit)
    }

    @Test
    fun aSignOnARoadNotTakenDoesNotChangeTheLimit() {
        val free = FreeRideSigns()
        // 40 m down a side road, which leaves at 300 m: the rider rides straight on.
        val side = at(300.0, 40.0).let { SpeedChange(340.0, it.lat, it.lon, 0.0, 30) }
        free.look(RouteLimits(60, listOf(side)), at(0.0))
        for (m in 0..500 step 20) free.ride(at(m.toDouble()))
        assertEquals(60, free.limit)
    }

    @Test
    fun aSignCloseByOnARoadGoingAnotherWayDoesNotChangeTheLimit() {
        val free = FreeRideSigns()
        // On a roundabout's way out to the north: the rider rides on east, passing 15 m from it.
        val side = at(300.0, 15.0).let { SpeedChange(340.0, it.lat, it.lon, 0.0, 50) }
        free.look(RouteLimits(60, listOf(side)), at(0.0))
        for (m in 0..500 step 20) free.ride(at(m.toDouble()))
        assertEquals(60, free.limit)
    }

    @Test
    fun aSideRoadsSignShowsButNeverChangesTheLimit() {
        val free = FreeRideSigns()
        // Right beside the road the rider is on, going nearly the same way.
        val side = at(300.0, 15.0).let { SpeedChange(340.0, it.lat, it.lon, 60.0, 50) }
        free.look(RouteLimits(60, emptyList(), sideRoad = side), at(0.0))
        assertEquals(listOf(50), free.ride(at(100.0)).map { it.limit })
        for (m in 100..500 step 20) free.ride(at(m.toDouble()))
        assertEquals(60, free.limit)
    }

    @Test
    fun aFailedLookLeavesTheLimitToBeReadAnotherWay() {
        val free = FreeRideSigns()
        free.look(RouteLimits(60, emptyList()), at(0.0))
        free.look(null, at(150.0))
        assertEquals(null, free.limit)
    }

    @Test
    fun aLimitFirstReadFurtherOnSaysNothingOfTheRidersPlace() {
        val free = FreeRideSigns()
        free.look(RouteLimits(80, emptyList(), listOf(100.0..400.0)), at(0.0))
        assertEquals(null, free.limit)
    }

    @Test
    fun aSignSoonSaysToWaitOnlyForItsOwnLimit() {
        val free = FreeRideSigns()
        free.look(RouteLimits(60, listOf(sign(300.0, 80))), at(0.0))
        assertEquals(false, free.signSoon(80, at(0.0)))
        assertEquals(true, free.signSoon(80, at(250.0)))
        assertEquals(false, free.signSoon(50, at(250.0)))
    }
}
