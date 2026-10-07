package com.curveon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.curveon.data.model.Ride
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startMs DESC")
    fun all(): Flow<List<Ride>>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun get(id: Long): Ride?

    @Insert
    suspend fun insert(ride: Ride): Long

    @Update
    suspend fun update(ride: Ride)

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rides WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
