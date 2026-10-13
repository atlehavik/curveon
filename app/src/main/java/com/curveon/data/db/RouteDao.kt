package com.curveon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.curveon.data.model.RouteGroup
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

    @Query("UPDATE saved_routes SET title = :title WHERE id = :id")
    suspend fun renameRoute(id: Long, title: String)

    @Query("UPDATE saved_routes SET groupId = :groupId WHERE id = :id")
    suspend fun setRouteGroup(id: Long, groupId: Long?)

    @Query("SELECT * FROM route_groups ORDER BY name COLLATE NOCASE")
    fun getAllGroups(): Flow<List<RouteGroup>>

    @Insert
    suspend fun insertGroup(group: RouteGroup): Long

    @Query("UPDATE route_groups SET name = :name WHERE id = :id")
    suspend fun renameGroup(id: Long, name: String)

    @Query("DELETE FROM route_groups WHERE id = :id")
    suspend fun deleteGroupById(id: Long)

    /** Takes a group's trips out of it (they stay as loose planned trips). */
    @Query("UPDATE saved_routes SET groupId = NULL WHERE groupId = :groupId")
    suspend fun ungroupRoutes(groupId: Long)

    @Query("DELETE FROM saved_routes WHERE groupId = :groupId")
    suspend fun deleteRoutesInGroup(groupId: Long)

    @Query("SELECT COUNT(*) FROM saved_routes")
    suspend fun getRouteCount(): Int
}
