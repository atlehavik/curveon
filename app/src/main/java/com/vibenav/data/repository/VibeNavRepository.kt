package com.vibenav.data.repository

import com.vibenav.data.db.GpxDao
import com.vibenav.data.db.RouteDao
import com.vibenav.data.model.*
import kotlinx.coroutines.flow.Flow

class VibeNavRepository(
    private val routeDao: RouteDao,
    private val gpxDao: GpxDao
) {
    val allRoutes: Flow<List<RouteItem>> = routeDao.getAllRoutes()
    val allGpxTracks: Flow<List<GpxTrack>> = gpxDao.getAllTracks()

    suspend fun saveRoute(route: RouteItem): Long = routeDao.insertRoute(route)

    suspend fun deleteRoute(id: Long) = routeDao.deleteRouteById(id)

    suspend fun saveGpxTrack(track: GpxTrack): Long = gpxDao.insertTrack(track)

    suspend fun deleteGpxTrack(id: Long) = gpxDao.deleteTrackById(id)
}
