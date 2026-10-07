package com.curveon.data.model

import androidx.room.ColumnInfo
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
    val importedAtMs: Long = System.currentTimeMillis(),
    /** Drawn as an overlay on the map. */
    @ColumnInfo(defaultValue = "0") val showOnMap: Boolean = false,
    /** Overlay colour, ARGB. 0 = not chosen yet (a palette colour is used). */
    @ColumnInfo(defaultValue = "0") val color: Int = 0,
    /** Show the file's waypoints (campsites, fuel, …) along this track. */
    @ColumnInfo(defaultValue = "1") val showPois: Boolean = true,
    /** The group it belongs to, or null for a loose track. */
    val groupId: Long? = null,
    /** Ride (and draw the arrows) against the recorded direction. */
    @ColumnInfo(defaultValue = "0") val reversed: Boolean = false
)
