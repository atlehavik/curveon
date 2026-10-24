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
    val allRouteGroups: Flow<List<RouteGroup>> = routeDao.getAllGroups()
    val allGpxTracks: Flow<List<GpxTrack>> = gpxDao.getAllTracks()
    val allGpxGroups: Flow<List<GpxGroup>> = gpxDao.getAllGroups()

    suspend fun saveRoute(route: RouteItem): Long = routeDao.insertRoute(route)

    suspend fun route(id: Long): RouteItem? = routeDao.getRouteById(id)

    suspend fun plannedNow(): List<RouteItem> = routeDao.getPlannedRoutes()

    suspend fun routeGroupsNow(): List<RouteGroup> = routeDao.getGroups()

    suspend fun deleteRoute(id: Long) = routeDao.deleteRouteById(id)

    suspend fun renameRoute(id: Long, title: String) = routeDao.renameRoute(id, title)

    suspend fun setRouteGroup(id: Long, groupId: Long?) = routeDao.setRouteGroup(id, groupId)

    suspend fun createRouteGroup(name: String): Long = routeDao.insertGroup(RouteGroup(name = name))

    suspend fun renameRouteGroup(id: Long, name: String) = routeDao.renameGroup(id, name)

    /** Deletes a group of planned trips; [keepTrips] leaves them as loose ones, else they leave the planned trips too. */
    suspend fun deleteRouteGroup(id: Long, keepTrips: Boolean) {
        if (keepTrips) routeDao.ungroupRoutes(id) else routeDao.getRoutesInGroup(id).forEach { removeFromPlanned(it) }
        routeDao.deleteGroupById(id)
    }

    /** Takes a saved route out of Where to?; gone altogether when it isn't among the planned trips either. */
    suspend fun removeFromWhereTo(route: RouteItem) {
        if (route.inPlanned) routeDao.updateRoute(route.copy(inWhereTo = false)) else routeDao.deleteRouteById(route.id)
    }

    /** Takes a saved route out of the planned trips (and their groups); gone altogether when it isn't in Where to? either. */
    suspend fun removeFromPlanned(route: RouteItem) {
        if (route.inWhereTo) routeDao.updateRoute(route.copy(inPlanned = false, groupId = null)) else routeDao.deleteRouteById(route.id)
    }

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
