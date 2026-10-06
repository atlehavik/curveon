package com.curveon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.curveon.data.model.GpxTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface GpxDao {
    @Query("SELECT * FROM gpx_tracks ORDER BY name COLLATE NOCASE")
    fun getAllTracks(): Flow<List<GpxTrack>>

    @Query("SELECT * FROM gpx_tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): GpxTrack?

    @Query("SELECT * FROM gpx_tracks WHERE name = :name LIMIT 1")
    suspend fun getTrackByName(name: String): GpxTrack?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: GpxTrack): Long

    @Update
    suspend fun updateTrack(track: GpxTrack)

    @Query("DELETE FROM gpx_tracks WHERE id = :id")
    suspend fun deleteTrackById(id: Long)

    @Query("SELECT COUNT(*) FROM gpx_tracks")
    suspend fun getTrackCount(): Int
}
