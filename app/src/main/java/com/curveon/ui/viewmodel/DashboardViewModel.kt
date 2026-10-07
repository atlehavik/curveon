package com.curveon.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.curveon.R
import com.curveon.data.bluetooth.PhoneCompanionManager
import com.curveon.data.db.AppDatabase
import com.curveon.data.gpx.GpxGroups
import com.curveon.data.gpx.GpxWriter
import com.curveon.data.location.DemoRide
import com.curveon.data.location.GpsFix
import com.curveon.data.location.LocationSource
import com.curveon.data.location.TripTracker
import com.curveon.data.map.OfflineMaps
import com.curveon.data.model.*
import com.curveon.data.repository.CurveonRepository
import com.curveon.data.ride.LeanSensor
import com.curveon.data.ride.RideRecorder
import com.curveon.data.routing.CameraAlert
import com.curveon.data.routing.FuelHint
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.NavigationState
import com.curveon.data.routing.Navigator
import com.curveon.data.routing.RecentDestinations
import com.curveon.data.routing.RouteHazards
import com.curveon.data.routing.RoutePreview
import com.curveon.data.routing.RoutingStatus
import com.curveon.data.routing.SavedRoutes
import com.curveon.data.routing.Stop
import com.curveon.data.search.PlaceSearch
import com.curveon.data.search.SearchResult
import com.curveon.routing.LatLon
import com.curveon.routing.Maneuver
import com.curveon.routing.ManeuverType
import com.curveon.routing.RoadStyle
import com.curveon.routing.RouteOptions
import com.curveon.routing.Routes
import com.curveon.routing.Sun
import com.curveon.routing.SurfacePreference
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

