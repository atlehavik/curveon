package com.curveon.data.routing

import androidx.test.core.app.ApplicationProvider
import com.curveon.routing.LatLon
import com.curveon.routing.RoadStyle
import com.curveon.routing.RouteOptions
import com.curveon.routing.SurfacePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlanDraftTest {
    private val draft = PlanDraft(ApplicationProvider.getApplicationContext())

    @Test
    fun tripBeingPlannedComesBackAsItWas() {
        val loop = NavigationGoal.Roundtrip(120.0, 45.0, RouteOptions(RoadStyle.CURVY), variant = 2, name = "Loop", maxKm = 150)
        val goal = NavigationGoal.Destination(
            "Bergen", LatLon(60.39, 5.32),
            RouteOptions(RoadStyle.CURVY, SurfacePreference.GRAVEL, avoidFerries = true, avoidToll = true, gravelReachKm = 20, gravelPull = 60),
            stops = listOf(Stop("Dale", LatLon(60.58, 5.82)), Stop("Near Vaksdal", LatLon(60.48, 5.73), via = true)),
            start = Stop("Voss", LatLon(60.63, 6.42)),
            saved = SavedRef(7, "Voss – Bergen"),
            loop = loop
        )
        draft.save(goal to 2)
        assertEquals(goal to 2, draft.load())
    }

    @Test
    fun adventureAndTrackComeBack() {
        val area = listOf(LatLon(60.0, 10.0), LatLon(60.5, 10.0), LatLon(60.5, 11.0))
        val adventure = NavigationGoal.Adventure("Gjøvik", LatLon(60.8, 10.7), area, 180, RouteOptions(), variant = 1)
        draft.save(adventure to 0)
        assertEquals(adventure to 0, draft.load())
        // No time limit is no time, not zero.
        val unlimited = adventure.copy(minutes = null)
        draft.save(unlimited to 0)
        assertEquals(unlimited to 0, draft.load())

        val track = NavigationGoal.Track("Valdres", listOf(LatLon(61.0, 9.0), LatLon(61.1, 9.2)), RouteOptions(RoadStyle.FASTEST))
        draft.save(track to 0)
        assertEquals(track to 0, draft.load())
    }

    @Test
    fun goneOnceThePreviewCloses() {
        draft.save(NavigationGoal.Track("Valdres", listOf(LatLon(61.0, 9.0), LatLon(61.1, 9.2)), RouteOptions()) to 0)
        draft.save(null)
        assertNull(draft.load())
    }
}
