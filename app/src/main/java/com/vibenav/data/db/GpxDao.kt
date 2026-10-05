package com.vibenav.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vibenav.data.model.GpxTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface GpxDao {
    @Query("SELECT * FROM gpx_tracks ORDER BY importedAtMs DESC")
    fun getAllTracks(): Flow<List<GpxTrack>>

    @Query("SELECT * FROM gpx_tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): GpxTrack?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: GpxTrack): Long

    @Update
    suspend fun updateTrack(track: GpxTrack)

    @Query("DELETE FROM gpx_tracks WHERE id = :id")
    suspend fun deleteTrackById(id: Long)

    @Query("SELECT COUNT(*) FROM gpx_tracks")
    suspend fun getTrackCount(): Int
}
