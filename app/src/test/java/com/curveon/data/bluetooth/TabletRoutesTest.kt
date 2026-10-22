package com.curveon.data.bluetooth

import com.curveon.data.model.RouteItem
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.SavedRoutes
import com.curveon.routing.LatLon
import com.curveon.routing.RoadStyle
import com.curveon.routing.RouteOptions
import com.curveon.routing.SurfacePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A trip sent from the phone arrives on the tablet as the same planned trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabletRoutesTest {

    private val line = (0 until 400).map { LatLon(60.0 + it * 0.001, 10.0 + it * 0.001) }

    @Test
    fun `a track comes over as a track, with its line`() {
        val sent = SavedRoutes.trackItem("Valdres", line, 55_000.0, 70, RouteOptions(RoadStyle.FASTEST, SurfacePreference.MIXED), groupId = 7)
        val got = TabletRoutes.decode(TabletRoutes.encode(sent))!!
        assertEquals("Valdres", got.title)
        assertEquals(sent.polylineJson, got.polylineJson)
        assertEquals(55f, got.totalDistanceKm, 0.01f)
        assertEquals(70, got.estimatedMinutes)
        // Kept among the planned trips on the tablet, in no group of the phone's.
        assertTrue(got.inPlanned)
        assertNull(got.groupId)
        assertEquals(0L, got.id)
        val goal = SavedRoutes.toGoal(got) as NavigationGoal.Track
        assertEquals(SavedRoutes.decode(sent.polylineJson), goal.points)
    }

    @Test
    fun `a trip keeps its stops and destination`() {
        val waypoints = """{"options":{"style":"CURVY","surface":"PAVED"},"stops":[{"name":"Fagernes","lat":60.98,"lon":9.23}],""" +
            """"destination":{"name":"Beitostølen","lat":61.25,"lon":8.91}}"""
        val sent = RouteItem(title = "Over fjellet", totalDistanceKm = 210f, estimatedMinutes = 200, polylineJson = SavedRoutes.encode(line), waypointsJson = waypoints)
        val goal = SavedRoutes.toGoal(TabletRoutes.decode(TabletRoutes.encode(sent))!!) as NavigationGoal.Destination
        assertEquals("Beitostølen", goal.name)
        assertEquals(listOf("Fagernes"), goal.stops.map { it.name })
        assertEquals(RoadStyle.CURVY, goal.options.roadStyle)
    }

    @Test
    fun `something else than a trip is not taken for one`() {
        assertNull(TabletRoutes.decode("H4sI"))
        assertNull(TabletRoutes.decode("not base64 at all"))
    }
}
