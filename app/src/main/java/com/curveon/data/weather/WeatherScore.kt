package com.curveon.data.weather

import com.curveon.protocol.WeatherHour
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.Route
import kotlin.math.PI

/** A place on a trip and when the rider is there. */
data class WeatherPoint(val point: LatLon, val time: Long, val metersFromStart: Double)

/** What the forecast says at a [WeatherPoint]. */
data class WeatherSpot(val at: WeatherPoint, val hour: WeatherHour) {
    val wet get() = WeatherScore.wet(hour)
    val windy get() = WeatherScore.windy(hour)
}

/** The weather along a trip, at the times the rider is where. */
data class TripWeather(val spots: List<WeatherSpot>, val asked: Int) {
    /** Enough of the trip has a forecast to say something (the rest is too far ahead, or could not be fetched). */
    val known get() = asked > 0 && spots.size * 2 >= asked
    /** Where the rain starts, if it does. */
    val firstWet get() = spots.firstOrNull { it.wet }
    val firstWindy get() = spots.firstOrNull { it.windy }
    val wetShare get() = if (spots.isEmpty()) 0.0 else spots.count { it.wet }.toDouble() / spots.size
    val dry get() = known && spots.none { it.wet }
    val minTemp get() = spots.minOfOrNull { it.hour.tempC }
    val maxTemp get() = spots.maxOfOrNull { it.hour.tempC }
    /** Lower is better: see [WeatherScore.badness]. One heavy shower counts more than a lot of light drizzle. */
    val score get() = if (spots.isEmpty()) Double.MAX_VALUE else
        spots.sumOf { WeatherScore.badness(it.hour) } / spots.size + 0.5 * spots.maxOf { WeatherScore.badness(it.hour) }
}

/** A direction to ride a loop in, and the weather on it. */
data class DirectionWeather(val directionDeg: Double, val weather: TripWeather)

/**
 * How good the weather is for riding, from MET's hours: rain counts most, then strong gusts and cold. Kept simple and
 * plain so the sheet can say why in a few words.
 */
object WeatherScore {
    /** Rain a rider notices: more than a few drops, and not only a slight chance of it. */
    fun wet(h: WeatherHour) = h.precipMm >= WET_MM && (h.precipChance ?: 100.0) >= 40.0

    /** Gusts that push a bike around. */
    fun windy(h: WeatherHour) = (h.gustMs ?: (h.windMs * 1.5)) >= WINDY_GUST_MS

    /** 0 = fine riding weather; rain up to 10, gusts up to 4, cold up to 3. */
    fun badness(h: WeatherHour): Double {
        val likely = (h.precipChance ?: 100.0) / 100.0
        val rain = if (h.precipMm < 0.1) 0.0 else (h.precipMm * likely).coerceAtMost(4.0) / 4.0 * 10.0 + if (wet(h)) 2.0 else 0.0
        val gust = h.gustMs ?: (h.windMs * 1.5)
        val wind = ((gust - 12.0) / 8.0 * 4.0).coerceIn(0.0, 4.0)
        val cold = ((8.0 - h.tempC) / 8.0 * 3.0).coerceIn(0.0, 3.0)
        return rain + wind + cold
    }

    /** About every 15 km or ten minutes along [route] (whichever comes first), and its end, leaving at [departure]. */
    fun along(route: Route, departure: Long): List<WeatherPoint> {
        val out = mutableListOf<WeatherPoint>()
        var nextM = 0.0
        var nextS = 0.0
        for (p in route.points) {
            if (p.distanceFromStart >= nextM || p.secondsFromStart >= nextS) {
                out += WeatherPoint(LatLon(p.lat, p.lon), departure + (p.secondsFromStart * 1000).toLong(), p.distanceFromStart)
                nextM = p.distanceFromStart + STEP_M
                nextS = p.secondsFromStart + STEP_S
            }
        }
        route.points.lastOrNull()?.let { p ->
            if (out.lastOrNull()?.metersFromStart != p.distanceFromStart)
                out += WeatherPoint(LatLon(p.lat, p.lon), departure + (p.secondsFromStart * 1000).toLong(), p.distanceFromStart)
        }
        return out
    }

    /**
     * The loop a roundtrip of [km] in [directionDeg] would roughly ride, like [com.curveon.routing.RoundtripPlanner]
     * lays it: a circle that touches [start] and lies in that direction, ridden in [hours]. [n] points on it, with times.
     */
    fun loop(start: LatLon, km: Double, directionDeg: Double, hours: Double, departure: Long, n: Int = 6): List<WeatherPoint> {
        val radiusM = km * 1000 / (2 * PI * ROAD_DETOUR)
        val center = Geo.destination(start, directionDeg, radiusM)
        val back = directionDeg + 180.0
        return (1..n).map { i ->
            val f = i.toDouble() / (n + 1)
            // Round the circle from the start (which is at "back" seen from the centre), clockwise.
            val at = Geo.destination(center, back + f * 360.0, radiusM)
            WeatherPoint(at, departure + (f * hours * 3_600_000).toLong(), f * km * 1000)
        }
    }

    /** The eight compass directions, best weather first. */
    val DIRECTIONS = (0 until 8).map { it * 45.0 }

    /** The forecast's hours at [points] (null where there is none); a [TripWeather] of what was found. */
    fun trip(points: List<WeatherPoint>, hours: List<WeatherHour?>): TripWeather =
        TripWeather(points.zip(hours).mapNotNull { (p, h) -> h?.let { WeatherSpot(p, it) } }, points.size)

    private const val WET_MM = 0.3
    private const val WINDY_GUST_MS = 15.0
    private const val STEP_M = 15_000.0
    private const val STEP_S = 600.0
    /** Roads wind about this much more than the circle (as in RoundtripPlanner). */
    private const val ROAD_DETOUR = 1.3
}
