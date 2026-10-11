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
import com.curveon.data.location.NavTrace
import com.curveon.data.location.RiderMotion
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
    private val favoritePlaces = com.curveon.data.routing.FavoritePlaces(application)
    private val _favorites = MutableStateFlow(favoritePlaces.all())
    /** Places kept to go to again (Home first), shown at the top of Where to?. */
    val favorites: StateFlow<List<com.curveon.data.routing.Favorite>> = _favorites

    /** Keep [point] as a favourite: Home (replacing the old one) or under [name]. */
    fun saveFavorite(point: GeoPoint, name: String, home: Boolean) {
        favoritePlaces.save(com.curveon.data.routing.Favorite(name, LatLon(point.latitude, point.longitude), home))
        _favorites.value = favoritePlaces.all()
    }

    fun deleteFavorite(fav: com.curveon.data.routing.Favorite) {
        favoritePlaces.remove(fav)
        _favorites.value = favoritePlaces.all()
    }
    private val settings = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** Text in the language the rider chose (the app context follows it). */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    // ---------------------------------------------------------------- map downloads

    /** Official speed limits (Norway: NVDB) where a country publishes them; OSM's maxspeed elsewhere. */
    private val speedLimits = com.curveon.data.speed.SpeedLimits(application)
    private val mapDownloads = com.curveon.data.map.MapDownloads(application, offlineMaps, navigator.routingData, placeSearch.indexes, speedLimits)
    val mapCatalog get() = mapDownloads.catalog
    private var latestMapBuild: String? = null
    private val _installedMaps = MutableStateFlow(mapDownloads.installed(null))
    val installedMaps: StateFlow<List<com.curveon.data.map.InstalledMap>> = _installedMaps.asStateFlow()
    private val _mapDownload = MutableStateFlow<com.curveon.data.map.MapDownloadState>(com.curveon.data.map.MapDownloadState.Idle)
    val mapDownload: StateFlow<com.curveon.data.map.MapDownloadState> = _mapDownload.asStateFlow()
    /** Countries waiting their turn after the one downloading now. */
    private val _mapQueue = MutableStateFlow<List<String>>(emptyList())
    val mapQueue: StateFlow<List<String>> = _mapQueue.asStateFlow()
    /** Bumped when the offline map files change so the map view reloads them. */
    private val _mapVersion = MutableStateFlow(0)
    val mapVersion: StateFlow<Int> = _mapVersion.asStateFlow()
    @Volatile private var cancelMapDownload = false
    private var mapWorker: Job? = null

    fun freeStorageBytes() = mapDownloads.freeBytes()

    /** Download size per area, given the routing tiles on the tablet right now. */
    fun mapDownloadSizer(): (com.curveon.data.map.CatalogArea) -> Long {
        val have = navigator.routingData.installedNames()
        return { mapDownloads.downloadBytes(it, have) }
    }

    /** Continent to open the country list on: where the rider is, else Europe. */
    fun currentContinent(): String = _currentLocation.value.let { mapCatalog.areaAt(it.latitude, it.longitude)?.continent } ?: "Europe"

    /** Checks online (when there is a connection) whether a newer map build exists. */
    fun refreshMapStatus() = viewModelScope.launch {
        latestMapBuild = mapDownloads.latestBuild() ?: latestMapBuild
        mapDownloads.refreshSearch()
        updateMapStatus()
    }

    private fun updateMapStatus() {
        _installedMaps.value = mapDownloads.installed(latestMapBuild)
        _routingDataStatus.value = navigator.routingData.describe()
    }

    /** Queues a country (map and routing); downloads run one after another. */
    fun downloadMap(areaId: String) {
        val running = (_mapDownload.value as? com.curveon.data.map.MapDownloadState.Running)?.areaId
        if (areaId == running || areaId in _mapQueue.value || mapCatalog.byId[areaId] == null) return
        _mapQueue.value = _mapQueue.value + areaId
        if (mapWorker?.isActive != true) mapWorker = viewModelScope.launch { runMapQueue() }
    }

    private suspend fun runMapQueue() {
        while (true) {
            val id = _mapQueue.value.firstOrNull() ?: break
            _mapQueue.value = _mapQueue.value.drop(1)
            val area = mapCatalog.byId[id] ?: continue
            cancelMapDownload = false
            _mapDownload.value = com.curveon.data.map.MapDownloadState.Running(id, str(R.string.map_checking_newest), 0f)
            val progress = { msg: String, f: Float -> _mapDownload.value = com.curveon.data.map.MapDownloadState.Running(id, msg, f) }
            try {
                val installed = _installedMaps.value.firstOrNull { it.id == id }
                if (installed != null && installed.newerBuild == null && (installed.missingRouting.isNotEmpty() || installed.missingSearch)) {
                    // Map is current: only fetch the routing and search it lacks.
                    mapDownloads.completeMissing(area, progress, isCancelled = { cancelMapDownload })
                } else {
                    val build = mapDownloads.latestBuild() ?: error(str(R.string.map_no_connection))
                    latestMapBuild = build
                    mapDownloads.download(area, build, progress, isCancelled = { cancelMapDownload })
                    _mapVersion.value++
                }
                _mapDownload.value = com.curveon.data.map.MapDownloadState.Idle
            } catch (e: Throwable) {
                mapDownloads.cleanupPartial(id)
                if (cancelMapDownload) _mapDownload.value = com.curveon.data.map.MapDownloadState.Idle
                else {
                    _mapDownload.value = com.curveon.data.map.MapDownloadState.Failed(str(R.string.map_download_failed, e.message ?: ""))
                    _mapQueue.value = emptyList() // no connection or no space: the rest would fail too
                }
            }
            updateMapStatus()
        }
    }

    /** Stops [areaId]: the running download, or takes it out of the queue. */
    fun cancelMapDownload(areaId: String) {
        if ((_mapDownload.value as? com.curveon.data.map.MapDownloadState.Running)?.areaId == areaId) cancelMapDownload = true
        else _mapQueue.value = _mapQueue.value - areaId
    }

    fun deleteMap(id: String) {
        mapDownloads.delete(id)
        updateMapStatus()
        _mapVersion.value++
    }

    private val _autoZoom = MutableStateFlow(settings.getBoolean("autoZoom", true))
    val autoZoom: StateFlow<Boolean> = _autoZoom.asStateFlow()
    fun setAutoZoom(on: Boolean) { _autoZoom.value = on; settings.edit().putBoolean("autoZoom", on).apply() }

    /** Gravel and soft roads dashed on the map. */
    private val _showSurfaces = MutableStateFlow(settings.getBoolean("showSurfaces", true))
    val showSurfaces: StateFlow<Boolean> = _showSurfaces.asStateFlow()
    fun setShowSurfaces(on: Boolean) { _showSurfaces.value = on; settings.edit().putBoolean("showSurfaces", on).apply() }
    /** Settings → Display: the height above sea level under the speed. */
    private val _showAltitude = MutableStateFlow(settings.getBoolean("showAltitude", false))
    val showAltitude: StateFlow<Boolean> = _showAltitude.asStateFlow()
    /** The welcome tour has been shown (or skipped) once; Settings → Help brings it back. */
    val tourSeen: Boolean get() = settings.getBoolean("tourSeen", false)
    fun markTourSeen() = settings.edit().putBoolean("tourSeen", true).apply()

    fun setShowAltitude(on: Boolean) { _showAltitude.value = on; settings.edit().putBoolean("showAltitude", on).apply() }

    fun setUseMph(on: Boolean) { if (_telemetry.value.useMph != on) toggleSpeedUnits() }
    fun setObd(on: Boolean) { if (_telemetry.value.isObdEnabled != on) toggleObd() }

    // ---------------------------------------------------------------- engine (OBD2)

    val obd = com.curveon.data.obd.ObdLink(application, viewModelScope)

    /** The OBD adapter's Bluetooth address ([com.curveon.data.obd.ObdLink.DEMO] for made-up data), or null. */
    private val _obdDevice = MutableStateFlow(settings.getString("obdDevice", null))
    val obdDevice: StateFlow<String?> = _obdDevice.asStateFlow()
    fun setObdDevice(address: String?) {
        _obdDevice.value = address
        settings.edit().putString("obdDevice", address).apply()
        connectObd()
    }

    /** Which readings the engine panel shows, in this order. */
    private val _obdValues = MutableStateFlow(
        settings.getString("obdValues", null)?.split(',')?.mapNotNull { n -> com.curveon.data.obd.ObdValue.entries.firstOrNull { it.name == n } }
            ?: listOf(com.curveon.data.obd.ObdValue.FUEL, com.curveon.data.obd.ObdValue.RPM)
    )
    val obdValues: StateFlow<List<com.curveon.data.obd.ObdValue>> = _obdValues.asStateFlow()
    fun toggleObdValue(v: com.curveon.data.obd.ObdValue) {
        val now = _obdValues.value
        _obdValues.value = if (v in now) now - v else com.curveon.data.obd.ObdValue.entries.filter { it in now || it == v }
        settings.edit().putString("obdValues", _obdValues.value.joinToString(",") { it.name }).apply()
    }

    /** The adapter is only talked to while the engine panel is on. */
    private fun connectObd() = obd.use(_obdDevice.value.takeIf { _telemetry.value.isObdEnabled })

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

    private val _mapLayers = MutableStateFlow(
        // Until the rider chooses, only fuel: the first look at the map stays calm.
        settings.getStringSet("mapLayers", null)?.mapNotNull { runCatching { com.curveon.data.search.MapLayer.valueOf(it) }.getOrNull() }?.toSet()
            ?: setOf(com.curveon.data.search.MapLayer.FUEL)
    )
    /** Settings → Display → On the map: kinds of place always shown on the map. */
    val mapLayers: StateFlow<Set<com.curveon.data.search.MapLayer>> = _mapLayers.asStateFlow()
    fun setMapLayer(layer: com.curveon.data.search.MapLayer, on: Boolean) {
        _mapLayers.value = if (on) _mapLayers.value + layer else _mapLayers.value - layer
        settings.edit().putStringSet("mapLayers", _mapLayers.value.map { it.name }.toSet()).apply()
    }

    private class Viewport(val south: Double, val north: Double, val west: Double, val east: Double, val zoom: Double)
    private val _viewport = MutableStateFlow<Viewport?>(null)
    fun setViewport(south: Double, north: Double, west: Double, east: Double, zoom: Double) { _viewport.value = Viewport(south, north, west, east, zoom) }

    /** The chosen kinds of place in the part of the map in view, from the offline search of the downloaded countries. */
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mapPois: StateFlow<List<com.curveon.data.gpx.GpxPoi>> = combine(_mapLayers, _viewport) { layers, v -> layers to v }
        .debounce(250)
        .mapLatest { (layers, v) ->
            if (v == null || layers.isEmpty()) return@mapLatest emptyList()
            val app = getApplication<Application>()
            val widthKm = com.curveon.routing.Geo.distance((v.south + v.north) / 2, v.west, (v.south + v.north) / 2, v.east) / 1000
            val now = layers.filter { widthKm <= it.maxWidthKm }
            val found = mutableListOf<com.curveon.data.gpx.GpxPoi>()
            val kinds = now.flatMap { it.kinds }
            placeSearch.poisInBox(kinds, v.south, v.north, v.west, v.east).forEach { p ->
                val kind = com.curveon.data.search.MapLayer.poiKind(p.kind)
                found += com.curveon.data.gpx.GpxPoi(name = p.name, description = "", category = kind.category, lat = p.lat, lon = p.lon, kind = kind)
            }
            if (com.curveon.data.search.MapLayer.CAMERAS in now) placeSearch.camerasInBox(v.south, v.north, v.west, v.east).take(400).forEach { p ->
                val name = p.name + (p.maxSpeed?.let { " $it" } ?: "")
                found += com.curveon.data.gpx.GpxPoi(name = name, description = "", category = com.curveon.data.gpx.PoiKind.CAMERA.category, lat = p.lat, lon = p.lon,
                    kind = com.curveon.data.gpx.PoiKind.CAMERA)
            }
            found
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    /** The latest position with what the map needs to keep the rider moving smoothly until the next fix. */
    private val _riderFix = MutableStateFlow<RiderMotion.Fix?>(null)
    val riderFix: StateFlow<RiderMotion.Fix?> = _riderFix.asStateFlow()
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
            (listOfNotNull(g.start?.point) + g.stops.filterNot { it.via }.map { it.point } + g.destination).map { GeoPoint(it.lat, it.lon) }
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
        // Riding against the route (e.g. just after "Turn back"): the route starts behind the rider,
        // which has no turn of its own, so say it.
        val heading = navigator.heading()
        if (heading != null && !p.offRoute && p.distanceFromRoute < 40 && tripTracker.speedKmh > 5 &&
            kotlin.math.abs(com.curveon.routing.Geo.angleDiff(heading, p.routeBearingDeg)) > 135) {
            val u = Maneuver(ManeuverType.U_TURN, 0, p.distanceAlongRoute)
            return@combine TurnInstruction(
                index = -1, type = u.toTurnType(), text = u.describe(app), distanceMeters = 0, streetName = "",
                speedLimitKmh = p.speedLimitKmh ?: 0, location = GeoPoint(p.snapped.lat, p.snapped.lon)
            )
        }
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
                nextStop = s.stopsAlong.firstOrNull { !it.first.via }?.let { (stop, along) -> stop.name to (along - p.distanceAlongRoute) / 1000.0 }
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

    private val _screenLock = MutableStateFlow(com.curveon.ScreenLock.valueOf(settings.getString("screenLock", com.curveon.ScreenLock.AUTO.name)!!))
    val screenLock: StateFlow<com.curveon.ScreenLock> = _screenLock.asStateFlow()
    /** The exact orientation to ask for (an ActivityInfo constant), worked out when the lock was chosen. */
    private val _screenOrientation = MutableStateFlow(settings.getInt("screenOrientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER))
    val screenOrientation: StateFlow<Int> = _screenOrientation.asStateFlow()

    fun setScreenLock(lock: com.curveon.ScreenLock) {
        val orientation = com.curveon.ScreenLock.orientationFor(app, lock)
        _screenLock.value = lock
        _screenOrientation.value = orientation
        settings.edit().putString("screenLock", lock.name).putInt("screenOrientation", orientation).apply()
    }

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
        NavTrace.start(application)
        updateMapDark()
        startDeadReckoning()
        leanSensor.start()

        viewModelScope.launch { while (true) { delay(60_000); updateMapDark() } }

        // Speed limit while navigating: the official one of the road the route is on, else the route's (OSM).
        viewModelScope.launch {
            navigator.state.map { s ->
                val p = s.progress ?: return@map 0
                withContext(Dispatchers.IO) { speedLimits.at(p.snapped.lat, p.snapped.lon, p.routeBearingDeg) } ?: p.speedLimitKmh ?: 0
            }.distinctUntilChanged().collect { limit ->
                if (navigator.state.value.isNavigating) _telemetry.value = _telemetry.value.copy(speedLimitKmh = limit)
            }
        }
        // Speed limits of the countries we have maps of and the one we are in (maps or not), as soon
        // as we are online; new versions are looked for every few hours.
        viewModelScope.launch {
            delay(20_000)
            var lastIds = emptySet<String>()
            var lastCheck = 0L
            while (true) {
                val here = if (_telemetry.value.hasGpsFix) _currentLocation.value.let { mapCatalog.areaAt(it.latitude, it.longitude)?.id } else null
                val ids = offlineMaps.installedMaps().map { it.nameWithoutExtension }.toSet() + listOfNotNull(here)
                val now = System.currentTimeMillis()
                if (ids.isNotEmpty() && (ids != lastIds || now - lastCheck > 6 * 3_600_000L)) {
                    speedLimits.available()?.let { online ->
                        speedLimits.update(ids, online)
                        lastIds = ids
                        lastCheck = now
                    }
                }
                delay(60_000)
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
                else withContext(Dispatchers.IO) { speedLimits.at(here.lat, here.lon, t.headingDeg.toDouble()) }
                    ?: navigator.probeSpeedLimit(here, t.headingDeg.toDouble()) ?: 0
                _telemetry.value = _telemetry.value.copy(speedLimitKmh = limit)
                _cameraAlert.value = if (t.speedKmh < 15f) null else hazards.cameraAheadFreeRide(here, t.headingDeg.toDouble())
            }
        }
        // New route: load fuel and cameras along it; forget road names of the old one.
        viewModelScope.launch {
            navigator.state.map { it.route }.distinctUntilChanged().collect { route ->
                if (route != null) NavTrace.route(route.points.map { LatLon(it.lat, it.lon) }) else NavTrace.event("route cleared")
                hazards.load(route)
                if (route == null) stopDemoRide()
                else if (_demoMode.value) {
                    // A re-plan of the trip being ridden carries on from here; another trip begins at its start.
                    val title = navigator.state.value.goal?.title
                    startDemoRide(newTrip = !_isDemoRiding.value || title != demoTrip)
                    demoTrip = title
                }
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
        obd.use(null)
        leanSensor.stop()
        NavTrace.flush()
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
        NavTrace.fix(fix, locationSource.satellitesUsed, estimated)
        tripTracker.onFix(fix)
        navigator.onLocation(LatLon(fix.latitude, fix.longitude), (fix.accuracyMeters ?: 10f).toDouble(),
            tripTracker.headingDeg.toDouble().takeIf { tripTracker.speedKmh > 5 })
        val onRoad = snapToRoute(fix)
        val nav = navigator.state.value
        nav.progress?.let { p ->
            NavTrace.match(onRoad != null, p.distanceFromRoute, p.routeBearingDeg, p.distanceAlongRoute, p.offRoute, tripTracker.headingDeg)
        }
        _currentLocation.value = GeoPoint(onRoad?.first?.lat ?: fix.latitude, onRoad?.first?.lon ?: fix.longitude,
            fix.altitudeMeters ?: _currentLocation.value.altitudeMeters)
        val heading = onRoad?.second ?: tripTracker.headingDeg
        _riderFix.value = RiderMotion.Fix(
            lat = _currentLocation.value.latitude, lon = _currentLocation.value.longitude,
            headingDeg = heading.toDouble(), speedMps = tripTracker.speedKmh / 3.6,
            atNanos = fix.atNanos,
            route = if (onRoad != null) nav.route else null,
            along = if (onRoad != null) nav.progress?.distanceAlongRoute else null
        )
        val stats = tripTracker.stats
        _telemetry.value = _telemetry.value.copy(
            speedKmh = tripTracker.speedKmh,
            headingDeg = heading,
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

    /** Whether the rider is drawn on the route line right now (see [snapToRoute]). */
    private var drawnOnRoute = false

    /**
     * Where to draw the rider while navigating, like Google Maps: on the route line, pointing along
     * the road, as long as the rider is following it. GPS drift beside the road is ignored. Taking
     * another road shows at once: a few metres off the line while heading away from it is enough
     * to let go (the navigator itself waits longer before calling it off route and rerouting).
     * Null means show the raw GPS position. The ride log and trip stats always use raw GPS.
     */
    private fun snapToRoute(fix: GpsFix): Pair<LatLon, Float>? {
        val p = navigator.state.value.progress
        if (p == null || p.offRoute) { drawnOnRoute = false; return null }
        val speed = tripTracker.speedKmh
        val away = kotlin.math.abs(com.curveon.routing.Geo.angleDiff(tripTracker.headingDeg.toDouble(), p.routeBearingDeg))
        val acc = (fix.accuracyMeters ?: 10f).toDouble()
        val moving = speed >= 10f
        drawnOnRoute = if (drawnOnRoute) {
            val leave = p.distanceFromRoute > maxOf(SNAP_LEAVE_M, acc * 1.5) ||
                (moving && away > 100) || // riding the wrong way along it
                (moving && p.distanceFromRoute > SNAP_LEAVE_TURNING_M && away > 35)
            !leave
        } else {
            p.distanceFromRoute < maxOf(SNAP_JOIN_M, acc) && (!moving || away < 30)
        }
        return if (drawnOnRoute) p.snapped to p.routeBearingDeg.toFloat() else null
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
    private suspend fun placeName(lat: Double, lon: Double): String? = nearestPlace(lat, lon)?.name

    private suspend fun nearestPlace(lat: Double, lon: Double): SearchResult? {
        val bonusKm = mapOf("city" to 3.0, "town" to 2.0, "village" to 1.0, "suburb" to 0.5, "hamlet" to 0.0)
        return bonusKm.keys.mapNotNull { kind -> placeSearch.nearest(kind, lat, lon, 1).firstOrNull() }
            .filter { it.distanceKm < 10 }
            .minByOrNull { it.distanceKm - (bonusKm[it.kind] ?: 0.0) }
    }

    /** Gravel and soft roads for the map, read off the main thread. */
    suspend fun surfaceWays(south: Double, west: Double, north: Double, east: Double) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { navigator.surfaceWays(south, west, north, east) }

    /** A name for a point picked on the map: "Voss", "Near Voss", or "Map point" out in the wild. */
    suspend fun describePoint(point: GeoPoint): String {
        val p = runCatching { nearestPlace(point.latitude, point.longitude) }.getOrNull() ?: return str(R.string.map_point)
        return if (p.distanceKm < 1.5) p.name else str(R.string.near_place, p.name)
    }

    /** What to call the previewed or active route when saving it, e.g. "Voss – Bergen" or "Loop from Voss, 120 km". */
    suspend fun suggestedRouteName(): String {
        val s = navigator.state.value
        val goal = s.preview?.goal ?: s.goal ?: return ""
        val route = s.preview?.selectedRoute ?: s.route
        val here = _currentLocation.value
        // Points picked on the map are called "Map point" / "Near X": name them after the place instead.
        val near = str(R.string.near_place, "")
        suspend fun named(s: Stop) = if (s.name == str(R.string.map_point) || s.name.startsWith(near)) placeName(s.point.lat, s.point.lon) else s.name
        return when (goal) {
            is NavigationGoal.Destination -> {
                goal.saved?.let { return it.title }
                if (goal.loop != null) {
                    val km = ((route?.distanceMeters ?: 0.0) / 1000).roundToInt()
                    return named(Stop(goal.name, goal.destination))?.let { str(R.string.loop_name_from, it, km) } ?: str(R.string.loop_name, km)
                }
                val from = goal.start?.let { named(it) } ?: route?.points?.firstOrNull()?.let { placeName(it.lat, it.lon) }
                    ?: placeName(here.latitude, here.longitude)
                val to = named(Stop(goal.name, goal.destination)) ?: goal.name
                if (from == null || from == to) to else "$from – $to"
            }
            is NavigationGoal.Roundtrip -> {
                val from = route?.points?.firstOrNull()?.let { placeName(it.lat, it.lon) } ?: placeName(here.latitude, here.longitude)
                val km = ((route?.distanceMeters ?: goal.distanceKm * 1000) / 1000).roundToInt()
                if (from != null) str(R.string.loop_name_from, from, km) else str(R.string.loop_name, km)
            }
            is NavigationGoal.Track -> goal.name
        }
    }

    fun deleteRides(ids: Collection<Long>) = viewModelScope.launch { rideDao.delete(ids.toList()) }

    /** Keeps a ride as a track in the GPX collection, in [groupId] or a new group called [newGroup]. */
    fun saveRideToGpx(ride: Ride, groupId: Long?, newGroup: String?) = viewModelScope.launch {
        storeRideAsGpx(ride, newGroup?.takeIf { it.isNotBlank() }?.let { repository.createGpxGroup(it.trim()) } ?: groupId)
    }

    private suspend fun storeRideAsGpx(ride: Ride, group: Long?) {
        // Each point keeps when it was ridden, so the GPX lines up with a video of the ride.
        val pts = RideRecorder.parseTrack(ride.track).map { GeoPoint(it.lat, it.lon, it.ele, ride.startMs + (it.secondsFromStart * 1000).toLong()) }
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
    private var demoTrip: String? = null
    private val _isDemoRiding = MutableStateFlow(false)
    val isDemoRiding: StateFlow<Boolean> = _isDemoRiding.asStateFlow()

    /** Demo mode (Settings): every trip that starts is ridden as a simulation instead of on GPS. */
    private val _demoMode = MutableStateFlow(settings.getBoolean("demoMode", false))
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    fun setDemoMode(on: Boolean) {
        _demoMode.value = on
        settings.edit().putBoolean("demoMode", on).apply()
        if (on) { if (navigator.state.value.route != null) { demoTrip = navigator.state.value.goal?.title; startDemoRide(newTrip = true) } } else stopDemoRide()
    }

    private fun stopDemoRide() {
        if (demoJob?.isActive != true) return
        demoJob?.cancel()
        _isDemoRiding.value = false
        startGps()
    }

    /** Replaces real GPS with a simulated ride along the active route; [newTrip] begins at the trip's own start. */
    private fun startDemoRide(newTrip: Boolean, speedFactor: Double = 1.0) {
        demoJob?.cancel()
        val nav = navigator.state.value
        val route = nav.route ?: return
        // A trip planned from its own start: ride it from there, not from where the tablet is.
        val startAt = if (newTrip) (nav.goal as? NavigationGoal.Track)?.points?.firstOrNull() else LatLon(_currentLocation.value.latitude, _currentLocation.value.longitude)
        gpsJob?.cancel()
        _isDemoRiding.value = true
        demoJob = viewModelScope.launch {
            DemoRide.fixes(route, speedFactor, startAt = startAt).collect(::onGpsFix)
            _isDemoRiding.value = false
            startGps()
        }
    }

    fun resetTrip() {
        tripTracker.reset()
        _telemetry.value = _telemetry.value.copy(tripDistanceKm = 0f, tripDurationMinutes = 0)
    }

    private var portraitNow = false

    /** Full map or dashboard is remembered per orientation: [restore] takes the one last used in this orientation. */
    fun setOrientation(portrait: Boolean, restore: Boolean = true) {
        portraitNow = portrait
        if (restore) _isFullScreenMap.value = settings.getBoolean(fullMapKey(), false)
    }

    private fun fullMapKey() = if (portraitNow) "fullMapPortrait" else "fullMapLandscape"

    /** The rider's own choice (the rail button) is what is remembered. */
    fun toggleFullScreenMap() {
        _isFullScreenMap.value = !_isFullScreenMap.value
        settings.edit().putBoolean(fullMapKey(), _isFullScreenMap.value).apply()
    }

    fun setFullScreenMap(on: Boolean) { _isFullScreenMap.value = on }

    fun toggleSpeedUnits() {
        _telemetry.value = _telemetry.value.copy(useMph = !_telemetry.value.useMph)
        settings.edit().putBoolean("mph", _telemetry.value.useMph).apply()
    }

    fun toggleObd() {
        _telemetry.value = _telemetry.value.copy(isObdEnabled = !_telemetry.value.isObdEnabled)
        settings.edit().putBoolean("obd", _telemetry.value.isObdEnabled).apply()
        connectObd()
    }

    // ---------------------------------------------------------------- planning

    /** Last surface/avoid choices, reused for the next preview. */
    private var lastOptions = RouteOptions(
        RoadStyle.valueOf(settings.getString("style", RoadStyle.CURVY.name)!!),
        SurfacePreference.valueOf(settings.getString("surface", SurfacePreference.PAVED.name)!!),
        avoidFerries = settings.getBoolean("avoidFerries", false),
        avoidMotorways = settings.getBoolean("avoidMotorways", false),
        avoidTrunk = settings.getBoolean("avoidTrunk", false),
        gravelReachKm = settings.getInt("gravelReachKm", 0).takeIf { it > 0 }
    ).also { navigator.preferredStyle = it.roadStyle }

    /** Preview a trip to [point], from [start] (null = from where I am). */
    fun previewDestination(point: GeoPoint, name: String, start: GeoPoint? = null, startName: String = "") {
        navigator.previewDestination(name, LatLon(point.latitude, point.longitude), lastOptions,
            start = start?.let { Stop(startName, LatLon(it.latitude, it.longitude)) })
    }

    /** Plan the previewed trip from [point] (null = from where I am). */
    fun setPreviewStart(point: GeoPoint?, name: String) =
        navigator.setPreviewStart(point?.let { Stop(name, LatLon(it.latitude, it.longitude)) })

    /**
     * Point by point on the map: the planned trip rides on to [point], its end so far becoming a
     * point on the way; with no trip planned yet, a trip to [point] (from [start], or from here).
     */
    fun planPointByPoint(point: GeoPoint, name: String, start: GeoPoint? = null, startName: String = "", keepEndAsStop: Boolean = false) {
        if (navigator.state.value.preview?.goal is NavigationGoal.Destination) navigator.extendPreview(Stop(name, LatLon(point.latitude, point.longitude)), keepEndAsStop)
        else previewDestination(point, name, start, startName)
    }

    fun setPreviewDestination(point: GeoPoint, name: String) = navigator.setPreviewDestination(Stop(name, LatLon(point.latitude, point.longitude)))

    fun movePreviewStop(index: Int, delta: Int) = navigator.movePreviewStop(index, delta)

    /** Remove a stop from the active trip (re-plans from here). */
    fun removeStop(index: Int) = navigator.removeStop(index)

    // Wrong turn: the rider's answer
    val wrongTurn: StateFlow<com.curveon.data.routing.WrongTurn?> =
        navigator.state.map { it.wrongTurn }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun turnBack() = navigator.turnBack()
    fun newRouteFromHere(keepStops: Boolean) = navigator.newRouteFromHere(keepStops)
    fun rejoinAhead() = navigator.rejoinAhead()

    private val _offRouteSettings = MutableStateFlow(
        com.curveon.data.routing.OffRouteSettings(
            offRouteMeters = settings.getInt("offRouteMeters", 40),
            maxExtraMinutes = settings.getInt("offRouteExtraMin", 3),
            maxExtraPercent = settings.getInt("offRouteExtraPct", 10)
        ).also { navigator.offRouteSettings = it }
    )
    val offRouteSettings: StateFlow<com.curveon.data.routing.OffRouteSettings> = _offRouteSettings.asStateFlow()
    fun setOffRouteSettings(s: com.curveon.data.routing.OffRouteSettings) {
        _offRouteSettings.value = s
        navigator.offRouteSettings = s
        settings.edit().putInt("offRouteMeters", s.offRouteMeters).putInt("offRouteExtraMin", s.maxExtraMinutes)
            .putInt("offRouteExtraPct", s.maxExtraPercent).apply()
    }

    /**
     * A loop inside [area] (drawn on the map), from [start] or from here, with the last route
     * options; its shape is found on curvy roads (the sheet then offers every style through it).
     */
    fun previewAreaRoundtrip(
        area: List<GeoPoint>, start: GeoPoint? = null, startName: String = "",
        maxKm: Int? = null, surface: SurfacePreference = lastOptions.surface, gravelReachKm: Int? = lastOptions.gravelReachKm
    ) = navigator.previewAreaRoundtrip(
        area.map { LatLon(it.latitude, it.longitude) },
        lastOptions.copy(roadStyle = if (lastOptions.roadStyle == RoadStyle.FASTEST) RoadStyle.CURVY else lastOptions.roadStyle,
            surface = surface, gravelReachKm = gravelReachKm),
        start?.let { Stop(startName, LatLon(it.latitude, it.longitude)) },
        maxKm
    )

    /** The surface last planned with: what a new loop starts with. */
    fun lastSurface(): SurfacePreference = lastOptions.surface

    /** How far off the ordinary road gravel was last allowed to go (null = any distance). */
    fun lastGravelReach(): Int? = lastOptions.gravelReachKm

    /** The previewed loop again, no longer than [maxKm] (null = round the whole area). */
    fun setLoopMax(maxKm: Int?) = navigator.setLoopMax(maxKm)

    /** Preview riding a track, in the direction set for it (or its group); [reverse] flips that. */
    fun previewGpx(track: GpxTrack, reverse: Boolean, ground: GroundPreference) {
        val inverted = GpxGroups.reversed(track, track.groupId?.let { id -> gpxGroups.value.firstOrNull { it.id == id } }) != reverse
        val pts = com.curveon.data.osm.GpxParser.decodePoints(track.trackPointsJson).map { LatLon(it.latitude, it.longitude) }
        navigator.previewTrack(track.name, if (inverted) pts.reversed() else pts,
            RouteOptions(RoadStyle.FASTEST, SurfacePreference.entries[ground.ordinal]))
    }

    fun previewSaved(item: RouteItem) {
        when (val g = SavedRoutes.toGoal(item)) {
            is NavigationGoal.Destination -> navigator.previewPlanned(g)
            is NavigationGoal.Track -> navigator.previewTrack(g.name, g.points, g.options)
            is NavigationGoal.Roundtrip -> Unit // saved loops are stored as tracks
        }
    }

    fun selectCandidate(index: Int) = navigator.selectCandidate(index)

    fun setPreviewSurface(surface: SurfacePreference) = navigator.updatePreviewOptions { it.copy(surface = surface) }

    /** Gravel/Offroad: how far off the ordinary route for gravel (null = any distance). */
    fun setPreviewGravelReach(km: Int?) = navigator.updatePreviewOptions { it.copy(gravelReachKm = km) }

    fun togglePreviewAvoidFerries() = navigator.updatePreviewOptions { it.copy(avoidFerries = !it.avoidFerries) }

    /** Motorways OK / no motorways / no motorways or E-roads (trunk roads). */
    fun setPreviewMotorways(avoid: Boolean, trunk: Boolean) = navigator.updatePreviewOptions { it.copy(avoidMotorways = avoid, avoidTrunk = avoid && trunk) }

    fun removePreviewStop(index: Int) = navigator.removePreviewStop(index)

    fun undoPreviewEdit() = navigator.undoPreviewEdit()

    /** Put a stop at [index] in the previewed trip; [via] for a point the route was dragged through. */
    fun insertPreviewStop(index: Int, point: GeoPoint, name: String, via: Boolean = false) =
        navigator.insertPreviewStop(index, Stop(name, LatLon(point.latitude, point.longitude), via))

    /** Route through both ends of a part of the previewed trip marked for changing, so only that part moves. */
    fun pinPreviewSection(fromIndex: Int, from: GeoPoint, fromName: String, toIndex: Int, to: GeoPoint, toName: String) =
        navigator.insertPreviewStops(listOf(
            fromIndex to Stop(fromName, LatLon(from.latitude, from.longitude), via = true),
            toIndex to Stop(toName, LatLon(to.latitude, to.longitude), via = true)
        ))

    /** Move the previewed stop at [index] to [point] (keeps being a via point if it was one). */
    fun replacePreviewStop(index: Int, point: GeoPoint, name: String, via: Boolean = false) =
        navigator.replacePreviewStop(index, Stop(name, LatLon(point.latitude, point.longitude), via))

    fun shuffleRoundtrip() = navigator.shuffleRoundtrip()

    fun reversePreviewTrack() = navigator.reversePreviewTrack()

    fun cancelPreview() = navigator.cancelPreview()

    /** The previewed trip ends at its last stop instead; with no stops there is no trip left. */
    fun removePreviewDestination() {
        val g = navigator.state.value.preview?.goal as? NavigationGoal.Destination ?: return
        if (g.stops.isEmpty()) navigator.cancelPreview() else navigator.removePreviewDestination()
    }

    fun startPreview() {
        val p = navigator.state.value.preview ?: return
        val style = p.candidates.getOrNull(p.selected)?.style
        lastOptions = p.goal.options.copy(roadStyle = style ?: lastOptions.roadStyle)
        settings.edit()
            .putString("style", lastOptions.roadStyle.name).putString("surface", lastOptions.surface.name)
            .putBoolean("avoidFerries", lastOptions.avoidFerries).putBoolean("avoidMotorways", lastOptions.avoidMotorways)
            .putBoolean("avoidTrunk", lastOptions.avoidTrunk)
            .putInt("gravelReachKm", lastOptions.gravelReachKm ?: 0)
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
        // Saved again under its own name: the saved route is updated rather than copied.
        val item = SavedRoutes.toItem(g, route, name).let { item ->
            (g as? NavigationGoal.Destination)?.saved?.takeIf { it.title == name }?.let { item.copy(id = it.id) } ?: item
        }
        viewModelScope.launch { repository.saveRoute(item) }
    }

    /**
     * Saves the previewed (or active) route's line as a GPX track under [name], in an existing
     * group, a new one ([newGroup]) or none; its stops and destination come along as waypoints.
     */
    fun saveRouteToGpx(name: String, groupId: Long?, newGroup: String?) {
        val s = navigator.state.value
        val route = s.preview?.selectedRoute ?: s.route ?: return
        val goal = s.preview?.goal ?: s.goal
        val pts = route.points.map { GeoPoint(it.lat, it.lon, it.elevation) }
        if (pts.size < 2) return
        val places = (goal as? NavigationGoal.Destination)?.let { g ->
            g.stops.filter { !it.via }.map { it.name to it.point } + (g.name to g.destination)
        }.orEmpty()
        viewModelScope.launch {
            val created = newGroup?.takeIf { it.isNotBlank() }?.let { repository.createGpxGroup(it.trim()) }
            // A group made here is where the next route goes too.
            created?.let { settings.edit().putLong("saveRouteGpxGroup", it).apply() }
            val group = created ?: groupId
            repository.saveGpxTrack(
                GpxTrack(
                    name = name,
                    totalDistanceKm = (route.distanceMeters / 1000).toFloat(),
                    elevationGainMeters = route.ascentMeters.roundToInt(),
                    maxElevationMeters = pts.maxOf { it.altitudeMeters }.roundToInt(),
                    pointCount = pts.size,
                    trackPointsJson = com.curveon.data.osm.GpxParser.encodePoints(pts),
                    waypointsJson = com.curveon.data.gpx.GpxOverlay.encodePois(places.map { (n, p) ->
                        com.curveon.data.osm.GpxParser.GpxWaypoint(n, GeoPoint(p.lat, p.lon))
                    }),
                    category = "Route",
                    groupId = group
                )
            )
        }
    }

    /** Where routes were saved last time (Where to?, GPX, the GPX group), so the save dialog starts there. */
    val saveRouteTargets: Triple<Boolean, Boolean, Long?>
        get() = Triple(
            settings.getBoolean("saveRouteToRoutes", true),
            settings.getBoolean("saveRouteToGpx", false),
            settings.getLong("saveRouteGpxGroup", -1L).takeIf { it >= 0 }
        )

    fun rememberSaveRouteTargets(toRoutes: Boolean, toGpx: Boolean, groupId: Long?) = settings.edit()
        .putBoolean("saveRouteToRoutes", toRoutes).putBoolean("saveRouteToGpx", toGpx)
        .putLong("saveRouteGpxGroup", groupId ?: -1L).apply()

    fun deleteSavedRoute(item: RouteItem) = viewModelScope.launch { repository.deleteRoute(item.id) }

    fun recents(): List<Stop> = recentDestinations.all()

    /** Add a stop to the active route (or to the preview). */
    fun addStop(point: GeoPoint, name: String) = navigator.addStop(Stop(name, LatLon(point.latitude, point.longitude)))

    fun stopNavigation() = navigator.stop()

    fun dismissRoutingError() = navigator.dismissError()

    // ---------------------------------------------------------------- search

    fun searchStatus(): String = placeSearch.describe()

    /** The road at [point] (a finger held on the map): name from the map ([mapName]), the rest from the routing and speed data. */
    suspend fun roadAt(point: GeoPoint, mapName: String?): com.curveon.data.routing.RoadInfo? {
        val p = LatLon(point.latitude, point.longitude)
        val road = navigator.roadAt(p)
        val official = withContext(Dispatchers.IO) { speedLimits.at(p.lat, p.lon, null) }
        if (road == null && official == null && mapName == null) return null
        return com.curveon.data.routing.RoadInfo(mapName, road?.highway, road?.surface, road?.tracktype, road?.unpaved ?: false,
            official ?: road?.maxSpeedKmh, official != null)
    }

    /** Settings → Maps & data: where the speed limits come from, per country (credits the licences ask for). */
    fun speedLimitsStatus(): String = speedLimits.sources().let { s ->
        if (s.isEmpty()) str(R.string.speed_limits_osm)
        else s.joinToString("\n") { (id, src) -> "${mapDownloads.catalog.byId[id]?.displayName(java.util.Locale.getDefault()) ?: id}: $src" } + "\n" + str(R.string.speed_limits_elsewhere)
    }

    fun searchPlaces(query: String) =
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
    init {
        importGpxInbox(); connectObd()
        navigator.describePlace = { describePoint(GeoPoint(it.lat, it.lon)) }
    }

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
                .onSuccess { _offlineMapStatus.value = describeOfflineMaps(); _mapVersion.value++; updateMapStatus() }
        }
    }

    companion object {
        val DEFAULT_LOCATION = GeoPoint(59.9139, 10.7522)
        private const val DR_START_MS = 3_000L
        /** A single fix further from the route than this is shown as it is, even before off-route kicks in. */
        private const val SNAP_LEAVE_M = 20.0
        private const val SNAP_LEAVE_TURNING_M = 7.0
        private const val SNAP_JOIN_M = 12.0
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
