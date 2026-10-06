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
import com.vibenav.data.ride.LeanSensor
import com.vibenav.data.ride.RideRecorder
import com.vibenav.data.routing.CameraAlert
import com.vibenav.data.routing.FuelHint
import com.vibenav.data.routing.NavigationGoal
import com.vibenav.data.routing.NavigationState
import com.vibenav.data.routing.Navigator
import com.vibenav.data.routing.RecentDestinations
import com.vibenav.data.routing.RouteHazards
import com.vibenav.data.routing.RoutePreview
import com.vibenav.data.routing.RoutingStatus
import com.vibenav.data.routing.SavedRoutes
import com.vibenav.data.routing.Stop
import com.vibenav.data.search.PlaceSearch
import com.vibenav.data.search.SearchResult
import com.vibenav.routing.LatLon
import com.vibenav.routing.Maneuver
import com.vibenav.routing.ManeuverType
import com.vibenav.routing.RoadStyle
import com.vibenav.routing.RouteOptions
import com.vibenav.routing.Routes
import com.vibenav.routing.Sun
import com.vibenav.routing.SurfacePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val curviness: Int,
    /** Distance to the next stop, when there is one before the destination. */
    val nextStop: Pair<String, Double>? = null
)

enum class MapTheme { AUTO, DAY, NIGHT }

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = VibeNavRepository(db.routeDao(), db.gpxDao())
    private val rideDao = db.rideDao()
    val phoneCompanion = PhoneCompanionManager(application, viewModelScope)
    private val offlineMaps = OfflineMaps(application)
    private val navigator = Navigator(application, viewModelScope)
    private val placeSearch = PlaceSearch(application)
    private val hazards = RouteHazards(placeSearch)
    private val recentDestinations = RecentDestinations(application)
    private val settings = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private val _offlineMapStatus = MutableStateFlow(describeOfflineMaps())
    val offlineMapStatus: StateFlow<String> = _offlineMapStatus.asStateFlow()

    private val _routingDataStatus = MutableStateFlow(navigator.routingData.describe())
    val routingDataStatus: StateFlow<String> = _routingDataStatus.asStateFlow()

    val savedRoutes = repository.allRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val savedGpxTracks = repository.allGpxTracks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------------------------------------------------------------- GPX overlays

    private val trackWatcher = com.vibenav.data.gpx.TrackWatcher()

    /** Tracks switched on in the GPX manager, decoded for the map. */
    val overlayTracks: StateFlow<List<com.vibenav.data.gpx.OverlayTrack>> = repository.allGpxTracks
        .map { list -> list.filter { it.showOnMap } }
        .distinctUntilChanged()
        .map { list -> withContext(Dispatchers.Default) { list.map(com.vibenav.data.gpx.GpxOverlay::overlay) } }
        .onEach { trackWatcher.setTracks(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _poiCategories = MutableStateFlow(
        settings.getStringSet("poiCategories", null)?.mapNotNull { runCatching { com.vibenav.data.gpx.PoiCategory.valueOf(it) }.getOrNull() }?.toSet()
            ?: com.vibenav.data.gpx.PoiCategory.entries.toSet()
    )
    val poiCategories: StateFlow<Set<com.vibenav.data.gpx.PoiCategory>> = _poiCategories.asStateFlow()

    /** POIs of visible tracks, filtered by the categories the rider wants to see. */
    val overlayPois: StateFlow<List<com.vibenav.data.gpx.GpxPoi>> = combine(overlayTracks, _poiCategories) { tracks, cats ->
        tracks.flatMap { it.pois }.filter { it.category in cats }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _trackHint = MutableStateFlow<com.vibenav.data.gpx.TrackHint?>(null)
    /** Nearest visible track (line from the rider to it); null while navigating. */
    val trackHint: StateFlow<com.vibenav.data.gpx.TrackHint?> = _trackHint.asStateFlow()

    private val _autoFollow = MutableStateFlow(settings.getBoolean("autoFollowTracks", false))
    val autoFollowTracks: StateFlow<Boolean> = _autoFollow.asStateFlow()

    /** Tracks the rider stopped following on purpose: don't start them again right away. */
    private val followSuppressedUntil = HashMap<Long, Long>()
    val rides = rideDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val locationSource = LocationSource(application)
    private val tripTracker = TripTracker()
    private var gpsJob: Job? = null

    // Until the first fix arrives we show Oslo rather than a random spot in the sea.
    private val _currentLocation = MutableStateFlow(DEFAULT_LOCATION)
    val currentLocation: StateFlow<GeoPoint> = _currentLocation.asStateFlow()

    private val _telemetry = MutableStateFlow(
        TelemetryData(useMph = settings.getBoolean("mph", false), isObdEnabled = settings.getBoolean("obd", false))
    )
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()

    private val _isFullScreenMap = MutableStateFlow(false)
    val isFullScreenMap: StateFlow<Boolean> = _isFullScreenMap.asStateFlow()

    val navigation: StateFlow<NavigationState> = navigator.state

    val isNavigating: StateFlow<Boolean> =
        navigator.state.map { it.isNavigating }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val routingStatus: StateFlow<RoutingStatus> =
        navigator.state.map { it.status }.stateIn(viewModelScope, SharingStarted.Eagerly, RoutingStatus.Idle)

    val preview: StateFlow<RoutePreview?> =
        navigator.state.map { it.preview }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeRoutePoints: StateFlow<List<GeoPoint>> = navigator.state
        .map { it.route }
        .distinctUntilChanged()
        .map { r -> r?.points?.map { GeoPoint(it.lat, it.lon, it.elevation) }.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Remaining stops plus the destination, for markers on the map. */
    val routeMarkers: StateFlow<List<GeoPoint>> = navigator.state
        .map { s ->
            val g = (s.preview?.goal ?: s.goal) as? NavigationGoal.Destination ?: return@map emptyList()
            (g.stops.map { it.point } + g.destination).map { GeoPoint(it.lat, it.lon) }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groundPreference: StateFlow<GroundPreference> = navigator.state
        .map { s -> s.goal?.options?.surface?.let { GroundPreference.entries[it.ordinal] } ?: GroundPreference.PAVED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GroundPreference.PAVED)

    // ---------------------------------------------------------------- road names

    /** Road name/number after each maneuver, looked up from the map tiles as it comes into view. */
    private val roadNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    private var roadNamesRoute: com.vibenav.routing.Route? = null

    /** Point just after the next maneuver, where the map should read the road name. */
    val roadNameProbe: StateFlow<Pair<Int, GeoPoint>?> = navigator.state
        .map { s ->
            val route = s.route ?: return@map null
            val m = s.progress?.nextManeuver ?: return@map null
            if (m.type == ManeuverType.ARRIVE) return@map null
            val idx = route.maneuvers.indexOf(m)
            if (roadNames.value.containsKey(idx)) return@map null
            val (p, _) = Routes.pointAt(route, m.distanceFromStart + 30)
            idx to GeoPoint(p.lat, p.lon)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onRoadName(maneuverIndex: Int, name: String?) {
        val route = navigator.state.value.route
        if (route !== roadNamesRoute) { roadNames.value = emptyMap(); roadNamesRoute = route }
        roadNames.value = roadNames.value + (maneuverIndex to (name ?: ""))
    }

    val currentTurnInstruction: StateFlow<TurnInstruction?> = combine(navigator.state, roadNames) { s, names ->
        val p = s.progress ?: return@combine null
        val route = s.route ?: return@combine null
        val m = p.nextManeuver
        val idx = route.maneuvers.indexOf(m)
        val then = p.followingManeuver?.takeIf {
            it.type != ManeuverType.ARRIVE && it.distanceFromStart - m.distanceFromStart < 300
        }
        val road = if (route === roadNamesRoute) names[idx]?.takeIf { it.isNotEmpty() } else null
        TurnInstruction(
            index = idx,
            type = m.toTurnType(),
            text = m.describe() +
                (road?.let { " · $it" } ?: "") +
                (then?.let { " · then " + it.describe(null).lowercase() } ?: ""),
            distanceMeters = roundDistance(p.distanceToNextManeuver),
            streetName = road ?: "",
            speedLimitKmh = p.speedLimitKmh ?: 0,
            location = route.points[m.pointIndex].let { GeoPoint(it.lat, it.lon) }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val navigationSummary: StateFlow<NavigationSummary?> = navigator.state
        .map { s ->
            val p = s.progress ?: return@map null
            val route = s.route ?: return@map null
            NavigationSummary(
                remainingKm = p.remainingDistance / 1000.0,
                remainingMinutes = (p.remainingSeconds / 60).roundToInt(),
                arrivalTimeMillis = System.currentTimeMillis() + (p.remainingSeconds * 1000).toLong(),
                title = s.goal?.title ?: "",
                curviness = route.curviness,
                nextStop = s.stopsAlong.firstOrNull()?.let { (stop, along) -> stop.name to (along - p.distanceAlongRoute) / 1000.0 }
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---------------------------------------------------------------- fuel & cameras

    private val _fuelHint = MutableStateFlow<FuelHint?>(null)
    val fuelHint: StateFlow<FuelHint?> = _fuelHint.asStateFlow()

    private val _cameraAlert = MutableStateFlow<CameraAlert?>(null)
    val cameraAlert: StateFlow<CameraAlert?> = _cameraAlert.asStateFlow()

    // ---------------------------------------------------------------- day / night

    private val _mapTheme = MutableStateFlow(MapTheme.valueOf(settings.getString("mapTheme", MapTheme.AUTO.name)!!))
    val mapTheme: StateFlow<MapTheme> = _mapTheme.asStateFlow()

    private val _mapDark = MutableStateFlow(true)
    val mapDark: StateFlow<Boolean> = _mapDark.asStateFlow()

    fun setMapTheme(theme: MapTheme) {
        _mapTheme.value = theme
        settings.edit().putString("mapTheme", theme.name).apply()
        updateMapDark()
    }

    private fun updateMapDark() {
        val loc = _currentLocation.value
        _mapDark.value = when (_mapTheme.value) {
            MapTheme.DAY -> false
            MapTheme.NIGHT -> true
            MapTheme.AUTO -> !Sun.isDay(loc.latitude, loc.longitude, System.currentTimeMillis())
        }
    }

    // ---------------------------------------------------------------- ride log

    private val rideRecorder = RideRecorder()
    private val leanSensor = LeanSensor(application)

    private val _currentRide = MutableStateFlow<Ride?>(null)
    val currentRide: StateFlow<Ride?> = _currentRide.asStateFlow()

    init {
        // Create the data folders up front so they exist (and are owned by the app) when the
        // rider connects the tablet over USB to copy maps, routing data and the search index.
        offlineMaps.mapsDir; navigator.routingData.segmentsDir; placeSearch.indexDir
        updateMapDark()
        startDeadReckoning()
        leanSensor.start()

        viewModelScope.launch { while (true) { delay(60_000); updateMapDark() } }

        // Speed limit while navigating comes from the route.
        viewModelScope.launch {
            navigator.state.map { it.progress?.speedLimitKmh ?: 0 }.distinctUntilChanged().collect { limit ->
                if (navigator.state.value.isNavigating) _telemetry.value = _telemetry.value.copy(speedLimitKmh = limit)
            }
        }
        // Free riding: probe the road we're on for its limit and look for cameras ahead.
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                val t = _telemetry.value
                if (navigator.state.value.isNavigating || !t.hasGpsFix) continue
                val here = LatLon(_currentLocation.value.latitude, _currentLocation.value.longitude)
                val limit = if (t.speedKmh < 8f) t.speedLimitKmh // standing still: heading is unreliable, keep last
                else navigator.probeSpeedLimit(here, t.headingDeg.toDouble()) ?: 0
                _telemetry.value = _telemetry.value.copy(speedLimitKmh = limit)
                _cameraAlert.value = if (t.speedKmh < 15f) null else hazards.cameraAheadFreeRide(here, t.headingDeg.toDouble())
            }
        }
        // New route: load fuel and cameras along it; forget road names of the old one.
        viewModelScope.launch {
            navigator.state.map { it.route }.distinctUntilChanged().collect { route ->
                hazards.load(route)
                if (route == null) { _fuelHint.value = null; _cameraAlert.value = null }
            }
        }
        // While navigating: next fuel and camera ahead on the route.
        viewModelScope.launch {
            navigator.state.mapNotNull { it.progress }.collect { p ->
                _fuelHint.value = hazards.nextFuel(p.distanceAlongRoute, p.remainingDistance)
                _cameraAlert.value = hazards.cameraAhead(p.distanceAlongRoute)
            }
        }
    }

    override fun onCleared() {
        leanSensor.stop()
        saveRide(finish = true)
        super.onCleared()
    }

    /** Called by the activity once location permission is granted. Safe to call repeatedly. */
    fun startGps() {
        if (gpsJob?.isActive == true) return
        locationSource.lastKnown()?.let {
            _currentLocation.value = GeoPoint(it.latitude, it.longitude, it.altitudeMeters ?: 0.0)
            updateMapDark()
        }
        gpsJob = viewModelScope.launch {
            locationSource.fixes().collect(::onGpsFix)
        }
    }

    private var lastRealFixAt = 0L

    private fun onGpsFix(fix: GpsFix) {
        lastRealFixAt = System.currentTimeMillis()
        handleFix(fix, estimated = false)
    }

    /**
     * Tunnels: Norwegian tunnels run for kilometres without GPS. While navigating we keep the
     * rider moving along the route at the last known speed until real fixes return.
     */
    private fun startDeadReckoning() = viewModelScope.launch {
        var anchorAlong = 0.0
        var anchorTime = 0L
        var anchorSpeedMps = 0.0
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            val nav = navigator.state.value
            val route = nav.route
            val gap = now - lastRealFixAt
            if (route == null || lastRealFixAt == 0L || gap < DR_START_MS || gap > DR_MAX_MS || _isDemoRiding.value) {
                anchorTime = 0L
                if (_telemetry.value.isDeadReckoning) _telemetry.value = _telemetry.value.copy(isDeadReckoning = false)
                continue
            }
            if (anchorTime == 0L) {
                anchorSpeedMps = (_telemetry.value.speedKmh / 3.6).toDouble()
                if (anchorSpeedMps < 4.0) continue // stopped in the tunnel or parked: don't invent movement
                anchorAlong = nav.progress?.distanceAlongRoute ?: continue
                anchorTime = lastRealFixAt
            }
            val along = anchorAlong + anchorSpeedMps * (now - anchorTime) / 1000.0
            val (p, bearing) = Routes.pointAt(route, along)
            handleFix(GpsFix(p.lat, p.lon, null, anchorSpeedMps.toFloat(), bearing.toFloat(), 25f, now), estimated = true)
        }
    }

    private fun handleFix(fix: GpsFix, estimated: Boolean) {
        tripTracker.onFix(fix)
        _currentLocation.value = GeoPoint(fix.latitude, fix.longitude, fix.altitudeMeters ?: _currentLocation.value.altitudeMeters)
        val stats = tripTracker.stats
        _telemetry.value = _telemetry.value.copy(
            speedKmh = tripTracker.speedKmh,
            headingDeg = tripTracker.headingDeg,
            altitudeMeters = (fix.altitudeMeters ?: _telemetry.value.altitudeMeters.toDouble()).roundToInt(),
            hasGpsFix = true,
            gpsAccuracyMeters = (fix.accuracyMeters ?: 0f).roundToInt(),
            isDeadReckoning = estimated,
            tripDistanceKm = (stats.distanceMeters / 1000.0).toFloat(),
            tripDurationMinutes = (stats.movingTimeMillis / 60_000L).toInt()
        )
        navigator.onLocation(LatLon(fix.latitude, fix.longitude), (fix.accuracyMeters ?: 10f).toDouble())
        recordRide(fix)
        watchTracks(fix)
    }

    private fun recordRide(fix: GpsFix) {
        leanSensor.speedMps = tripTracker.speedKmh / 3.6f
        when (rideRecorder.onFix(fix, tripTracker.speedKmh, leanSensor.leanDeg)) {
            RideRecorder.Event.STARTED -> viewModelScope.launch {
                val id = rideDao.insert(rideRecorder.current!!)
                rideRecorder.setId(id)
                placeName(fix.latitude, fix.longitude)?.let { rideRecorder.setTitle(it) }
            }
            RideRecorder.Event.UPDATED -> saveRide(finish = false)
            RideRecorder.Event.FINISHED -> saveRide(finish = true)
            RideRecorder.Event.NONE -> Unit
        }
        _currentRide.value = rideRecorder.current
    }

    private fun saveRide(finish: Boolean) {
        val current = rideRecorder.current ?: return
        if (current.id == 0L) return
        val ride = if (finish) rideRecorder.finish() else current
        viewModelScope.launch(Dispatchers.IO) {
            if (ride == null) { rideDao.delete(current.id); return@launch } // too short to keep
            val pts = RideRecorder.parseTrack(ride.track)
            val end = pts.lastOrNull()
            val startName = ride.title.substringBefore(" → ")
            val endName = end?.let { placeName(it.lat, it.lon) }
            val title = if (finish && endName != null && endName != startName) "$startName → $endName" else ride.title
            rideDao.update(ride.copy(title = title))
        }
    }

    /** Name for a ride end: the closest place, nudged towards real towns when distances are similar. */
    private suspend fun placeName(lat: Double, lon: Double): String? {
        val bonusKm = mapOf("city" to 3.0, "town" to 2.0, "village" to 1.0, "suburb" to 0.5, "hamlet" to 0.0)
        return bonusKm.keys.mapNotNull { kind -> placeSearch.nearest(kind, lat, lon, 1).firstOrNull() }
            .filter { it.distanceKm < 10 }
            .minByOrNull { it.distanceKm - (bonusKm[it.kind] ?: 0.0) }
            ?.name
    }

    fun deleteRide(ride: Ride) = viewModelScope.launch { rideDao.delete(ride.id) }

    fun exportRide(ride: Ride, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(RideRecorder.toGpx(ride).toByteArray()) }
    }

    /** Ride a logged ride again, as a track. */
    fun rideAgain(ride: Ride) {
        val pts = RideRecorder.parseTrack(ride.track).map { LatLon(it.lat, it.lon) }
        if (pts.size >= 2) navigator.previewTrack(ride.title.ifEmpty { "Earlier ride" }, pts, RouteOptions(RoadStyle.FASTEST, SurfacePreference.MIXED))
    }

    // ---------------------------------------------------------------- demo ride

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
        settings.edit().putBoolean("mph", _telemetry.value.useMph).apply()
    }

    fun toggleObd() {
        _telemetry.value = _telemetry.value.copy(isObdEnabled = !_telemetry.value.isObdEnabled)
        settings.edit().putBoolean("obd", _telemetry.value.isObdEnabled).apply()
    }

    // ---------------------------------------------------------------- planning

    /** Last surface/avoid choices, reused for the next preview. */
    private var lastOptions = RouteOptions(
        RoadStyle.valueOf(settings.getString("style", RoadStyle.CURVY.name)!!),
        SurfacePreference.valueOf(settings.getString("surface", SurfacePreference.PAVED.name)!!),
        avoidFerries = settings.getBoolean("avoidFerries", false),
        avoidMotorways = settings.getBoolean("avoidMotorways", false)
    ).also { navigator.preferredStyle = it.roadStyle }

    fun previewDestination(point: GeoPoint, name: String) {
        navigator.previewDestination(name, LatLon(point.latitude, point.longitude), lastOptions)
    }

    fun previewRoundtrip(config: RoundtripConfig) {
        navigator.previewRoundtrip(
            distanceKm = config.radiusKm.toDouble(),
            directionDeg = meanDirection(config.selectedDirections),
            options = routeOptions(config.roadType, config.groundPreference, config.avoidFerries, config.avoidHighways, config.avoidTolls)
        )
    }

    fun previewGpx(track: GpxTrack, reverse: Boolean, ground: GroundPreference) {
        val pts = com.vibenav.data.osm.GpxParser.decodePoints(track.trackPointsJson).map { LatLon(it.latitude, it.longitude) }
        navigator.previewTrack(track.name, if (reverse) pts.reversed() else pts,
            RouteOptions(RoadStyle.FASTEST, SurfacePreference.entries[ground.ordinal]))
    }

    fun previewSaved(item: RouteItem) {
        when (val g = SavedRoutes.toGoal(item)) {
            is NavigationGoal.Destination -> navigator.previewDestination(g.name, g.destination, g.options, g.stops)
            is NavigationGoal.Track -> navigator.previewTrack(g.name, g.points, g.options)
            is NavigationGoal.Roundtrip -> Unit // saved loops are stored as tracks
        }
    }

    fun selectCandidate(index: Int) = navigator.selectCandidate(index)

    fun setPreviewSurface(surface: SurfacePreference) = navigator.updatePreviewOptions { it.copy(surface = surface) }

    fun togglePreviewAvoidFerries() = navigator.updatePreviewOptions { it.copy(avoidFerries = !it.avoidFerries) }

    fun togglePreviewAvoidMotorways() = navigator.updatePreviewOptions { it.copy(avoidMotorways = !it.avoidMotorways) }

    fun removePreviewStop(index: Int) = navigator.removePreviewStop(index)

    fun shuffleRoundtrip() = navigator.shuffleRoundtrip()

    fun reversePreviewTrack() = navigator.reversePreviewTrack()

    fun cancelPreview() = navigator.cancelPreview()

    fun startPreview() {
        val p = navigator.state.value.preview ?: return
        val style = p.candidates.getOrNull(p.selected)?.style
        lastOptions = p.goal.options.copy(roadStyle = style ?: lastOptions.roadStyle)
        settings.edit()
            .putString("style", lastOptions.roadStyle.name).putString("surface", lastOptions.surface.name)
            .putBoolean("avoidFerries", lastOptions.avoidFerries).putBoolean("avoidMotorways", lastOptions.avoidMotorways)
            .apply()
        (p.goal as? NavigationGoal.Destination)?.let { recentDestinations.add(Stop(it.name, it.destination)) }
        navigator.startPreview()
    }

    /** Saves the previewed (or active) route under [name]. */
    fun saveRoute(name: String) {
        val s = navigator.state.value
        val goal = s.preview?.goal ?: s.goal ?: return
        val route = s.preview?.selectedRoute ?: s.route ?: return
        val g = if (goal is NavigationGoal.Destination && s.preview != null) {
            goal.copy(options = goal.options.copy(roadStyle = s.preview.candidates[s.preview.selected].style ?: goal.options.roadStyle))
        } else goal
        viewModelScope.launch { repository.saveRoute(SavedRoutes.toItem(g, route, name)) }
    }

    fun deleteSavedRoute(item: RouteItem) = viewModelScope.launch { repository.deleteRoute(item.id) }

    fun recents(): List<Stop> = recentDestinations.all()

    /** Add a stop to the active route (or to the preview). */
    fun addStop(point: GeoPoint, name: String) = navigator.addStop(Stop(name, LatLon(point.latitude, point.longitude)))

    fun stopNavigation() = navigator.stop()

    fun dismissRoutingError() = navigator.dismissError()

    // ---------------------------------------------------------------- search

    fun searchStatus(): String = placeSearch.describe()

    suspend fun searchPlaces(query: String) =
        placeSearch.search(query, _currentLocation.value.latitude, _currentLocation.value.longitude)

    suspend fun nearestFuel(): List<SearchResult> {
        val p = _currentLocation.value
        return placeSearch.nearest("fuel", p.latitude, p.longitude, 8)
    }

    // ---------------------------------------------------------------- GPX import

    private val _gpxMessage = MutableStateFlow<String?>(null)
    val gpxMessage: StateFlow<String?> = _gpxMessage.asStateFlow()

    private val gpxImporter = com.vibenav.data.osm.GpxImporter(application, repository)

    fun importGpx(uri: Uri) {
        viewModelScope.launch {
            runCatching { gpxImporter.import(uri) }
                .onSuccess { _gpxMessage.value = if (it.size == 1) "Imported ${it[0]}" else "Imported ${it.size} tracks" }
                .onFailure { _gpxMessage.value = "Import failed: ${it.message}" }
        }
    }

    // Declared after gpxImporter and _gpxMessage so they exist when this runs.
    init { importGpxInbox() }

    /** GPX files copied over USB into the gpx/ folder are imported at startup. */
    private fun importGpxInbox() = viewModelScope.launch {
        val names = runCatching { gpxImporter.importInbox() }.getOrDefault(emptyList())
        if (names.isNotEmpty()) _gpxMessage.value = "Imported ${names.size} tracks from the gpx folder"
    }

    private fun watchTracks(fix: GpsFix) {
        val nav = navigator.state.value
        if (nav.isNavigating || nav.preview != null) {
            _trackHint.value = null
            trackWatcher.reset()
            return
        }
        _trackHint.value = trackWatcher.onFix(LatLon(fix.latitude, fix.longitude), tripTracker.headingDeg.toDouble(), tripTracker.speedKmh)
        val (track, reverse) = trackWatcher.ridingAlong() ?: return
        if (_autoFollow.value && System.currentTimeMillis() > (followSuppressedUntil[track.id] ?: 0L)) {
            followOverlayTrack(track.id, reverse)
        }
    }

    /** Navigate along a visible track from where the rider is (from the Follow chip or auto-follow). */
    fun followOverlayTrack(trackId: Long, reverse: Boolean) {
        val t = overlayTracks.value.firstOrNull { it.id == trackId } ?: return
        val pts = t.points.map { LatLon(it.latitude, it.longitude) }
        followSuppressedUntil[trackId] = System.currentTimeMillis() + 10 * 60_000L
        trackWatcher.reset()
        _trackHint.value = null
        navigator.followTrackNow(t.name, if (reverse) pts.reversed() else pts, RouteOptions(RoadStyle.FASTEST, SurfacePreference.MIXED))
    }

    fun setTrackOnMap(track: GpxTrack, show: Boolean) = viewModelScope.launch {
        repository.updateGpxTrack(track.copy(showOnMap = show, color = com.vibenav.data.gpx.GpxOverlay.colorOf(track)))
    }

    fun setTrackColor(track: GpxTrack, color: Int) = viewModelScope.launch { repository.updateGpxTrack(track.copy(color = color)) }

    fun setTrackPois(track: GpxTrack, show: Boolean) = viewModelScope.launch { repository.updateGpxTrack(track.copy(showPois = show)) }

    fun togglePoiCategory(c: com.vibenav.data.gpx.PoiCategory) {
        val next = if (c in _poiCategories.value) _poiCategories.value - c else _poiCategories.value + c
        _poiCategories.value = next
        settings.edit().putStringSet("poiCategories", next.map { it.name }.toSet()).apply()
    }

    fun setAutoFollow(on: Boolean) {
        _autoFollow.value = on
        settings.edit().putBoolean("autoFollowTracks", on).apply()
    }

    fun deleteGpx(track: GpxTrack) {
        viewModelScope.launch { repository.deleteGpxTrack(track.id) }
    }

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

    // ---------------------------------------------------------------- offline map

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
        private const val DR_START_MS = 3_000L
        private const val DR_MAX_MS = 15 * 60_000L // longest Norwegian road tunnel (Lærdal, 24.5 km) at low speed

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

        fun Maneuver.describe(stopName: String? = null): String = when (type) {
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
            ManeuverType.ARRIVE -> if (stopName != null) "Arrive at $stopName" else "Arrive at destination"
        }
    }
}
