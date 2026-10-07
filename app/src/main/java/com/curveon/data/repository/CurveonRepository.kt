package com.curveon.data.repository

import com.curveon.data.db.GpxDao
import com.curveon.data.db.RouteDao
import com.curveon.data.model.*
import kotlinx.coroutines.flow.Flow

class CurveonRepository(
    private val routeDao: RouteDao,
    private val gpxDao: GpxDao
) {
    val allRoutes: Flow<List<RouteItem>> = routeDao.getAllRoutes()
    val allGpxTracks: Flow<List<GpxTrack>> = gpxDao.getAllTracks()
    val allGpxGroups: Flow<List<GpxGroup>> = gpxDao.getAllGroups()

    suspend fun saveRoute(route: RouteItem): Long = routeDao.insertRoute(route)

    suspend fun deleteRoute(id: Long) = routeDao.deleteRouteById(id)

    suspend fun saveGpxTrack(track: GpxTrack): Long = gpxDao.insertTrack(track)

    suspend fun deleteGpxTrack(id: Long) = gpxDao.deleteTrackById(id)

    suspend fun updateGpxTrack(track: GpxTrack) = gpxDao.updateTrack(track)

    suspend fun findGpxTrack(name: String): GpxTrack? = gpxDao.getTrackByName(name)

    suspend fun updateGpxTracks(tracks: List<GpxTrack>) = gpxDao.updateTracks(tracks)

    suspend fun gpxTracksInGroup(groupId: Long): List<GpxTrack> = gpxDao.getTracksInGroup(groupId)

    suspend fun gpxGroup(id: Long): GpxGroup? = gpxDao.getGroupById(id)

    /** The group called [name], made if there is none. */
    suspend fun gpxGroupNamed(name: String): GpxGroup =
        gpxDao.getGroupByName(name) ?: GpxGroup(name = name).let { it.copy(id = gpxDao.insertGroup(it)) }

    suspend fun createGpxGroup(name: String): Long = gpxDao.insertGroup(GpxGroup(name = name))

    suspend fun updateGpxGroup(group: GpxGroup) = gpxDao.updateGroup(group)

    /** Deletes the group; its tracks go with it, or stay as loose tracks with [keepTracks]. */
    suspend fun deleteGpxGroup(id: Long, keepTracks: Boolean) {
        if (keepTracks) gpxDao.ungroupTracks(id) else gpxDao.deleteTracksInGroup(id)
        gpxDao.deleteGroupById(id)
    }
}
