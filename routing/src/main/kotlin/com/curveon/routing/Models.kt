package com.curveon.routing

data class LatLon(val lat: Double, val lon: Double)

enum class RoadStyle { FASTEST, CURVY, VERY_CURVY }

enum class SurfacePreference { PAVED, MIXED, GRAVEL }

data class RouteOptions(
    val roadStyle: RoadStyle = RoadStyle.CURVY,
    val surface: SurfacePreference = SurfacePreference.PAVED,
    val avoidFerries: Boolean = false,
    val avoidMotorways: Boolean = false,
    val avoidToll: Boolean = false
)

enum class ManeuverType {
    DEPART, STRAIGHT,
    SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    KEEP_LEFT, KEEP_RIGHT,
    EXIT_LEFT, EXIT_RIGHT,
    U_TURN, ROUNDABOUT, ARRIVE
}

/** A point on the route with cumulative distance/time and the properties of the road leading to it. */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double,
    val distanceFromStart: Double,
    val secondsFromStart: Double,
    val highway: String?,
    val maxSpeedKmh: Int?,
    val unpaved: Boolean,
    val ferry: Boolean
)

data class Maneuver(
    val type: ManeuverType,
    /** Index into [Route.points] where the maneuver happens. */
    val pointIndex: Int,
    val distanceFromStart: Double,
    /** Roundabout exit number, 0 when not a roundabout. */
    val exitNumber: Int = 0
)

data class Route(
    val points: List<RoutePoint>,
    val maneuvers: List<Maneuver>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val ascentMeters: Double,
    /** 0–100, how twisty the route is (see [Curviness]). */
    val curviness: Int,
    val unpavedMeters: Double,
    val ferryCount: Int
)

open class RoutingException(message: String) : Exception(message)

/** No loop could be routed in the asked direction; the app tells the rider in their language. */
class NoRoundtripException(message: String) : RoutingException(message)
