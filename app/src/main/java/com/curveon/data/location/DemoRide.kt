package com.curveon.data.location

import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fake GPS that rides along a route at the route's estimated speeds. For trying out navigation
 * at home and for testing; [speedFactor] > 1 fast-forwards.
 */
object DemoRide {

    /** [startAt]: begin at the route point nearest it instead of at the route's start (skips the way to a planned start). */
    fun fixes(route: Route, speedFactor: Double = 1.0, intervalMillis: Long = 1000L, startAt: LatLon? = null): Flow<GpsFix> = flow {
        val pts = route.points
        if (pts.size < 2) return@flow
        var i = 0
        if (startAt != null) {
            var best = Double.MAX_VALUE
            for (k in 0 until pts.lastIndex) {
                val d = Geo.distance(startAt, LatLon(pts[k].lat, pts[k].lon))
                if (d < best - 1.0) { best = d; i = k }
            }
        }
        var t = pts[i].secondsFromStart
        while (t <= route.durationSeconds) {
            while (i < pts.lastIndex - 1 && pts[i + 1].secondsFromStart < t) i++
            val a = pts[i]
            val b = pts[i + 1]
            val span = b.secondsFromStart - a.secondsFromStart
            val f = if (span > 0) ((t - a.secondsFromStart) / span).coerceIn(0.0, 1.0) else 0.0
            val segLen = b.distanceFromStart - a.distanceFromStart
            val speedMps = if (span > 0) segLen / span else 0.0
            emit(
                GpsFix(
                    latitude = a.lat + (b.lat - a.lat) * f,
                    longitude = a.lon + (b.lon - a.lon) * f,
                    altitudeMeters = a.elevation + (b.elevation - a.elevation) * f,
                    speedMps = (speedMps * speedFactor).toFloat(), // fast-forwarded: the map carries the rider forward at this speed
                    bearingDeg = Geo.bearing(a.lat, a.lon, b.lat, b.lon).toFloat(),
                    accuracyMeters = 4f,
                    timeMillis = System.currentTimeMillis()
                )
            )
            delay(intervalMillis)
            t += intervalMillis / 1000.0 * speedFactor
        }
    }
}
