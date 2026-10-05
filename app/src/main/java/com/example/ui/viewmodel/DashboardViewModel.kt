package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.bluetooth.CompanionConnectionState
import com.example.data.bluetooth.PhoneCompanionManager
import com.example.data.db.AppDatabase
import com.example.data.model.*
import com.example.data.osm.CurvyRouteEngine
import com.example.data.osm.GpxParser
import com.example.data.osm.OsmTileManager
import com.example.data.repository.VibeNavRepository
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

    // Current motorcycle coordinate (default centered on scenic alpine pass: Passo dello Stelvio / 46.5286, 10.4532)
    private val _currentLocation = MutableStateFlow(GeoPoint(46.5286, 10.4532, 2150.0))
    val currentLocation: StateFlow<GeoPoint> = _currentLocation.asStateFlow()

    private val _telemetry = MutableStateFlow(
        TelemetryData(
            speedKmh = 72f,
            speedLimitKmh = 80,
            rpm = 5800,
            gear = "3",
            leanAngleDeg = -14f,
            headingDeg = 52f,
            tripDistanceKm = 34.6f,
            tripDurationMinutes = 42,
            altitudeMeters = 2150,
            isObdEnabled = true
        )
    )
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

    private val _isNavigating = MutableStateFlow(true)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private var rideSimulationJob: Job? = null

    init {
        viewModelScope.launch {
            repository.seedInitialRoutesIfEmpty()
            // Generate initial scenic curvy roundtrip so user has immediate rich map & turn-by-turn navigation
            generateDefaultScenicRoundtrip()
            startRideSimulation()
        }
    }

    private fun generateDefaultScenicRoundtrip() {
        val start = _currentLocation.value
        val config = RoundtripConfig(
            radiusKm = 65,
            selectedDirections = setOf(CompassDirection.NORTH, CompassDirection.EAST),
            roadType = RoadTypePreference.CURVY,
            groundPreference = GroundPreference.PAVED
        )
        val generated = CurvyRouteEngine.generateRoundtrip(start, config)
        _activeRoutePoints.value = generated.points
        _activeTurnInstructions.value = generated.instructions
        _currentTurnIndex.value = 0
    }

    /**
     * Simulates motorcycle ride progression: moving along points, banking/leaning into turns,
     * updating RPM, speed, gear, and advancing turn-by-turn instructions.
     */
    private fun startRideSimulation() {
        rideSimulationJob?.cancel()
        rideSimulationJob = viewModelScope.launch {
            var step = 0
            while (isActive) {
                delay(1200)
                val points = _activeRoutePoints.value
                if (points.isNotEmpty()) {
                    val pIndex = step % points.size
                    val currentPt = points[pIndex]
                    val nextPt = points[(pIndex + 1) % points.size]

                    _currentLocation.value = currentPt

                    // Calculate bearing & lean angle
                    val dLat = nextPt.latitude - currentPt.latitude
                    val dLon = nextPt.longitude - currentPt.longitude
                    val heading = (Math.toDegrees(atan2(dLon, dLat)) + 360.0) % 360.0

                    // Sim lean angle into turn
                    val prevBearing = _telemetry.value.headingDeg
                    var diff = (heading - prevBearing).toFloat()
                    while (diff < -180f) diff += 360f
                    while (diff > 180f) diff -= 360f
                    val targetLean = (diff * 2.2f).coerceIn(-48f, 48f)

                    val baseSpeed = when (_groundPreference.value) {
                        GroundPreference.GRAVEL -> 45f + (sin(step * 0.3) * 8).toFloat()
                        GroundPreference.MIXED -> 58f + (sin(step * 0.3) * 12).toFloat()
                        GroundPreference.PAVED -> 72f + (sin(step * 0.2) * 18).toFloat()
                    }

                    val rpm = (4200 + (baseSpeed * 45) + (sin(step * 0.5) * 400)).toInt().coerceIn(2000, 10500)
                    val gear = when {
                        baseSpeed < 25 -> "1"
                        baseSpeed < 45 -> "2"
                        baseSpeed < 65 -> "3"
                        baseSpeed < 85 -> "4"
                        baseSpeed < 105 -> "5"
                        else -> "6"
                    }

                    val instructions = _activeTurnInstructions.value
                    if (instructions.isNotEmpty()) {
                        val turnProgress = ((step % points.size).toFloat() / points.size * instructions.size).toInt()
                        _currentTurnIndex.value = turnProgress.coerceIn(0, instructions.size - 1)
                    }

                    _telemetry.value = _telemetry.value.copy(
                        speedKmh = baseSpeed,
                        rpm = rpm,
                        gear = gear,
                        headingDeg = heading.toFloat(),
                        leanAngleDeg = targetLean,
                        altitudeMeters = currentPt.altitudeMeters.toInt(),
                        tripDistanceKm = _telemetry.value.tripDistanceKm + 0.025f
                    )

                    step++
                }
            }
        }
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
        _telemetry.value = _telemetry.value.copy(speedLimitKmh = if (config.groundPreference == GroundPreference.GRAVEL) 50 else 80)
        startRideSimulation()
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
        startRideSimulation()
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
        startRideSimulation()
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
        _telemetry.value = _telemetry.value.copy(
            speedLimitKmh = if (groundPreference == GroundPreference.GRAVEL) 50 else 80
        )
        startRideSimulation()
    }

    fun clearOfflineCache() {
        tileManager.clearCache()
    }

    fun getOfflineCacheCount(): Int = tileManager.getOfflineCacheCount()
    fun getOfflineCacheSizeMb(): Float = tileManager.getOfflineCacheSizeMb()
}
