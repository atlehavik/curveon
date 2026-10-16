package com.curveon.data.routing

import com.curveon.data.search.PlaceSearch
import com.curveon.data.speed.SpeedLimits
import com.curveon.routing.GiveWay
import com.curveon.routing.GiveWayOnRoute
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.Route
import com.curveon.routing.RouteCorridor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Something on or near the route, [along] metres from the route start. */
data class AlongPoi(val poi: PlaceSearch.Poi, val along: Double)

data class FuelHint(val name: String, val point: LatLon, val distanceMeters: Double, val isLongGap: Boolean)

data class CameraAlert(val distanceMeters: Double, val maxSpeedKmh: Int?)

/**
 * A junction ahead where the rider gives way (or stops, [stop]), [distanceMeters] away, at [lat],
 * [lon], approached heading [bearingDeg].
 */
data class GiveWayAlert(val distanceMeters: Double, val stop: Boolean, val lat: Double = 0.0, val lon: Double = 0.0, val bearingDeg: Double = 0.0)

/**
 * Fuel stations, speed cameras and give way signs for the active route, and cameras ahead when free
 * riding.
 */
class RouteHazards(private val search: PlaceSearch, private val speedLimits: SpeedLimits) {

    var fuel: List<AlongPoi> = emptyList()
        private set
    var cameras: List<AlongPoi> = emptyList()
        private set
    var giveWays: List<GiveWayOnRoute> = emptyList()
        private set

    /** False while a new route's fuel/cameras are loading, so we don't claim "no fuel ahead". */
    @Volatile private var ready = false

    private var nearbyCameras: List<PlaceSearch.Poi> = emptyList()
    private var nearbyCenter: LatLon? = null

    suspend fun load(route: Route?) = withContext(Dispatchers.Default) {
        ready = false
        if (route == null) {
            fuel = emptyList(); cameras = emptyList(); giveWays = emptyList(); return@withContext
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
        giveWays = giveWaysOn(route)
        ready = true
    }

    /** Next fuel on the route; flagged when the gap after it (or to it) is long for a motorcycle tank. */
    fun nextFuel(along: Double, remaining: Double): FuelHint? {
        if (!ready) return null
        val next = fuel.firstOrNull { it.along > along + 50 }
        val distance = next?.let { it.along - along } ?: return if (remaining > LONG_GAP_M) {
            FuelHint("",  LatLon(0.0, 0.0), remaining, isLongGap = true)
        } else null
        return FuelHint(next.poi.name, LatLon(next.poi.lat, next.poi.lon), distance, distance > LONG_GAP_M)
    }

    fun cameraAhead(along: Double): CameraAlert? =
        cameras.firstOrNull { it.along > along - 20 && it.along - along < CAMERA_WARN_M }
            ?.let { CameraAlert((it.along - along).coerceAtLeast(0.0), it.poi.maxSpeed) }

    /** The next junction where the rider gives way, from a little before it until it is reached. */
    fun giveWayAhead(along: Double): GiveWayAlert? =
        giveWaysAhead(along, GIVE_WAY_WARN_M).firstOrNull()

    /** The junctions where the rider gives way in the next [withinM] metres of the route, for signs on the map. */
    fun giveWaysAhead(along: Double, withinM: Double): List<GiveWayAlert> =
        giveWays.filter { it.along > along - GIVE_WAY_PAST_M && it.along - along < withinM }.map { it.alert(along) }

    /**
     * Not navigating: the first junction where the rider gives way along [probe] (the next stretch
     * of the road they are on, starting where they are), if it is close.
     */
    suspend fun giveWayFreeRide(probe: Route): GiveWayAlert? = withContext(Dispatchers.Default) {
        giveWaysOn(probe).firstOrNull { it.along < GIVE_WAY_FREE_M }?.alert(0.0)
    }

    private fun giveWaysOn(route: Route): List<GiveWayOnRoute> = runCatching {
        GiveWay.alongRoute(route, speedLimits.giveWaySigns(route.points.map { LatLon(it.lat, it.lon) }), speedLimits::priorityRoadNear)
    }.getOrDefault(emptyList())

    private fun GiveWayOnRoute.alert(from: Double) = GiveWayAlert((along - from).coerceAtLeast(0.0), stop, lat, lon, bearingDeg)

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
        private const val GIVE_WAY_WARN_M = 200.0
        /** Not navigating, only what is close: further on, where the rider goes is a guess. */
        private const val GIVE_WAY_FREE_M = 120.0
        /** A sign stands a little before the junction: keep showing it until the rider is there. */
        private const val GIVE_WAY_PAST_M = 10.0
        /** Many bikes have 250–350 km range; warn when the next pump is more than 120 km away. */
        const val LONG_GAP_M = 120_000.0
    }
}
