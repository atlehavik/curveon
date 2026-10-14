package com.curveon.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RoadTypePreference { FASTEST, CURVY, VERY_CURVY }

enum class GroundPreference { PAVED, MIXED, GRAVEL, OFFROAD }

/** Clockwise from north, in the order of R.string.compass_points. */
enum class CompassDirection(val angleDegrees: Float) {
    NORTH(0f),
    NORTH_EAST(45f),
    EAST(90f),
    SOUTH_EAST(135f),
    SOUTH(180f),
    SOUTH_WEST(225f),
    WEST(270f),
    NORTH_WEST(315f)
}

enum class TurnType {
    START,
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    DESTINATION
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0,
    /** When it was ridden (epoch ms), for recorded rides and GPX files with times; null otherwise. */
    val timeMillis: Long? = null
)

data class TurnInstruction(
    val index: Int,
    val type: TurnType,
    val text: String,
    val distanceMeters: Int,
    val streetName: String,
    val speedLimitKmh: Int = 80,
    val location: GeoPoint,
    /** The number sign of the road after the turn, when known. */
    val roadRef: com.curveon.data.road.RoadRef? = null
)

@Entity(tableName = "saved_routes")
data class RouteItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val subtitle: String = "",
    val totalDistanceKm: Float,
    val estimatedMinutes: Int,
    val roadType: String = RoadTypePreference.CURVY.name,
    val groundType: String = GroundPreference.PAVED.name,
    val curvinessScore: Int = 85, // 0 - 100
    val elevationGainMeters: Int = 420,
    val isRoundtrip: Boolean = false,
    val polylineJson: String = "", // serialized points
    val waypointsJson: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    /** The group of planned trips this one is kept in ("Trips for the weekend"); null = none. */
    val groupId: Long? = null,
    /** Offered in Where to? (saved routes), and/or kept among the planned trips: one saved route can be both. */
    @ColumnInfo(defaultValue = "1") val inWhereTo: Boolean = true,
    @ColumnInfo(defaultValue = "0") val inPlanned: Boolean = false
)

/** A named set of planned trips, e.g. "Trips for the weekend". */
@Entity(tableName = "route_groups")
data class RouteGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)

data class RoundtripConfig(
    val radiusKm: Int = 75, // approximate distance
    val selectedDirections: Set<CompassDirection> = setOf(CompassDirection.NORTH, CompassDirection.EAST),
    val roadType: RoadTypePreference = RoadTypePreference.CURVY,
    val groundPreference: GroundPreference = GroundPreference.PAVED,
    val avoidHighways: Boolean = true,
    val avoidTolls: Boolean = true,
    val avoidFerries: Boolean = false
)
