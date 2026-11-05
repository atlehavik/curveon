package com.curveon.ui.viewmodel

import android.app.Application
import android.net.Uri
import com.curveon.R
import com.curveon.data.map.MapDownloads
import com.curveon.data.map.OfflineMaps
import com.curveon.data.model.GeoPoint
import com.curveon.data.routing.RoutingData
import com.curveon.data.search.SearchIndexes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The maps on the device (Settings → Maps & data): countries downloaded one after another with their routing and
 * search, search data for where the rider is fetched without the map, a map file imported by hand, and what is
 * installed and up to date.
 */
class MapDownloadController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val mapDownloads: MapDownloads,
    private val offlineMaps: OfflineMaps,
    private val routingData: RoutingData,
    private val searchIndexes: SearchIndexes,
    /** Where the rider is ([DashboardViewModel.DEFAULT_LOCATION] before the first fix). */
    private val location: () -> GeoPoint,
) {

    /** Text in the language the rider chose (the app context follows it). */
    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private val mapCatalog get() = mapDownloads.catalog

    // ---------------------------------------------------------------- map downloads

    private var latestMapBuild: String? = null
    private val _installedMaps = MutableStateFlow(mapDownloads.installed(null))
    val installedMaps: StateFlow<List<com.curveon.data.map.InstalledMap>> = _installedMaps.asStateFlow()
    private val _mapDownload = MutableStateFlow<com.curveon.data.map.MapDownloadState>(com.curveon.data.map.MapDownloadState.Idle)
    /** The map downloading now, without the finishing step of the one before it (see [mapDownload]). */
    val downloadingNow: StateFlow<com.curveon.data.map.MapDownloadState> = _mapDownload.asStateFlow()
    /** A country whose map is in place and whose routing and search are being fetched while the next map downloads. */
    private val _mapFinishing = MutableStateFlow<com.curveon.data.map.MapDownloadState.Running?>(null)
    val mapDownload: StateFlow<com.curveon.data.map.MapDownloadState> = combine(_mapDownload, _mapFinishing) { d, f ->
        when {
            f == null || d is com.curveon.data.map.MapDownloadState.Failed -> d
            d is com.curveon.data.map.MapDownloadState.Running -> d.copy(finishing = f)
            else -> f
        }
    }.stateIn(scope, SharingStarted.Eagerly, com.curveon.data.map.MapDownloadState.Idle)
    /** Countries waiting their turn after the one downloading now. */
    private val _mapQueue = MutableStateFlow<List<String>>(emptyList())
    val mapQueue: StateFlow<List<String>> = _mapQueue.asStateFlow()
    /** Bumped when the offline map files change so the map view reloads them. */
    private val _mapVersion = MutableStateFlow(0)
    val mapVersion: StateFlow<Int> = _mapVersion.asStateFlow()
    @Volatile private var cancelMapDownload = false
    @Volatile private var cancelMapFinish = false
    /** A finishing step that failed while a map was downloading; shown when the queue is done. */
    @Volatile private var deferredFailure: String? = null
    private var mapWorker: Job? = null
    private var mapFinisher: Job? = null

    private val _offlineMapStatus = MutableStateFlow(describeOfflineMaps())
    val offlineMapStatus: StateFlow<String> = _offlineMapStatus.asStateFlow()

    private val _routingDataStatus = MutableStateFlow(routingData.describe())
    val routingDataStatus: StateFlow<String> = _routingDataStatus.asStateFlow()

    /** After a language change: redo the status texts that were written in the old language. */
    fun refreshTexts() {
        _routingDataStatus.value = routingData.describe()
        _offlineMapStatus.value = describeOfflineMaps()
    }

    // ---------------------------------------------------------------- search data without the map

    private val _searchOffer = MutableStateFlow<com.curveon.data.search.SearchOffer?>(null)
    /** Search data for where the rider is that can be fetched without downloading the map (see [checkSearchOffer]). */
    val searchOffer: StateFlow<com.curveon.data.search.SearchOffer?> = _searchOffer.asStateFlow()
    private var searchOfferJob: Job? = null
    /** Countries already fetched by themselves this time the app runs: once, so a failure doesn't make a loop. */
    private val autoSearchTried = mutableSetOf<String>()

    /**
     * Is there a search index for where the rider is that isn't on the tablet but is small enough to
     * offer? Called when Where to? opens and at the first GPS fix. It is fetched by itself (once per run, so a
     * failure doesn't make a loop); the card in Where to? shows how far it is, or lets the rider try again.
     */
    fun checkSearchOffer() {
        if (searchOfferJob?.isActive == true) return
        val here = location()
        if (here == DashboardViewModel.DEFAULT_LOCATION) return
        scope.launch {
            val area = withContext(Dispatchers.Default) { mapCatalog.indexAreaAt(here.latitude, here.longitude) }
            if (area == null || searchIndexes.source(area.id) != com.curveon.data.search.SearchIndexes.Source.NONE) { _searchOffer.value = null; return@launch }
            withContext(Dispatchers.IO) { if (mapDownloads.searchBytes(area.id) == null) mapDownloads.refreshSearch() }
            val bytes = mapDownloads.searchBytes(area.id)
            if (bytes == null || bytes > SEARCH_OFFER_MAX_BYTES) { _searchOffer.value = null; return@launch }
            if (_searchOffer.value?.areaId != area.id) _searchOffer.value = com.curveon.data.search.SearchOffer(area.id, area.displayName(java.util.Locale.getDefault()), bytes)
            if (autoSearchTried.add(area.id)) downloadSearchIndex()
        }
    }

    /** Fetches the offered search index. */
    fun downloadSearchIndex() {
        val offer = _searchOffer.value ?: return
        if (searchOfferJob?.isActive == true) return
        searchOfferJob = scope.launch {
            _searchOffer.value = offer.copy(progress = str(R.string.search_offer_starting), fraction = 0f, failed = false)
            try {
                searchIndexes.fetch(offer.areaId, { msg, f -> _searchOffer.value = offer.copy(progress = msg, fraction = f) }, isCancelled = { false })
                _searchOffer.value = null
                updateMapStatus()
            } catch (e: Throwable) {
                searchIndexes.cleanupPartial(offer.areaId)
                _searchOffer.value = offer.copy(progress = str(R.string.search_download_failed, e.message ?: ""), failed = true)
            }
        }
    }

    /** Checks online (when there is a connection) whether a newer map build exists. */
    fun refreshMapStatus() = scope.launch {
        latestMapBuild = mapDownloads.latestBuild() ?: latestMapBuild
        mapDownloads.refreshSearch()
        updateMapStatus()
    }

    private fun updateMapStatus() {
        _installedMaps.value = mapDownloads.installed(latestMapBuild)
        _routingDataStatus.value = routingData.describe()
    }

    /**
     * Queues a country (map and routing). Maps download one after another; a finished map's routing
     * and search are fetched/built meanwhile, so the next map doesn't wait for them.
     */
    fun downloadMap(areaId: String) {
        val running = (_mapDownload.value as? com.curveon.data.map.MapDownloadState.Running)?.areaId
        if (areaId == running || areaId == _mapFinishing.value?.areaId || areaId in _mapQueue.value || mapCatalog.byId[areaId] == null) return
        _mapQueue.update { it + areaId }
        if (mapWorker?.isActive != true) mapWorker = scope.launch { runMapQueue() }
    }

    private suspend fun runMapQueue() {
        while (true) {
            val id = _mapQueue.value.firstOrNull() ?: break
            _mapQueue.update { it.drop(1) }
            val area = mapCatalog.byId[id] ?: continue
            cancelMapDownload = false
            _mapDownload.value = com.curveon.data.map.MapDownloadState.Running(id, str(R.string.map_checking_newest), 0f)
            val progress = { msg: String, f: Float -> _mapDownload.value = com.curveon.data.map.MapDownloadState.Running(id, msg, f) }
            try {
                val installed = _installedMaps.value.firstOrNull { it.id == id }
                // A current map only needs the routing and search it lacks.
                if (installed == null || installed.newerBuild != null || (installed.missingRouting.isEmpty() && !installed.missingSearch)) {
                    val build = mapDownloads.latestBuild() ?: error(str(R.string.map_no_connection))
                    latestMapBuild = build
                    mapDownloads.download(area, build, progress, isCancelled = { cancelMapDownload })
                    _mapVersion.value++
                    updateMapStatus()
                }
                // One finishing step at a time; the next map can start downloading once this one is handed over.
                progress(str(R.string.maps_waiting), 1f)
                mapFinisher?.join()
                check(!cancelMapDownload) { "Cancelled" }
                _mapFinishing.value = com.curveon.data.map.MapDownloadState.Running(id, str(R.string.map_checking_newest), 0f)
                cancelMapFinish = false
                mapFinisher = scope.launch { finishMap(area) }
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
        deferredFailure?.let {
            deferredFailure = null
            _mapDownload.value = com.curveon.data.map.MapDownloadState.Failed(it)
        }
    }

    /** Routing tiles, search index and speed limits for a country whose map is installed. */
    private suspend fun finishMap(area: com.curveon.data.map.CatalogArea) {
        val id = area.id
        val progress = { msg: String, f: Float -> _mapFinishing.value = com.curveon.data.map.MapDownloadState.Running(id, msg, f) }
        try {
            mapDownloads.completeMissing(area, progress, isCancelled = { cancelMapFinish })
        } catch (e: Throwable) {
            mapDownloads.cleanupPartial(id)
            if (!cancelMapFinish) {
                val message = str(R.string.map_download_failed, e.message ?: "")
                if (_mapDownload.value is com.curveon.data.map.MapDownloadState.Running) deferredFailure = message
                else _mapDownload.value = com.curveon.data.map.MapDownloadState.Failed(message)
            }
        } finally {
            _mapFinishing.value = null
            updateMapStatus()
        }
    }

    /** Stops [areaId]: the running download or finishing step, or takes it out of the queue. */
    fun cancelMapDownload(areaId: String) {
        when {
            (_mapDownload.value as? com.curveon.data.map.MapDownloadState.Running)?.areaId == areaId -> cancelMapDownload = true
            _mapFinishing.value?.areaId == areaId -> cancelMapFinish = true
            else -> _mapQueue.update { it - areaId }
        }
    }

    fun deleteMap(id: String) {
        mapDownloads.delete(id)
        updateMapStatus()
        _mapVersion.value++
    }

    // ---------------------------------------------------------------- offline map

    private fun describeOfflineMaps(): String {
        val maps = offlineMaps.installedMaps()
        return if (maps.isEmpty()) str(R.string.offline_map_none, offlineMaps.mapsDir)
        else maps.joinToString("\n") { str(R.string.offline_map_line, it.name, it.length() / 1e6) } + "\n" + str(R.string.offline_map_restart)
    }

    fun importOfflineMap(uri: Uri) {
        scope.launch {
            runCatching {
                offlineMaps.import(uri) { p -> _offlineMapStatus.value = str(R.string.importing_percent, p * 100) }
            }.onFailure { _offlineMapStatus.value = str(R.string.import_failed, it.message ?: "") }
                .onSuccess { _offlineMapStatus.value = describeOfflineMaps(); _mapVersion.value++; updateMapStatus() }
        }
    }

    private companion object {
        /** Search data bigger than this (compressed) isn't fetched on its own: it comes with the country's map. */
        const val SEARCH_OFFER_MAX_BYTES = 120_000_000L
    }
}
