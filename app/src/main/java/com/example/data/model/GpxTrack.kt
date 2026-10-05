package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gpx_tracks")
data class GpxTrack(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val filename: String = "",
    val totalDistanceKm: Float,
    val elevationGainMeters: Int,
    val maxElevationMeters: Int,
    val pointCount: Int,
    val trackPointsJson: String,
    val waypointsJson: String = "",
    val isFavorite: Boolean = false,
    val category: String = "Curvy Pass", // e.g. "Alpine Pass", "TET Section", "Coastline"
    val importedAtMs: Long = System.currentTimeMillis()
)