enum class MapTheme(val label: Int) { AUTO(com.curveon.R.string.map_theme_auto), DAY(com.curveon.R.string.map_theme_day), NIGHT(com.curveon.R.string.map_theme_night) }

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Context = application
    private val db = AppDatabase.getDatabase(application)
    private val repository = CurveonRepository(db.routeDao(), db.gpxDao())
    private val rideDao = db.rideDao()
    val phoneCompanion = PhoneCompanionManager(application, viewModelScope)
    private val offlineMaps = OfflineMaps(application)
    private val navigator = Navigator(application, viewModelScope)
    private val placeSearch = PlaceSearch(application)
    private val hazards = RouteHazards(placeSearch)
    private val recentDestinations = RecentDestinations(application)
    private val settings = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** Text in the language the rider chose (the app context follows it). */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    // ---------------------------------------------------------------- map downloads

    private val mapDownloads = com.curveon.data.map.MapDownloads(application, offlineMaps)
    private var latestMapBuild: String? = null
    private val _mapStatuses = MutableStateFlow(mapDownloads.status(null))
    val mapStatuses: StateFlow<List<com.curveon.data.map.MapRegionStatus>> = _mapStatuses.asStateFlow()
    private val _mapDownload = MutableStateFlow<com.curveon.data.map.MapDownloadState>(com.curveon.data.map.MapDownloadState.Idle)
    val mapDownload: StateFlow<com.curveon.data.map.MapDownloadState> = _mapDownload.asStateFlow()
    /** Bumped when the offline map file changes so the map view reloads it. */
    private val _mapVersion = MutableStateFlow(0)
    val mapVersion: StateFlow<Int> = _mapVersion.asStateFlow()
    @Volatile private var cancelMapDownload = false

    /** Checks online (when there is a connection) whether a newer map build exists. */
    fun refreshMapStatus() = viewModelScope.launch {
        latestMapBuild = mapDownloads.latestBuild() ?: latestMapBuild
        _mapStatuses.value = mapDownloads.status(latestMapBuild)
    }

    fun downloadMap(regionId: String) {
        val region = mapDownloads.regions.firstOrNull { it.id == regionId } ?: return
        if (_mapDownload.value is com.curveon.data.map.MapDownloadState.Running) return
        cancelMapDownload = false
        _mapDownload.value = com.curveon.data.map.MapDownloadState.Running(regionId, str(R.string.map_checking_newest), 0f)
        viewModelScope.launch {
            try {
                val build = mapDownloads.latestBuild() ?: error(str(R.string.map_no_connection))
                latestMapBuild = build
                mapDownloads.download(region, build, onProgress = { msg, f ->
                    _mapDownload.value = com.curveon.data.map.MapDownloadState.Running(regionId, msg, f)
                }, isCancelled = { cancelMapDownload })
                _mapDownload.value = com.curveon.data.map.MapDownloadState.Idle
                _mapVersion.value++
            } catch (e: Throwable) {
                mapDownloads.cleanupPartial(region)
                _mapDownload.value = if (cancelMapDownload) com.curveon.data.map.MapDownloadState.Idle
                else com.curveon.data.map.MapDownloadState.Failed(str(R.string.map_download_failed, e.message ?: ""))
            }
            _mapStatuses.value = mapDownloads.status(latestMapBuild)
            _offlineMapStatus.value = describeOfflineMaps()
        }
    }

    fun cancelMapDownload() { cancelMapDownload = true }

    fun deleteMap(regionId: String) {
        val region = mapDownloads.regions.firstOrNull { it.id == regionId } ?: return
        mapDownloads.delete(region)
        _mapStatuses.value = mapDownloads.status(latestMapBuild)
        _mapVersion.value++
    }

    private val _autoZoom = MutableStateFlow(settings.getBoolean("autoZoom", true))
    val autoZoom: StateFlow<Boolean> = _autoZoom.asStateFlow()
    fun setAutoZoom(on: Boolean) { _autoZoom.value = on; settings.edit().putBoolean("autoZoom", on).apply() }

    fun setUseMph(on: Boolean) { if (_telemetry.value.useMph != on) toggleSpeedUnits() }
    fun setObd(on: Boolean) { if (_telemetry.value.isObdEnabled != on) toggleObd() }

    private val _offlineMapStatus = MutableStateFlow(describeOfflineMaps())
    val offlineMapStatus: StateFlow<String> = _offlineMapStatus.asStateFlow()

    private val _routingDataStatus = MutableStateFlow(navigator.routingData.describe())
    val routingDataStatus: StateFlow<String> = _routingDataStatus.asStateFlow()

    /** After a language change: redo the status texts that were written in the old language. */
    fun refreshTexts() {
        _routingDataStatus.value = navigator.routingData.describe()
        _offlineMapStatus.value = describeOfflineMaps()
        _gpxMessage.value = null
    }

    val savedRoutes = repository.allRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val savedGpxTracks = repository.allGpxTracks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val gpxGroups = repository.allGpxGroups.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------------------------------------------------------------- GPX overlays

    private val trackWatcher = com.curveon.data.gpx.TrackWatcher()

    /** Tracks switched on in the GPX manager (directly or by their group), decoded for the map. */
    val overlayTracks: StateFlow<List<com.curveon.data.gpx.OverlayTrack>> = combine(repository.allGpxTracks, repository.allGpxGroups) { tracks, groups ->
        val byId = groups.associateBy { it.id }
        tracks.map { it to it.groupId?.let(byId::get) }.filter { (t, g) -> GpxGroups.visible(t, g) }
    }
        .distinctUntilChanged()
        .map { list -> withContext(Dispatchers.Default) { list.map { (t, g) -> com.curveon.data.gpx.GpxOverlay.overlay(t, g) } } }
        .onEach { trackWatcher.setTracks(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _poiCategories = MutableStateFlow(
        settings.getStringSet("poiCategories", null)?.mapNotNull { runCatching { com.curveon.data.gpx.PoiCategory.valueOf(it) }.getOrNull() }?.toSet()
            ?: com.curveon.data.gpx.PoiCategory.entries.toSet()
    )
    val poiCategories: StateFlow<Set<com.curveon.data.gpx.PoiCategory>> = _poiCategories.asStateFlow()

    /** POIs of visible tracks, filtered by the categories the rider wants to see. */
    val overlayPois: StateFlow<List<com.curveon.data.gpx.GpxPoi>> = combine(overlayTracks, _poiCategories) { tracks, cats ->
        tracks.flatMap { it.pois }.filter { it.category in cats }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _trackHint = MutableStateFlow<com.curveon.data.gpx.TrackHint?>(null)
    /** Nearest visible track (line from the rider to it); null while navigating. */
    val trackHint: StateFlow<com.curveon.data.gpx.TrackHint?> = _trackHint.asStateFlow()

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
    private var roadNamesRoute: com.curveon.routing.Route? = null

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
            text = m.describe(app) +
                (road?.let { " · $it" } ?: "") +
                (then?.let { " · " + str(R.string.turn_then, it.describe(app).lowercase()) } ?: ""),
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
        navigator.onLocation(LatLon(fix.latitude, fix.longitude), (fix.accuracyMeters ?: 10f).toDouble())
        val onRoad = snapToRoute()
        _currentLocation.value = GeoPoint(onRoad?.first?.lat ?: fix.latitude, onRoad?.first?.lon ?: fix.longitude,
            fix.altitudeMeters ?: _currentLocation.value.altitudeMeters)
        val stats = tripTracker.stats
        _telemetry.value = _telemetry.value.copy(
            speedKmh = tripTracker.speedKmh,
            headingDeg = onRoad?.second ?: tripTracker.headingDeg,
            altitudeMeters = (fix.altitudeMeters ?: _telemetry.value.altitudeMeters.toDouble()).roundToInt(),
            hasGpsFix = true,
            gpsAccuracyMeters = (fix.accuracyMeters ?: 0f).roundToInt(),
            isDeadReckoning = estimated,
            tripDistanceKm = (stats.distanceMeters / 1000.0).toFloat(),
            tripDurationMinutes = (stats.movingTimeMillis / 60_000L).toInt()
        )
        recordRide(fix)
        watchTracks(fix)
    }

    /**
     * Where to draw the rider while navigating, like Google Maps: on the route line, pointing along
     * the road, as long as the rider is following it. GPS drift beside the road is ignored; the
     * bike only leaves the line once the navigator decides we're off route (a few fixes well away
     * from it, which also triggers the reroute) or when riding the wrong way along it.
     * Null means show the raw GPS position. The ride log and trip stats always use raw GPS.
     */
    private fun snapToRoute(): Pair<LatLon, Float>? {
        val p = navigator.state.value.progress ?: return null
        if (p.offRoute || p.distanceFromRoute > SNAP_MAX_M) return null
        val wrongWay = tripTracker.speedKmh >= 10f &&
            kotlin.math.abs(com.curveon.routing.Geo.angleDiff(tripTracker.headingDeg.toDouble(), p.routeBearingDeg)) > 100
        if (wrongWay) return null
        return p.snapped to p.routeBearingDeg.toFloat()
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
            if (finish) autoSaveRide(ride.copy(title = title))
        }
    }

    /** Group every finished ride is copied into as a GPX track; null = off. */
    private val _autoSaveRidesGroup = MutableStateFlow(settings.getLong("autoSaveRidesGroup", 0L).takeIf { it != 0L })
    val autoSaveRidesGroup: StateFlow<Long?> = _autoSaveRidesGroup.asStateFlow()

    /** Turns auto-saving on into [groupId], or a new group called [newGroup]; both null turns it off. */
    fun setAutoSaveRides(groupId: Long?, newGroup: String?) = viewModelScope.launch {
        val id = newGroup?.takeIf { it.isNotBlank() }?.let { repository.createGpxGroup(it.trim()) } ?: groupId
        _autoSaveRidesGroup.value = id
        settings.edit().putLong("autoSaveRidesGroup", id ?: 0L).apply()
    }

    private suspend fun autoSaveRide(ride: Ride) {
        val groupId = _autoSaveRidesGroup.value ?: return
        // The group was deleted since: stop rather than scatter rides as loose tracks.
        if (db.gpxDao().getGroupById(groupId) == null) { setAutoSaveRides(null, null); return }
        storeRideAsGpx(ride, groupId)
    }

    /** Name for a ride end: the closest place, nudged towards real towns when distances are similar. */
    private suspend fun placeName(lat: Double, lon: Double): String? {
        val bonusKm = mapOf("city" to 3.0, "town" to 2.0, "village" to 1.0, "suburb" to 0.5, "hamlet" to 0.0)
        return bonusKm.keys.mapNotNull { kind -> placeSearch.nearest(kind, lat, lon, 1).firstOrNull() }
            .filter { it.distanceKm < 10 }
            .minByOrNull { it.distanceKm - (bonusKm[it.kind] ?: 0.0) }
            ?.name
    }

    fun deleteRides(ids: Collection<Long>) = viewModelScope.launch { rideDao.delete(ids.toList()) }

    /** Keeps a ride as a track in the GPX collection, in [groupId] or a new group called [newGroup]. */
    fun saveRideToGpx(ride: Ride, groupId: Long?, newGroup: String?) = viewModelScope.launch {
        storeRideAsGpx(ride, newGroup?.takeIf { it.isNotBlank() }?.let { repository.createGpxGroup(it.trim()) } ?: groupId)
    }

    private suspend fun storeRideAsGpx(ride: Ride, group: Long?) {
        val pts = RideRecorder.parseTrack(ride.track).map { GeoPoint(it.lat, it.lon, it.ele) }
        if (pts.size < 2) return
        val name = ride.title.ifEmpty { str(R.string.ride) } + " · " +
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ride.startMs))
        repository.saveGpxTrack(
            GpxTrack(
                name = name,
                totalDistanceKm = (ride.distanceMeters / 1000).toFloat(),
                elevationGainMeters = ride.ascentMeters.roundToInt(),
                maxElevationMeters = pts.maxOf { it.altitudeMeters }.roundToInt(),
                pointCount = pts.size,
                trackPointsJson = com.curveon.data.osm.GpxParser.encodePoints(pts),
                category = "Ride",
                groupId = group
            )
        )
    }

    /** Ride a logged ride again, as a track. */
    fun rideAgain(ride: Ride) {
        val pts = RideRecorder.parseTrack(ride.track).map { LatLon(it.lat, it.lon) }
        if (pts.size >= 2) navigator.previewTrack(ride.title.ifEmpty { str(R.string.ride_earlier) }, pts, RouteOptions(RoadStyle.FASTEST, SurfacePreference.MIXED))
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

    /** Preview riding a track, in the direction set for it (or its group); [reverse] flips that. */
    fun previewGpx(track: GpxTrack, reverse: Boolean, ground: GroundPreference) {
        val inverted = GpxGroups.reversed(track, track.groupId?.let { id -> gpxGroups.value.firstOrNull { it.id == id } }) != reverse
        val pts = com.curveon.data.osm.GpxParser.decodePoints(track.trackPointsJson).map { LatLon(it.latitude, it.longitude) }
        navigator.previewTrack(track.name, if (inverted) pts.reversed() else pts,
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

    suspend fun nearest(kinds: List<String>): List<SearchResult> {
        val p = _currentLocation.value
        return placeSearch.nearest(kinds, p.latitude, p.longitude, 8)
    }

    // ---------------------------------------------------------------- GPX import

    private val _gpxMessage = MutableStateFlow<String?>(null)
    val gpxMessage: StateFlow<String?> = _gpxMessage.asStateFlow()

    private val gpxImporter = com.curveon.data.osm.GpxImporter(application, repository)

    fun importGpx(uri: Uri) {
        viewModelScope.launch {
            runCatching { gpxImporter.import(uri) }
                .onSuccess { _gpxMessage.value = if (it.size == 1) str(R.string.gpx_imported_one, it[0])
                    else getApplication<Application>().resources.getQuantityString(R.plurals.gpx_imported_tracks, it.size, it.size) }
                .onFailure { _gpxMessage.value = str(R.string.import_failed, it.message ?: "") }
        }
    }

    // Declared after gpxImporter and _gpxMessage so they exist when this runs.
    init { importGpxInbox() }

    /** GPX files copied over USB into the gpx/ folder are imported at startup. */
    private fun importGpxInbox() = viewModelScope.launch {
        val names = runCatching { gpxImporter.importInbox() }.getOrDefault(emptyList())
        if (names.isNotEmpty()) _gpxMessage.value = getApplication<Application>().resources.getQuantityString(R.plurals.gpx_imported_inbox, names.size, names.size)
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
        // Pin the palette colour so it doesn't change when other tracks come and go.
        val pinned = track.copy(color = com.curveon.data.gpx.GpxOverlay.colorOf(track))
        inGroup(pinned) { g, tracks -> GpxGroups.setTrackVisible(g, tracks, track.id, show) } ?: repository.updateGpxTrack(pinned.copy(showOnMap = show))
    }

    fun setTrackColor(track: GpxTrack, color: Int) = viewModelScope.launch {
        inGroup(track) { g, tracks -> GpxGroups.setTrackColor(g, tracks, track.id, color) } ?: repository.updateGpxTrack(track.copy(color = color))
    }

    fun setTrackReversed(track: GpxTrack, reversed: Boolean) = viewModelScope.launch {
        inGroup(track) { g, tracks -> GpxGroups.setTrackReversed(g, tracks, track.id, reversed) } ?: repository.updateGpxTrack(track.copy(reversed = reversed))
    }

    /**
     * Applies a change to one track of a group (which may turn a group override back to custom);
     * null when the track has no group.
     */
    private suspend fun inGroup(track: GpxTrack, change: (GpxGroup, List<GpxTrack>) -> Pair<GpxGroup, List<GpxTrack>>): Unit? {
        val g = track.groupId?.let { repository.gpxGroup(it) } ?: return null
        val tracks = repository.gpxTracksInGroup(g.id).map { if (it.id == track.id) track else it }
        val (g2, changed) = change(g, tracks)
        repository.updateGpxTracks(changed)
        repository.updateGpxGroup(g2)
        return Unit
    }

    fun renameTrack(track: GpxTrack, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.updateGpxTrack(track.copy(name = name.trim()))
    }

    fun moveTrack(track: GpxTrack, groupId: Long?, newGroup: String?) = viewModelScope.launch {
        val target = newGroup?.takeIf { it.isNotBlank() }?.let { repository.createGpxGroup(it.trim()) } ?: groupId
        // Keep how it looks now: its old group's overrides become its own settings.
        val old = track.groupId?.let { repository.gpxGroup(it) }
        repository.updateGpxTrack(
            track.copy(
                groupId = target,
                showOnMap = GpxGroups.visible(track, old),
                reversed = GpxGroups.reversed(track, old),
                color = GpxGroups.color(track, old)
            )
        )
    }

    fun toggleGroupVisibility(g: GpxGroup) = viewModelScope.launch {
        val tracks = repository.gpxTracksInGroup(g.id)
        val next = GpxGroups.nextVisibility(g, tracks)
        // Pin palette colours, as for a single track.
        repository.updateGpxTracks(tracks.filter { it.color == 0 }.map { it.copy(color = com.curveon.data.gpx.GpxOverlay.colorOf(it)) })
        repository.updateGpxGroup(next)
    }

    fun toggleGroupDirection(g: GpxGroup) = viewModelScope.launch {
        repository.updateGpxGroup(GpxGroups.nextDirection(g, repository.gpxTracksInGroup(g.id)))
    }

    /** [color] null: back to each track's own colour; otherwise all tracks in that colour. */
    fun setGroupColor(g: GpxGroup, color: Int?) = viewModelScope.launch {
        repository.updateGpxGroup(if (color == null) g.copy(sameColor = false) else g.copy(sameColor = true, color = color))
    }

    fun renameGroup(g: GpxGroup, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.updateGpxGroup(g.copy(name = name.trim()))
    }

    /** Ungroup keeps the tracks (looking as they do now); delete removes them too. */
    fun deleteGroup(g: GpxGroup, keepTracks: Boolean) = viewModelScope.launch {
        if (keepTracks) repository.updateGpxTracks(repository.gpxTracksInGroup(g.id).map {
            it.copy(showOnMap = GpxGroups.visible(it, g), reversed = GpxGroups.reversed(it, g), color = GpxGroups.color(it, g))
        })
        repository.deleteGpxGroup(g.id, keepTracks)
    }

    /** Writes a track, or a whole group when [track] is null, to a GPX file the rider picked. */
    fun exportGpx(group: GpxGroup?, track: GpxTrack?, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            val g = group ?: track?.groupId?.let { repository.gpxGroup(it) }
            val tracks = if (track != null) listOf(track) else repository.gpxTracksInGroup(g!!.id).sortedBy { it.name.lowercase() }
            val xml = GpxWriter.write(track?.name ?: g!!.name, tracks, g)
            getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(xml.toByteArray()) }
        }
        _gpxMessage.value = result.fold({ str(R.string.gpx_exported, track?.name ?: group?.name ?: "") }, { str(R.string.gpx_export_failed, it.message ?: "") })
    }

    fun setTrackPois(track: GpxTrack, show: Boolean) = viewModelScope.launch { repository.updateGpxTrack(track.copy(showPois = show)) }

    fun togglePoiCategory(c: com.curveon.data.gpx.PoiCategory) {
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
                    _routingDataStatus.value = if (done < total) str(R.string.routing_downloading, file, done + 1, total) else str(R.string.download_complete)
                }
            }.onFailure { _routingDataStatus.value = str(R.string.routing_download_failed, it.message ?: "") }
                .onSuccess { _routingDataStatus.value = navigator.routingData.describe() }
        }
    }

    // ---------------------------------------------------------------- offline map

    private fun describeOfflineMaps(): String {
        val maps = offlineMaps.installedMaps()
        return if (maps.isEmpty()) str(R.string.offline_map_none, offlineMaps.mapsDir)
        else maps.joinToString("\n") { str(R.string.offline_map_line, it.name, it.length() / 1e6) } + "\n" + str(R.string.offline_map_restart)
    }

    fun importOfflineMap(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                offlineMaps.import(uri) { p -> _offlineMapStatus.value = str(R.string.importing_percent, p * 100) }
            }.onFailure { _offlineMapStatus.value = str(R.string.import_failed, it.message ?: "") }
                .onSuccess { _offlineMapStatus.value = describeOfflineMaps(); _mapVersion.value++; _mapStatuses.value = mapDownloads.status(latestMapBuild) }
        }
    }

    companion object {
        val DEFAULT_LOCATION = GeoPoint(59.9139, 10.7522)
        private const val DR_START_MS = 3_000L
        /** A single fix further from the route than this is shown as it is, even before off-route kicks in. */
        private const val SNAP_MAX_M = 150.0
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

        fun Maneuver.describe(context: Context, stopName: String? = null): String = when (type) {
            ManeuverType.DEPART -> context.getString(R.string.turn_start)
            ManeuverType.STRAIGHT -> context.getString(R.string.turn_straight)
            ManeuverType.SLIGHT_LEFT -> context.getString(R.string.turn_bear_left)
            ManeuverType.LEFT -> context.getString(R.string.turn_left)
            ManeuverType.SHARP_LEFT -> context.getString(R.string.turn_sharp_left)
            ManeuverType.SLIGHT_RIGHT -> context.getString(R.string.turn_bear_right)
            ManeuverType.RIGHT -> context.getString(R.string.turn_right)
            ManeuverType.SHARP_RIGHT -> context.getString(R.string.turn_sharp_right)
            ManeuverType.KEEP_LEFT -> context.getString(R.string.turn_keep_left)
            ManeuverType.KEEP_RIGHT -> context.getString(R.string.turn_keep_right)
            ManeuverType.EXIT_LEFT -> context.getString(R.string.turn_exit_left)
            ManeuverType.EXIT_RIGHT -> context.getString(R.string.turn_exit_right)
            ManeuverType.U_TURN -> context.getString(R.string.turn_u_turn)
            ManeuverType.ROUNDABOUT -> if (exitNumber > 0) context.getString(R.string.turn_roundabout_exit, exitNumber) else context.getString(R.string.turn_roundabout)
            ManeuverType.ARRIVE -> if (stopName != null) context.getString(R.string.turn_arrive_at, stopName) else context.getString(R.string.turn_arrive)
        }
    }
}
