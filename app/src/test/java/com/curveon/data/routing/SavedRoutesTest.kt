package com.curveon.data.routing

import com.curveon.routing.LatLon
import com.curveon.routing.RoadStyle
import com.curveon.routing.Route
import com.curveon.routing.RouteOptions
import com.curveon.routing.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedRoutesTest {
    private fun point(lat: Double, lon: Double, at: Double) = RoutePoint(lat, lon, 0.0, at, at / 20, null, null, false, false)

    @Test
    fun savedTripComesBackWithItsStopsStartAndLineSoItCanBeEdited() {
        val route = Route(
            listOf(point(60.63, 6.42, 0.0), point(60.55, 6.10, 20_000.0), point(60.39, 5.32, 99_000.0)),
            emptyList(), 99_000.0, 5_000.0, 300.0, 40, 0.0, 0
        )
        val goal = NavigationGoal.Destination(
            "Bergen", LatLon(60.39, 5.32), RouteOptions(RoadStyle.CURVY),
            stops = listOf(Stop("Dale", LatLon(60.58, 5.82)), Stop("Near Vaksdal", LatLon(60.48, 5.73), via = true)),
            start = Stop("Voss", LatLon(60.63, 6.42))
        )
        val item = SavedRoutes.toItem(goal, route, "Voss – Bergen").copy(id = 7)
        // Points the route was dragged through aren't named in the list.
        assertEquals("via Dale", item.subtitle)

        val back = SavedRoutes.toGoal(item) as NavigationGoal.Destination
        assertEquals(goal.stops, back.stops)
        assertEquals(goal.start, back.start)
        assertEquals(LatLon(60.39, 5.32), back.destination)
        assertEquals(SavedRef(7, "Voss – Bergen"), back.saved)
        assertEquals(RoadStyle.CURVY, back.options.roadStyle)
        // Until it is changed, it rides the saved line.
        assertNotNull(back.fixedLine)
        assertEquals(3, back.fixedLine!!.size)
    }
}
