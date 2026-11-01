package com.curveon.data.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.curveon.protocol.WeatherForecast
import com.curveon.routing.LatLon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class WeatherServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun forecast(now: Long) = WeatherForecast(59.0, 5.0, now + 3_600_000L, listOf(
        com.curveon.protocol.WeatherHour(now, 10.0, 0.0, 0.0, 2.0, 3.0, "clearsky_day")
    ))

    /**
     * Moving the map cancels what was being loaded for the old view. The next view wants many of the same forecasts: it
     * must get them, not die with the cancelled one (that left old symbols on the map until it was moved again).
     */
    @Test fun aCancelledAskDoesNotTakeTheNextOneDownWithIt() = runBlocking {
        val now = System.currentTimeMillis()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val service = WeatherService(context) { _, _ -> fetches.incrementAndGet(); started.complete(Unit); release.await(); forecast(now) }
        val points = listOf(WeatherPoint(LatLon(61.23, 7.31), now, 0.0))

        val first = launch(Dispatchers.Default) { service.hours(points, now) }
        withTimeout(5_000) { started.await() }
        val second = async(Dispatchers.Default) { runCatching { service.hours(points, now) } }
        delay(300) // it now waits for the same fetch
        first.cancelAndJoin()
        release.complete(Unit)

        assertNotNull(withTimeout(5_000) { second.await() }.getOrThrow().first())
        assertEquals("one fetch, shared", 1, fetches.get())
    }

    /** A forecast already on its way is finished and kept even if nobody waits any more: panning back needs no new fetch. */
    @Test fun aForecastOnItsWayIsKeptAfterTheAskIsCancelled() = runBlocking {
        val now = System.currentTimeMillis()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val service = WeatherService(context) { _, _ -> fetches.incrementAndGet(); started.complete(Unit); release.await(); forecast(now) }
        val points = listOf(WeatherPoint(LatLon(62.23, 8.31), now, 0.0))

        val ask = launch(Dispatchers.Default) { service.hours(points, now) }
        withTimeout(5_000) { started.await() }
        ask.cancelAndJoin()
        release.complete(Unit)

        assertNotNull(withTimeout(5_000) { service.hours(points, now) }.first())
        assertEquals(1, fetches.get())
    }
}
