package com.curveon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.curveon.data.model.RouteItem
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Query("SELECT * FROM saved_routes ORDER BY timestamp DESC")
    fun getAllRoutes(): Flow<List<RouteItem>>

    @Query("SELECT * FROM saved_routes WHERE id = :id")
    suspend fun getRouteById(id: Long): RouteItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: RouteItem): Long

    @Update
    suspend fun updateRoute(route: RouteItem)

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteRouteById(id: Long)

    @Query("SELECT COUNT(*) FROM saved_routes")
    suspend fun getRouteCount(): Int
}
