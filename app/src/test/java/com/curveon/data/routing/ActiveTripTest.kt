package com.curveon.data.routing

import androidx.test.core.app.ApplicationProvider
import com.curveon.routing.LatLon
import com.curveon.routing.RouteOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The trip being navigated survives Android ending Curveon on the way, and only then. */
@RunWith(RobolectricTestRunner::class)
class ActiveTripTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val trip = ActiveTrip(context)

    @Test
    fun theTripBeingNavigatedComesBackWithItsGroupLink() {
        val goal = NavigationGoal.Destination("Bergen", LatLon(60.39, 5.32), RouteOptions(),
            stops = listOf(Stop("Dale", LatLon(60.58, 5.82))), shared = "trip-17")
        trip.save(goal)
        assertEquals(goal, ActiveTrip(context).load())

        val track = NavigationGoal.Track("Trollstigen", listOf(LatLon(62.4, 7.6), LatLon(62.5, 7.7)), RouteOptions())
        trip.save(track)
        assertEquals(track, trip.load())
    }

    @Test
    fun nothingOnceTheTripEndedOrCurveonWasClosedOrLongAgo() {
        val goal = NavigationGoal.Destination("Bergen", LatLon(60.39, 5.32), RouteOptions())
        trip.save(goal)
        trip.save(null)
        assertNull(trip.load())

        trip.save(goal)
        ActiveTrip.forget(context)
        assertNull(trip.load())

        trip.save(goal)
        File(context.filesDir, "active-trip.json").setLastModified(System.currentTimeMillis() - 13 * 60 * 60_000L)
        assertNull(trip.load())
    }
}
