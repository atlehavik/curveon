package com.curveon.data.routing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.curveon.R
import com.curveon.routing.LatLon
import com.curveon.routing.RouteOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigatorGpsStatusTest {

    @Test
    fun `waiting for gps status clears automatically when a gps fix arrives`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nav = Navigator(context, CoroutineScope(SupervisorJob()))

        nav.previewDestination("Bergen", LatLon(60.3913, 5.3221), RouteOptions())
        val failed = nav.state.value.status as RoutingStatus.Failed
        assertEquals(context.getString(R.string.nav_waiting_gps), failed.message)
        assertTrue(failed.waitingForGps)

        nav.onLocation(LatLon(59.8161, 5.2687), 8.0)
        assertEquals(RoutingStatus.Idle, nav.state.value.status)
    }

    @Test
    fun `other routing errors stay visible when gps fixes keep arriving`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nav = Navigator(context, CoroutineScope(SupervisorJob()))

        nav.onLocation(LatLon(59.8161, 5.2687), 8.0)
        nav.previewDestination("Bergen", LatLon(60.3913, 5.3221), RouteOptions())
        val failed = nav.state.value.status as RoutingStatus.Failed
        assertFalse(failed.waitingForGps)

        nav.onLocation(LatLon(59.8162, 5.2688), 8.0)
        assertEquals(failed, nav.state.value.status)
    }
}
