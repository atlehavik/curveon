package com.curveon.data.weather

import com.curveon.protocol.WeatherHour
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.Route
import com.curveon.routing.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherScoreTest {
    private fun hour(mm: Double = 0.0, chance: Double? = null, gust: Double? = null, temp: Double = 15.0) =
        WeatherHour(0L, temp, mm, chance, 3.0, gust, null)

    @Test fun rainCountsMostThenWindThenCold() {
        val fine = WeatherScore.badness(hour())
        val rain = WeatherScore.badness(hour(mm = 2.0, chance = 90.0))
        val wind = WeatherScore.badness(hour(gust = 18.0))
        val cold = WeatherScore.badness(hour(temp = 3.0))
        assertEquals(0.0, fine, 1e-9)
        assertTrue(rain > wind && wind > 0 && cold > 0 && rain > cold)
        assertFalse(WeatherScore.wet(hour(mm = 0.1)))          // a few drops
        assertFalse(WeatherScore.wet(hour(mm = 1.0, chance = 20.0))) // unlikely
        assertTrue(WeatherScore.wet(hour(mm = 1.0, chance = 70.0)))
    }

    @Test fun samplesAlongTheRouteWithTimes() {
        // 100 km due north in 100 minutes.
        val pts = (0..100).map { km -> RoutePoint(60.0 + km / 111.0, 10.0, 0.0, km * 1000.0, km * 60.0, null, null, false, false) }
        val route = Route(pts, emptyList(), 100_000.0, 6000.0, 0.0, 0, 0.0, 0)
        val along = WeatherScore.along(route, departure = 1_000L)
        assertEquals(0.0, along.first().metersFromStart, 1e-9)
        assertEquals(100_000.0, along.last().metersFromStart, 1e-9)
        assertTrue(along.zipWithNext().all { (a, b) -> b.metersFromStart - a.metersFromStart <= 15_000.0 })
        assertEquals(1_000L + 6_000_000L, along.last().time)
    }

    @Test fun loopLiesInItsDirection() {
        val start = LatLon(60.0, 10.0)
        val pts = WeatherScore.loop(start, km = 165.0, directionDeg = 90.0, hours = 3.0, departure = 0L)
        assertEquals(6, pts.size)
        assertTrue(pts.all { it.point.lon > start.lon }) // all east of the start
        assertTrue(pts.zipWithNext().all { (a, b) -> b.time > a.time })
        val far = pts.maxOf { Geo.distance(start, it.point) }
        assertTrue("far $far", far in 30_000.0..50_000.0) // about the circle's width
    }

    @Test fun tripNeedsMostOfItsForecast() {
        val p = (0 until 4).map { WeatherPoint(LatLon(60.0, 10.0), it * 1000L, it * 1000.0) }
        assertFalse(WeatherScore.trip(p, listOf(hour(), null, null, null)).known)
        val t = WeatherScore.trip(p, listOf(hour(), hour(mm = 2.0, chance = 90.0), hour(), null))
        assertTrue(t.known)
        assertEquals(1000.0, t.firstWet!!.at.metersFromStart, 1e-9)
        assertFalse(t.dry)
    }

    @Test fun cellsAreCoarseAndRounded() {
        val a = WeatherService.cellOf(60.3912, 5.3221)
        assertEquals(WeatherService.Cell(60.35, 5.3), a)
        assertEquals(a, WeatherService.cellOf(60.3001, 5.2001))
        assertEquals(WeatherService.Cell(-33.95, 18.5), WeatherService.cellOf(-33.92, 18.42))
    }
}

class WeatherMapGridTest {
    @Test fun samePlacesWhenTheMapMovesALittle() {
        val a = WeatherController.mapGrid(59.0, 61.0, 4.0, 8.0).toSet()
        val b = WeatherController.mapGrid(59.02, 61.02, 4.03, 8.03).toSet()
        assertTrue((a intersect b).size >= a.size * 0.8)
    }

    @Test fun coversTheWholeViewAlsoUpright() {
        // A phone held upright: tall and narrow (southern Norway).
        val south = 57.8; val north = 62.2; val west = 4.5; val east = 9.5
        val g = WeatherController.mapGrid(south, north, west, east)
        val lats = g.map { it.lat }.filter { it in south..north }
        assertTrue("top ${lats.max()}", lats.max() > north - (north - south) / 4)
        assertTrue("bottom ${lats.min()}", lats.min() < south + (north - south) / 4)
        assertTrue(g.size <= 120)
    }

    @Test fun wideAndZoomedOutStaysWithinTheLimit() {
        assertTrue(WeatherController.mapGrid(55.0, 65.0, -5.0, 30.0).size <= 120)
        // Zoomed in closer than one cell: still one in view.
        val g = WeatherController.mapGrid(59.7701, 59.7801, 5.2001, 5.2201)
        assertTrue(g.any { it.lat in 59.7701..59.7801 && it.lon in 5.2001..5.2201 })
    }
}
