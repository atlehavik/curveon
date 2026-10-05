package com.vibenav.data.routing

import com.vibenav.data.search.PlaceSearch
import com.vibenav.routing.Geo
import com.vibenav.routing.LatLon
import com.vibenav.routing.Route
import com.vibenav.routing.RouteCorridor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Something on or near the route, [along] metres from the route start. */
data class AlongPoi(val poi: PlaceSearch.Poi, val along: Double)

data class FuelHint(val name: String, val point: LatLon, val distanceMeters: Double, val isLongGap: Boolean)

data class CameraAlert(val distanceMeters: Double, val maxSpeedKmh: Int?)

/** Fuel stations and speed cameras for the active route, and cameras ahead when free riding. */
class RouteHazards(private val search: PlaceSearch) {

    var fuel: List<AlongPoi> = emptyList()
        private set
    var cameras: List<AlongPoi> = emptyList()
        private set

    private var nearbyCameras: List<PlaceSearch.Poi> = emptyList()
    private var nearbyCenter: LatLon? = null

    suspend fun load(route: Route?) = withContext(Dispatchers.Default) {
        if (route == null) {
            fuel = emptyList(); cameras = emptyList(); return@withContext
        }
        val corridor = RouteCorridor(route)
        val pad = 0.02
        val box = arrayOf(corridor.minLat - pad, corridor.maxLat + pad, corridor.minLon - pad * 2, corridor.maxLon + pad * 2)
        fuel = search.fuelInBox(box[0], box[1], box[2], box[3])
            .mapNotNull { p -> corridor.alongDistance(p.lat, p.lon, FUEL_MAX_OFFSET_M)?.let { AlongPoi(p, it) } }
            .sortedBy { it.along }
        cameras = search.camerasInBox(box[0], box[1], box[2], box[3])
            .mapNotNull { p -> corridor.alongDistance(p.lat, p.lon, CAMERA_MAX_OFFSET_M)?.let { AlongPoi(p, it) } }
            .sortedBy { it.along }
    }

    /** Next fuel on the route; flagged when the gap after it (or to it) is long for a motorcycle tank. */
    fun nextFuel(along: Double, remaining: Double): FuelHint? {
        val next = fuel.firstOrNull { it.along > along + 50 }
        val distance = next?.let { it.along - along } ?: return if (remaining > LONG_GAP_M) {
            FuelHint("No fuel on the rest of the route", LatLon(0.0, 0.0), remaining, isLongGap = true)
        } else null
        return FuelHint(next.poi.name, LatLon(next.poi.lat, next.poi.lon), distance, distance > LONG_GAP_M)
    }

    fun cameraAhead(along: Double): CameraAlert? =
        cameras.firstOrNull { it.along > along - 20 && it.along - along < CAMERA_WARN_M }
            ?.let { CameraAlert((it.along - along).coerceAtLeast(0.0), it.poi.maxSpeed) }

    /** Free riding: a camera within warning distance roughly straight ahead. */
    suspend fun cameraAheadFreeRide(position: LatLon, headingDeg: Double): CameraAlert? {
        val center = nearbyCenter
        if (center == null || Geo.distance(center, position) > 2_000) {
            nearbyCameras = search.camerasInBox(position.lat - 0.04, position.lat + 0.04, position.lon - 0.08, position.lon + 0.08)
            nearbyCenter = position
        }
        return nearbyCameras.mapNotNull { c ->
            val d = Geo.distance(position.lat, position.lon, c.lat, c.lon)
            val bearing = Geo.bearing(position.lat, position.lon, c.lat, c.lon)
            if (d < CAMERA_WARN_M && abs(Geo.angleDiff(headingDeg, bearing)) < 30) CameraAlert(d, c.maxSpeed) else null
        }.minByOrNull { it.distanceMeters }
    }

    companion object {
        private const val FUEL_MAX_OFFSET_M = 400.0
        private const val CAMERA_MAX_OFFSET_M = 40.0
        private const val CAMERA_WARN_M = 600.0
        /** Many bikes have 250–350 km range; warn when the next pump is more than 120 km away. */
        const val LONG_GAP_M = 120_000.0
    }
}
