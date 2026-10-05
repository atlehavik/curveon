package com.example.data.repository

import com.example.data.db.GpxDao
import com.example.data.db.RouteDao
import com.example.data.model.*
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

    suspend fun seedInitialRoutesIfEmpty() {
        if (routeDao.getRouteCount() == 0) {
            // Seed famous scenic motorcycle routes
            val stelvio = RouteItem(
                title = "Stelvio Pass (Passo dello Stelvio)",
                subtitle = "48 Hairpins • 2,757m Elevation",
                totalDistanceKm = 49.5f,
                estimatedMinutes = 68,
                roadType = RoadTypePreference.VERY_CURVY.name,
                groundType = GroundPreference.PAVED.name,
                curvinessScore = 99,
                elevationGainMeters = 1870,
                isRoundtrip = false
            )
            val tailOfDragon = RouteItem(
                title = "Tail of the Dragon (US-129)",
                subtitle = "318 Curves in 11 Miles • Deal's Gap",
                totalDistanceKm = 17.7f,
                estimatedMinutes = 24,
                roadType = RoadTypePreference.VERY_CURVY.name,
                groundType = GroundPreference.PAVED.name,
                curvinessScore = 100,
                elevationGainMeters = 430,
                isRoundtrip = false
            )
            val blackForest = RouteItem(
                title = "Schwarzwaldhochstraße B500",
                subtitle = "Black Forest Sweepers & Pine Ridge",
                totalDistanceKm = 62.0f,
                estimatedMinutes = 55,
                roadType = RoadTypePreference.CURVY.name,
                groundType = GroundPreference.PAVED.name,
                curvinessScore = 88,
                elevationGainMeters = 950,
                isRoundtrip = true
            )
            val tetGravel = RouteItem(
                title = "Trans Euro Trail - Alpine Gravel",
                subtitle = "Military Ridge & Loose Gravel Pass",
                totalDistanceKm = 38.2f,
                estimatedMinutes = 62,
                roadType = RoadTypePreference.CURVY.name,
                groundType = GroundPreference.GRAVEL.name,
                curvinessScore = 92,
                elevationGainMeters = 1250,
                isRoundtrip = false
            )

            routeDao.insertRoute(stelvio)
            routeDao.insertRoute(tailOfDragon)
            routeDao.insertRoute(blackForest)
            routeDao.insertRoute(tetGravel)
        }

        if (gpxDao.getTrackCount() == 0) {
            val sampleGpx = GpxTrack(
                name = "Trollstigen Scenic Pass GPX",
                filename = "trollstigen_pass.gpx",
                totalDistanceKm = 32.4f,
                elevationGainMeters = 852,
                maxElevationMeters = 1120,
                pointCount = 280,
                trackPointsJson = "[]",
                category = "Scenic Alpine",
                isFavorite = true
            )
            gpxDao.insertTrack(sampleGpx)
        }
    }
}
