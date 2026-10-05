package com.vibenav.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibenav.data.bluetooth.CompanionConnectionState
import com.vibenav.data.bluetooth.PhoneCompanionManager
import com.vibenav.data.db.AppDatabase
import com.vibenav.data.location.GpsFix
import com.vibenav.data.location.LocationSource
import com.vibenav.data.location.TripTracker
import com.vibenav.data.model.*
import com.vibenav.data.osm.CurvyRouteEngine
import com.vibenav.data.osm.GpxParser
import com.vibenav.data.osm.OsmTileManager
import com.vibenav.data.repository.VibeNavRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = VibeNavRepository(db.routeDao(), db.gpxDao())
    val phoneCompanion = PhoneCompanionManager(viewModelScope)
    val tileManager = OsmTileManager(application)

    val savedRoutes = repository.allRoutes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val savedGpxTracks = repository.allGpxTracks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val locationSource = LocationSource(application)
    private val tripTracker = TripTracker()
    private var gpsJob: Job? = null

    // Until the first fix arrives we show Oslo rather than a random spot in the sea.
    private val _currentLocation = MutableStateFlow(DEFAULT_LOCATION)
    val currentLocation: StateFlow<GeoPoint> = _currentLocation.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()

    // Navigation & Route State
    private val _activeRoutePoints = MutableStateFlow<List<GeoPoint>>(emptyList())
    val activeRoutePoints: StateFlow<List<GeoPoint>> = _activeRoutePoints.asStateFlow()

    private val _activeTurnInstructions = MutableStateFlow<List<TurnInstruction>>(emptyList())
    val activeTurnInstructions: StateFlow<List<TurnInstruction>> = _activeTurnInstructions.asStateFlow()

    private val _currentTurnIndex = MutableStateFlow(0)
    val currentTurnIndex: StateFlow<Int> = _currentTurnIndex.asStateFlow()

    val currentTurnInstruction: StateFlow<TurnInstruction?> = combine(
        _activeTurnInstructions,
        _currentTurnIndex
    ) { instructions, index ->
        if (instructions.isNotEmpty() && index in instructions.indices) {
            instructions[index]
        } else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isFullScreenMap = MutableStateFlow(false)
    val isFullScreenMap: StateFlow<Boolean> = _isFullScreenMap.asStateFlow()

    private val _groundPreference = MutableStateFlow(GroundPreference.PAVED)
    val groundPreference: StateFlow<GroundPreference> = _groundPreference.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    /** Called by the activity once location permission is granted. Safe to call repeatedly. */
    fun startGps() {
        if (gpsJob?.isActive == true) return
        locationSource.lastKnown()?.let { _currentLocation.value = GeoPoint(it.latitude, it.longitude, it.altitudeMeters ?: 0.0) }
        gpsJob = viewModelScope.launch {
            locationSource.fixes().collect(::onGpsFix)
        }
    }

    private fun onGpsFix(fix: GpsFix) {
        tripTracker.onFix(fix)
        _currentLocation.value = GeoPoint(fix.latitude, fix.longitude, fix.altitudeMeters ?: _currentLocation.value.altitudeMeters)
        val stats = tripTracker.stats
        _telemetry.value = _telemetry.value.copy(
            speedKmh = tripTracker.speedKmh,
            headingDeg = tripTracker.headingDeg,
            altitudeMeters = (fix.altitudeMeters ?: 0.0).roundToInt(),
            hasGpsFix = true,
            gpsAccuracyMeters = (fix.accuracyMeters ?: 0f).roundToInt(),
            tripDistanceKm = (stats.distanceMeters / 1000.0).toFloat(),
            tripDurationMinutes = (stats.movingTimeMillis / 60_000L).toInt()
        )
        advanceTurnInstruction()
    }

    /** Placeholder until real route following lands: advance to the nearest upcoming instruction. */
    private fun advanceTurnInstruction() {
        val instructions = _activeTurnInstructions.value
        if (instructions.isEmpty()) return
        val here = _currentLocation.value
        val nearest = instructions.indices.minByOrNull { i ->
            TripTracker.distanceMeters(here.latitude, here.longitude, instructions[i].location.latitude, instructions[i].location.longitude)
        } ?: return
        if (nearest > _currentTurnIndex.value) _currentTurnIndex.value = nearest
    }

    fun resetTrip() {
        tripTracker.reset()
        _telemetry.value = _telemetry.value.copy(tripDistanceKm = 0f, tripDurationMinutes = 0)
    }

    fun toggleFullScreenMap() {
        _isFullScreenMap.value = !_isFullScreenMap.value
    }

    fun toggleSpeedUnits() {
        _telemetry.value = _telemetry.value.copy(useMph = !_telemetry.value.useMph)
    }

    fun toggleObd() {
        _telemetry.value = _telemetry.value.copy(isObdEnabled = !_telemetry.value.isObdEnabled)
    }

    fun generateCustomRoundtrip(config: RoundtripConfig) {
        val start = _currentLocation.value
        val generated = CurvyRouteEngine.generateRoundtrip(start, config)
        _activeRoutePoints.value = generated.points
        _activeTurnInstructions.value = generated.instructions
        _groundPreference.value = config.groundPreference
        _currentTurnIndex.value = 0
        _isNavigating.value = true
    }

    fun selectSavedRoute(route: RouteItem) {
        val start = _currentLocation.value
        val config = RoundtripConfig(
            radiusKm = route.totalDistanceKm.toInt(),
            selectedDirections = setOf(CompassDirection.NORTH, CompassDirection.NORTH_EAST),
            roadType = RoadTypePreference.valueOf(route.roadType),
            groundPreference = GroundPreference.valueOf(route.groundType)
        )
        val generated = CurvyRouteEngine.generateRoundtrip(start, config)
        _activeRoutePoints.value = generated.points
        _activeTurnInstructions.value = generated.instructions
        _groundPreference.value = config.groundPreference
        _currentTurnIndex.value = 0
        _isNavigating.value = true
    }

    fun loadSampleGpx(filename: String) {
        val start = _currentLocation.value
        val config = RoundtripConfig(
            radiusKm = 95,
            selectedDirections = setOf(CompassDirection.NORTH, CompassDirection.WEST),
            roadType = RoadTypePreference.VERY_CURVY,
            groundPreference = if (filename.contains("TET")) GroundPreference.GRAVEL else GroundPreference.PAVED
        )
        val generated = CurvyRouteEngine.generateRoundtrip(start, config)
        _activeRoutePoints.value = generated.points
        _activeTurnInstructions.value = generated.instructions
        _groundPreference.value = config.groundPreference
        _currentTurnIndex.value = 0
        _isNavigating.value = true
    }

    fun navigateToDestination(
        destination: GeoPoint,
        destinationName: String,
        roadType: RoadTypePreference = RoadTypePreference.CURVY,
        groundPreference: GroundPreference = GroundPreference.PAVED
    ) {
        val start = _currentLocation.value
        val generated = CurvyRouteEngine.generateRouteToDestination(
            start = start,
            destination = destination,
            destinationName = destinationName,
            roadType = roadType,
            groundPreference = groundPreference
        )
        _activeRoutePoints.value = generated.points
        _activeTurnInstructions.value = generated.instructions
        _groundPreference.value = groundPreference
        _currentTurnIndex.value = 0
        _isNavigating.value = true
    }

    fun clearOfflineCache() {
        tileManager.clearCache()
    }

    fun getOfflineCacheCount(): Int = tileManager.getOfflineCacheCount()
    fun getOfflineCacheSizeMb(): Float = tileManager.getOfflineCacheSizeMb()

    companion object {
        val DEFAULT_LOCATION = GeoPoint(59.9139, 10.7522)
    }
}
