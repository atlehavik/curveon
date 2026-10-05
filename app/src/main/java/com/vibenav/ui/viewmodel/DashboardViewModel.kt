package com.vibenav.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibenav.data.bluetooth.PhoneCompanionManager
import com.vibenav.data.db.AppDatabase
import com.vibenav.data.location.DemoRide
import com.vibenav.data.location.GpsFix
import com.vibenav.data.location.LocationSource
import com.vibenav.data.location.TripTracker
import com.vibenav.data.map.OfflineMaps
import com.vibenav.data.model.*
import com.vibenav.data.repository.VibeNavRepository
import com.vibenav.data.routing.NavigationState
import com.vibenav.data.routing.Navigator
import com.vibenav.data.routing.RoutingStatus
import com.vibenav.routing.LatLon
import com.vibenav.routing.Maneuver
import com.vibenav.routing.ManeuverType
import com.vibenav.routing.RoadStyle
import com.vibenav.routing.RouteOptions
import com.vibenav.routing.SurfacePreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Remaining trip info shown while navigating. */
data class NavigationSummary(
    val remainingKm: Double,
    val remainingMinutes: Int,
    val arrivalTimeMillis: Long,
    val title: String,
    val curviness: Int
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = VibeNavRepository(db.routeDao(), db.gpxDao())
    val phoneCompanion = PhoneCompanionManager(viewModelScope)
    private val offlineMaps = OfflineMaps(application)
    private val navigator = Navigator(application, viewModelScope)

    private val _offlineMapStatus = MutableStateFlow(describeOfflineMaps())
    val offlineMapStatus: StateFlow<String> = _offlineMapStatus.asStateFlow()

    private val _routingDataStatus = MutableStateFlow(navigator.routingData.describe())
    val routingDataStatus: StateFlow<String> = _routingDataStatus.asStateFlow()

    val savedRoutes = repository.allRoutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val savedGpxTracks = repository.allGpxTracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val locationSource = LocationSource(application)
    private val tripTracker = TripTracker()
    private var gpsJob: Job? = null

    // Until the first fix arrives we show Oslo rather than a random spot in the sea.
    private val _currentLocation = MutableStateFlow(DEFAULT_LOCATION)
    val currentLocation: StateFlow<GeoPoint> = _currentLocation.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()

    private val _isFullScreenMap = MutableStateFlow(false)
    val isFullScreenMap: StateFlow<Boolean> = _isFullScreenMap.asStateFlow()

    val navigation: StateFlow<NavigationState> = navigator.state

    val isNavigating: StateFlow<Boolean> =
        navigator.state.map { it.isNavigating }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val routingStatus: StateFlow<RoutingStatus> =
        navigator.state.map { it.status }.stateIn(viewModelScope, SharingStarted.Eagerly, RoutingStatus.Idle)

    val activeRoutePoints: StateFlow<List<GeoPoint>> = navigator.state
        .map { it.route }
        .distinctUntilChanged()
        .map { r -> r?.points?.map { GeoPoint(it.lat, it.lon, it.elevation) }.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groundPreference: StateFlow<GroundPreference> = navigator.state
        .map { s -> s.goal?.options?.surface?.let { GroundPreference.entries[it.ordinal] } ?: GroundPreference.PAVED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GroundPreference.PAVED)

    val currentTurnInstruction: StateFlow<TurnInstruction?> = navigator.state
        .map { s ->
            val p = s.progress ?: return@map null
            val m = p.nextManeuver
            val then = p.followingManeuver?.takeIf {
                it.type != ManeuverType.ARRIVE && it.distanceFromStart - m.distanceFromStart < 300
            }
            TurnInstruction(
                index = s.route?.maneuvers?.indexOf(m) ?: 0,
                type = m.toTurnType(),
                text = m.describe() + (then?.let { " · then " + it.describe().lowercase() } ?: ""),
                distanceMeters = roundDistance(p.distanceToNextManeuver),
                streetName = "",
                speedLimitKmh = p.speedLimitKmh ?: 0,
                location = s.route!!.points[m.pointIndex].let { GeoPoint(it.lat, it.lon) }
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val navigationSummary: StateFlow<NavigationSummary?> = navigator.state
        .map { s ->
            val p = s.progress ?: return@map null
            val route = s.route ?: return@map null
            NavigationSummary(
                remainingKm = p.remainingDistance / 1000.0,
                remainingMinutes = (p.remainingSeconds / 60).roundToInt(),
                arrivalTimeMillis = System.currentTimeMillis() + (p.remainingSeconds * 1000).toLong(),
                title = when (val g = s.goal) {
                    is com.vibenav.data.routing.NavigationGoal.Destination -> g.name
                    is com.vibenav.data.routing.NavigationGoal.Roundtrip -> "Roundtrip %.0f km".format(route.distanceMeters / 1000)
                    null -> ""
                },
                curviness = route.curviness
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Speed limit comes from the route while navigating; unknown otherwise.
        viewModelScope.launch {
            navigator.state.map { it.progress?.speedLimitKmh ?: 0 }.distinctUntilChanged().collect { limit ->
                _telemetry.value = _telemetry.value.copy(speedLimitKmh = limit)
            }
        }
    }

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
        navigator.onLocation(LatLon(fix.latitude, fix.longitude), (fix.accuracyMeters ?: 10f).toDouble())
    }

    private var demoJob: Job? = null
    private val _isDemoRiding = MutableStateFlow(false)
    val isDemoRiding: StateFlow<Boolean> = _isDemoRiding.asStateFlow()

    /** Replaces real GPS with a simulated ride along the active route (or back to GPS). */
    fun toggleDemoRide(speedFactor: Double = 1.0) {
        if (demoJob?.isActive == true) {
            demoJob?.cancel()
            _isDemoRiding.value = false
            startGps()
            return
        }
        val route = navigator.state.value.route ?: return
        gpsJob?.cancel()
        _isDemoRiding.value = true
        demoJob = viewModelScope.launch {
            DemoRide.fixes(route, speedFactor).collect(::onGpsFix)
            _isDemoRiding.value = false
            startGps()
        }
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

    // --- Navigation ---------------------------------------------------------------------------

    fun navigateToDestination(
        destination: GeoPoint,
        destinationName: String,
        roadType: RoadTypePreference = RoadTypePreference.CURVY,
        groundPreference: GroundPreference = GroundPreference.PAVED,
        avoidFerries: Boolean = false,
        avoidMotorways: Boolean = false
    ) {
        navigator.navigateTo(
            destinationName,
            LatLon(destination.latitude, destination.longitude),
            routeOptions(roadType, groundPreference, avoidFerries, avoidMotorways, avoidToll = false)
        )
    }

    fun generateCustomRoundtrip(config: RoundtripConfig) {
        navigator.roundtrip(
            distanceKm = config.radiusKm.toDouble(),
            directionDeg = meanDirection(config.selectedDirections),
            options = routeOptions(config.roadType, config.groundPreference, config.avoidFerries, config.avoidHighways, config.avoidTolls)
        )
    }

    fun stopNavigation() = navigator.stop()

    fun dismissRoutingError() = navigator.dismissError()

    /** Saved routes and sample GPX files are replaced by real GPX import in the next step. */
    fun selectSavedRoute(route: RouteItem) {}

    fun loadSampleGpx(filename: String) {}

    fun downloadRoutingData() {
        viewModelScope.launch {
            runCatching {
                navigator.routingData.downloadNorway { done, total, file ->
                    _routingDataStatus.value = if (done < total) "Downloading routing data $file (${done + 1}/$total)…" else "Download complete"
                }
            }.onFailure { _routingDataStatus.value = "Download failed: ${it.message}. Connect to the phone's hotspot and retry." }
                .onSuccess { _routingDataStatus.value = navigator.routingData.describe() }
        }
    }

    // --- Offline map --------------------------------------------------------------------------

    private fun describeOfflineMaps(): String {
        val maps = offlineMaps.installedMaps()
        return if (maps.isEmpty()) "No offline map installed. Copy a .pmtiles file to ${offlineMaps.mapsDir} or import one."
        else maps.joinToString("\n") { "%s – %.0f MB".format(it.name, it.length() / 1e6) } + "\n(restart the app after changing maps)"
    }

    fun importOfflineMap(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                offlineMaps.import(uri) { p -> _offlineMapStatus.value = "Importing… %.0f%%".format(p * 100) }
            }.onFailure { _offlineMapStatus.value = "Import failed: ${it.message}" }
                .onSuccess { _offlineMapStatus.value = describeOfflineMaps() }
        }
    }

    companion object {
        val DEFAULT_LOCATION = GeoPoint(59.9139, 10.7522)

        fun routeOptions(
            roadType: RoadTypePreference,
            ground: GroundPreference,
            avoidFerries: Boolean,
            avoidMotorways: Boolean,
            avoidToll: Boolean
        ) = RouteOptions(
            roadStyle = RoadStyle.valueOf(roadType.name),
            surface = SurfacePreference.entries[ground.ordinal],
            avoidFerries = avoidFerries,
            avoidMotorways = avoidMotorways,
            avoidToll = avoidToll
        )

        /** Circular mean of the selected compass directions (N + E = NE), north if none. */
        fun meanDirection(directions: Set<CompassDirection>): Double {
            if (directions.isEmpty()) return 0.0
            val x = directions.sumOf { cos(Math.toRadians(it.angleDegrees.toDouble())) }
            val y = directions.sumOf { sin(Math.toRadians(it.angleDegrees.toDouble())) }
            if (x * x + y * y < 1e-6) return directions.first().angleDegrees.toDouble() // opposite picks cancel out
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /** Rounded like a nav unit: 10 m steps below 1 km, 100 m above. */
        fun roundDistance(m: Double): Int = if (m < 1000) ((m / 10).roundToInt() * 10) else ((m / 100).roundToInt() * 100)

        fun Maneuver.toTurnType(): TurnType = when (type) {
            ManeuverType.DEPART -> TurnType.START
            ManeuverType.STRAIGHT -> TurnType.STRAIGHT
            ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.EXIT_LEFT -> TurnType.SLIGHT_LEFT
            ManeuverType.LEFT -> TurnType.LEFT
            ManeuverType.SHARP_LEFT -> TurnType.SHARP_LEFT
            ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.EXIT_RIGHT -> TurnType.SLIGHT_RIGHT
            ManeuverType.RIGHT -> TurnType.RIGHT
            ManeuverType.SHARP_RIGHT -> TurnType.SHARP_RIGHT
            ManeuverType.U_TURN -> TurnType.U_TURN
            ManeuverType.ROUNDABOUT -> TurnType.ROUNDABOUT
            ManeuverType.ARRIVE -> TurnType.DESTINATION
        }

        fun Maneuver.describe(): String = when (type) {
            ManeuverType.DEPART -> "Start"
            ManeuverType.STRAIGHT -> "Continue straight"
            ManeuverType.SLIGHT_LEFT -> "Bear left"
            ManeuverType.LEFT -> "Turn left"
            ManeuverType.SHARP_LEFT -> "Sharp left"
            ManeuverType.SLIGHT_RIGHT -> "Bear right"
            ManeuverType.RIGHT -> "Turn right"
            ManeuverType.SHARP_RIGHT -> "Sharp right"
            ManeuverType.KEEP_LEFT -> "Keep left"
            ManeuverType.KEEP_RIGHT -> "Keep right"
            ManeuverType.EXIT_LEFT -> "Take the exit on the left"
            ManeuverType.EXIT_RIGHT -> "Take the exit on the right"
            ManeuverType.U_TURN -> "Make a U-turn"
            ManeuverType.ROUNDABOUT -> if (exitNumber > 0) "Roundabout, take exit $exitNumber" else "Enter the roundabout"
            ManeuverType.ARRIVE -> "Arrive at destination"
        }
    }
}
