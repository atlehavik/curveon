package com.vibenav.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RoadTypePreference(val label: String, val description: String) {
    FASTEST("Fastest", "Direct highway and main corridors"),
    CURVY("Curvy", "Scenic mountain passes & winding secondary roads"),
    VERY_CURVY("Super Curvy", "Maximum twisties, hairpin turns & scenic backroads")
}

enum class GroundPreference(val label: String, val icon: String) {
    PAVED("Tarmac Only", "Smooth asphalt and paved surfaces"),
    MIXED("Mixed Roads", "Tarmac with occasional light trails"),
    GRAVEL("Gravel Adventure", "Unpaved forest tracks & ADV gravel sections")
}

enum class CompassDirection(val code: String, val label: String, val angleDegrees: Float) {
    NORTH("N", "North", 0f),
    NORTH_EAST("NE", "North-East", 45f),
    EAST("E", "East", 90f),
    SOUTH_EAST("SE", "South-East", 135f),
    SOUTH("S", "South", 180f),
    SOUTH_WEST("SW", "South-West", 225f),
    WEST("W", "West", 270f),
    NORTH_WEST("NW", "North-West", 315f)
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
    val altitudeMeters: Double = 0.0
)

data class TurnInstruction(
    val index: Int,
    val type: TurnType,
    val text: String,
    val distanceMeters: Int,
    val streetName: String,
    val speedLimitKmh: Int = 80,
    val location: GeoPoint
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
    val timestamp: Long = System.currentTimeMillis()
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
