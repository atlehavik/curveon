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

    suspend fun saveRoute(route: RouteItem): Long = routeDao.insertRoute(route)

    suspend fun deleteRoute(id: Long) = routeDao.deleteRouteById(id)

    suspend fun saveGpxTrack(track: GpxTrack): Long = gpxDao.insertTrack(track)

    suspend fun deleteGpxTrack(id: Long) = gpxDao.deleteTrackById(id)

    suspend fun updateGpxTrack(track: GpxTrack) = gpxDao.updateTrack(track)

    suspend fun findGpxTrack(name: String): GpxTrack? = gpxDao.getTrackByName(name)
}
