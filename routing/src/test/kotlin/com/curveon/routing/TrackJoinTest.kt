package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackJoinTest {

    /** A loop from home that passes 100 m from home again halfway, then ends 2 km away. */
    @Test
    fun loopFromHomeJoinsAtTheStartNotWhereItPassesHomeAgain() {
        val home = LatLon(59.79, 5.15)
        val out = (0..20).map { Geo.destination(home, 0.0, it * 100.0) }
        val back = (0..20).map { Geo.destination(Geo.destination(home, 90.0, 100.0), 0.0, (20 - it) * 100.0) }
        val away = (1..20).map { Geo.destination(home, 180.0, it * 100.0) }
        val track = out + back + away
        // The second pass (in [back]) is just as close as the start would be from a spot between them.
        val rider = Geo.destination(home, 90.0, 60.0)
        assertEquals(out.size + back.lastIndex, TrackFollower.nearestIndex(rider, track))
        assertEquals(0, TrackFollower.firstIndexWithin(rider, track, TrackFollower.ON_TRACK_M))
        assertNull(TrackFollower.firstIndexWithin(Geo.destination(home, 270.0, 5_000.0), track, TrackFollower.ON_TRACK_M))
    }
}
