package com.curveon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.curveon.data.model.GpxGroup
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

    @Update
    suspend fun updateTracks(tracks: List<GpxTrack>)

    @Query("SELECT * FROM gpx_tracks WHERE groupId = :groupId")
    suspend fun getTracksInGroup(groupId: Long): List<GpxTrack>

    @Query("SELECT * FROM gpx_groups ORDER BY name COLLATE NOCASE")
    fun getAllGroups(): Flow<List<GpxGroup>>

    @Query("SELECT * FROM gpx_groups WHERE id = :id")
    suspend fun getGroupById(id: Long): GpxGroup?

    @Query("SELECT * FROM gpx_groups WHERE name = :name LIMIT 1")
    suspend fun getGroupByName(name: String): GpxGroup?

    @Insert
    suspend fun insertGroup(group: GpxGroup): Long

    @Update
    suspend fun updateGroup(group: GpxGroup)

    @Query("DELETE FROM gpx_groups WHERE id = :id")
    suspend fun deleteGroupById(id: Long)

    @Query("DELETE FROM gpx_tracks WHERE groupId = :groupId")
    suspend fun deleteTracksInGroup(groupId: Long)

    @Query("UPDATE gpx_tracks SET groupId = NULL WHERE groupId = :groupId")
    suspend fun ungroupTracks(groupId: Long)

    @Query("SELECT COUNT(*) FROM gpx_tracks")
    suspend fun getTrackCount(): Int
}
