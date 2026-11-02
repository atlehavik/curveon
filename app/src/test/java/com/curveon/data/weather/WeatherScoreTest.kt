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
    private val land = java.io.File("src/main/assets/land-mask.dat").inputStream().let { LandMask.read(it)!! }

    @Test fun samePlacesWhenTheMapMovesALittle() {
        val a = WeatherController.mapGrid(59.0, 61.0, 4.0, 8.0).places.toSet()
        val b = WeatherController.mapGrid(59.02, 61.02, 4.03, 8.03).places.toSet()
        assertTrue((a intersect b).size >= a.size * 0.8)
    }

    @Test fun coversTheWholeViewAlsoUpright() {
        // A phone held upright: tall and narrow (southern Norway).
        val south = 57.8; val north = 62.2; val west = 4.5; val east = 9.5
        val g = WeatherController.mapGrid(south, north, west, east).places
        val lats = g.map { it.lat }.filter { it in south..north }
        assertTrue("top ${lats.max()}", lats.max() > north - (north - south) / 4)
        assertTrue("bottom ${lats.min()}", lats.min() < south + (north - south) / 4)
        assertTrue(g.size <= 120)
    }

    @Test fun wideAndZoomedOutStaysWithinTheLimit() {
        assertTrue(WeatherController.mapGrid(55.0, 65.0, -5.0, 30.0).places.size <= 120)
        // Zoomed in closer than one cell: still one in view.
        val g = WeatherController.mapGrid(59.7701, 59.7801, 5.2001, 5.2201).places
        assertTrue(g.any { it.lat in 59.7701..59.7801 && it.lon in 5.2001..5.2201 })
    }

    @Test fun equallyFullAtEveryZoomUprightAndSideways() {
        // A phone's view at 60 north, from a few counties to half of Scandinavia; twice the degrees east-west is about square.
        val counts = listOf(1.5, 1.8, 2.2, 2.7, 3.2, 3.9, 4.7, 5.6, 6.8, 8.0).flatMap { tall ->
            val wide = tall / 1.9 * 2
            listOf(tall to wide, tall / 1.9 to tall * 2).map { (dLat, dLon) ->
                val south = 60.0 - dLat / 2; val north = 60.0 + dLat / 2; val west = 12.0 - dLon / 2; val east = 12.0 + dLon / 2
                WeatherController.mapGrid(south, north, west, east).places.count { it.lat in south..north && it.lon in west..east }
            }
        }
        assertTrue("$counts", counts.all { it in 15..30 })
    }

    @Test fun notInRowsAndColumns() {
        val g = WeatherController.mapGrid(59.0, 61.0, 6.0, 10.0)
        // On a grid these would be a handful of different values, one per row or column.
        assertTrue(g.places.map { it.lat }.distinct().size > g.places.size * 0.9)
        assertTrue(g.places.map { it.lon }.distinct().size > g.places.size * 0.9)
    }

    @Test fun zoomingOutKeepsAPartOfWhatWasThere() {
        // Each zoomed out to twice the size: of the symbols then shown where the closer view looked, most were there already.
        var kept = 0; var all = 0
        for (lat in listOf(50.0, 54.0, 59.0, 62.0, 66.0)) for (lon in listOf(6.0, 10.0, 14.0, 18.0)) {
            val close = WeatherController.mapGrid(lat - 1, lat + 1, lon - 2, lon + 2).places.toSet()
            val far = WeatherController.mapGrid(lat - 2, lat + 2, lon - 4, lon + 4).places.filter { it.lat in (lat - 1)..(lat + 1) && it.lon in (lon - 2)..(lon + 2) }
            kept += far.count { it in close }; all += far.size
        }
        assertTrue("$kept of $all", kept >= all * 0.7)
    }

    @Test fun noneOnTheOpenSeaButPlentyOnLand() {
        // Southern Norway zoomed well out: most of the view west and south of the coast is sea, in no routing data at all.
        val g = WeatherController.mapGrid(56.0, 63.0, -2.0, 12.0, land).places
        assertTrue(g.all { land.isLand(it.lat, it.lon) })
        assertTrue(g.none { it.lon in 0.5..4.0 && it.lat in 56.0..59.5 })
        assertTrue("${g.size} on land", g.size >= 12)
    }

    @Test fun theCoastKeepsItsWeatherWhenZoomedOut() {
        // A coarse cell mostly sea, with land in one corner, still has a place.
        val g = WeatherController.mapGrid(56.0, 72.0, -4.0, 32.0, land).places
        assertTrue(g.any { it.lon < 7.0 && it.lat in 58.5..62.5 })
    }

    @Test fun nothingAtAllInTheMiddleOfTheOcean() {
        assertTrue(WeatherController.mapGrid(40.0, 42.0, -40.0, -36.0, land).places.isEmpty())
    }

    @Test fun knowsLandFromSeaInEveryCountry() {
        listOf(59.91 to 10.75, 60.39 to 5.32, 59.28 to 5.28, 63.43 to 10.39, 59.33 to 18.07, 55.68 to 12.57, 52.5 to 13.4, 40.4 to -3.7)
            .forEach { (lat, lon) -> assertTrue("$lat $lon", land.isLand(lat, lon)) }
        listOf(57.9 to 8.5, 58.5 to 3.0, 65.0 to 5.0, 57.0 to 19.0, 45.5 to -4.0, 58.9 to 13.3)
            .forEach { (lat, lon) -> assertFalse("$lat $lon", land.isLand(lat, lon)) }
    }
}
