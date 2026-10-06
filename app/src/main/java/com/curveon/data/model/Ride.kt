package com.curveon.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One recorded ride. The track is stored compactly; see RideRecorder for the format. */
@Entity(tableName = "rides")
data class Ride(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val startMs: Long,
    val endMs: Long,
    val distanceMeters: Double = 0.0,
    val movingMillis: Long = 0,
    val maxSpeedKmh: Float = 0f,
    val ascentMeters: Double = 0.0,
    val maxLeanLeftDeg: Float = 0f,
    val maxLeanRightDeg: Float = 0f,
    /** "lat,lon,ele,secondsFromStart;…" */
    val track: String = ""
) {
    val averageSpeedKmh: Float
        get() = if (movingMillis > 0) (distanceMeters / (movingMillis / 1000.0) * 3.6).toFloat() else 0f
}
