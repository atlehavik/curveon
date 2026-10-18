package com.curveon.data.map

import android.content.Context
import com.curveon.R
import com.curveon.data.routing.RoutingData
import com.curveon.data.search.SearchIndexes
import com.curveon.pmtiles.ExtractProgress
import com.curveon.pmtiles.RegionExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** A map on the tablet: a catalog country, or a .pmtiles file copied/imported by hand ([area] null). */
data class InstalledMap(
    val id: String,
    val area: CatalogArea?,
    val file: File,
    val bytes: Long,
    /** Protomaps build date, e.g. "2026-10-05" (file date for hand-copied files). */
    val build: String?,
    /** Newest build online, when known and a month or more newer than [build]. */
    val newerBuild: String?,
    /** Routing tiles this country needs that aren't on the tablet. */
    val missingRouting: List<String>,
    val search: SearchIndexes.Source,
    /** Search is built from the map, but a full one (POIs, house numbers, cameras) is online. */
    val betterSearch: Boolean
) {
    val missingSearch get() = area != null && (search == SearchIndexes.Source.NONE || betterSearch)
}

sealed interface MapDownloadState {
    data object Idle : MapDownloadState
    /** [finishing]: another country whose routing and search are being fetched/built while this map downloads. */
    data class Running(val areaId: String, val message: String, val fraction: Float, val finishing: Running? = null) : MapDownloadState
    data class Failed(val message: String) : MapDownloadState

    /** The running download or finishing step of [id], if any. */
    fun runningFor(id: String): Running? =
        (this as? Running)?.let { if (it.areaId == id) it else it.finishing?.takeIf { f -> f.areaId == id } }
}

/**
 * Offline maps per country, like DMD2: the map comes from the Protomaps daily world build
 * (OpenStreetMap data), cut to the country on the tablet with HTTP range requests, and the
 * country's BRouter routing tiles come from brouter.de. Needs a connection (phone hotspot or
 * Wi-Fi) while downloading only.
 */
class MapDownloads(
    private val context: Context,
    private val maps: OfflineMaps,
    private val routing: RoutingData,
    private val search: SearchIndexes,
    private val speedLimits: com.curveon.data.speed.SpeedLimits
) {

    /** Full search indexes online (area id to bytes), once [refreshSearch] has reached the server. */
    @Volatile var searchAvailable: Map<String, Long>? = null
        private set

    suspend fun refreshSearch() { search.available()?.let { searchAvailable = it } }

    /** Size of [areaId]'s full search index online, once [refreshSearch] has reached the server. */
    fun searchBytes(areaId: String): Long? = searchAvailable?.get(areaId)

    val catalog = MapCatalog(context)
    private val prefs = context.getSharedPreferences("maps", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun file(area: CatalogArea) = File(maps.mapsDir, "${area.id}.pmtiles")

    fun installed(latestBuild: String?): List<InstalledMap> {
        val tiles = routing.installedNames()
        val sources = HashMap<String, SearchIndexes.Source>()
        return maps.installedMaps().map { f ->
            val id = f.nameWithoutExtension
            val area = catalog.byId[id]
            // Maps copied over USB have no recorded build: the file's date is close enough.
            val build = prefs.getString("build_$id", null)?.let(::pretty)
                ?: java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            val latest = latestBuild?.let(::pretty)
            InstalledMap(
                id, area, f, f.length(), build,
                latest?.takeIf { area != null && (build == null || daysBetween(build, it) >= STALE_DAYS) },
                area?.routing?.filterNot { it in tiles }.orEmpty(),
                search.source(id).also { src -> sources[id] = src },
                sources[id] == SearchIndexes.Source.MAP && searchAvailable?.containsKey(id) == true
            )
        }
    }

    fun freeBytes() = maps.mapsDir.usableSpace

    /** What downloading [area] would add: its map, plus the routing tiles not already on the tablet. */
    fun downloadBytes(area: CatalogArea, installedTiles: Set<String>) =
        area.mapBytes + area.routing.filterNot { it in installedTiles }.sumOf { catalog.routingBytes(it) } + (searchAvailable?.get(area.id) ?: 0L)

    /** Key of the newest Protomaps build, e.g. "20261006.pmtiles"; null when offline. */
    suspend fun latestBuild(): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(BUILDS_URL).build()).execute().use { r ->
                val a = JSONArray(r.body!!.string())
                (0 until a.length()).map { a.getJSONObject(it).getString("key") }.filter { it.endsWith(".pmtiles") }.maxOrNull()
            }
        }.getOrNull()
    }

    /**
     * Downloads (or updates) the map of [area]. Its routing and search come next, with
     * [completeMissing], which the caller can run while the next country's map downloads.
     */
    suspend fun download(area: CatalogArea, build: String, onProgress: (String, Float) -> Unit, isCancelled: () -> Boolean) =
        withContext(Dispatchers.IO) {
            // An old map stays usable until the new one is complete, so we need room for a full copy.
            val needed = (downloadBytes(area, routing.installedNames()) * 1.1).toLong()
            check(maps.mapsDir.usableSpace > needed) {
                context.getString(R.string.map_no_space, needed / 1e9, maps.mapsDir.usableSpace / 1e9)
            }
            RegionExtractor(http).extract("https://build.protomaps.com/$build", area.tileMask(), MAX_ZOOM, file(area), onProgress = { p ->
                val msg = when (p.stage) {
                    ExtractProgress.Stage.READING_INDEX ->
                        if (p.parts > 0) context.getString(R.string.map_reading_index_parts, p.partsDone, p.parts) else context.getString(R.string.map_reading_index)
                    ExtractProgress.Stage.DOWNLOADING -> context.getString(R.string.map_downloading, p.doneBytes / 1e6, p.totalBytes / 1e6)
                    ExtractProgress.Stage.DONE -> context.getString(R.string.download_complete)
                }
                onProgress(msg, p.fraction)
            }, isCancelled = isCancelled)
            prefs.edit().putString("build_${area.id}", build.removeSuffix(".pmtiles")).apply()
        }

    /** Routing tiles the area lacks, and its search when missing or when a fuller one is online (progress 0..1). */
    suspend fun completeMissing(area: CatalogArea, onProgress: (String, Float) -> Unit, isCancelled: () -> Boolean) {
        routing.download(area.routing, { done, total, tile ->
            if (done < total) onProgress(context.getString(R.string.routing_downloading, tile, done + 1, total), 0.3f * done / total)
        }, isCancelled)
        if (searchAvailable == null) refreshSearch()
        val src = search.source(area.id)
        if (src == SearchIndexes.Source.NONE || (src == SearchIndexes.Source.MAP && searchAvailable?.containsKey(area.id) == true)) {
            search.install(area.id, file(area), searchAvailable, { msg, f -> onProgress(msg, 0.3f + 0.7f * f) }, isCancelled)
        }
        // Official speed limits, for the countries that publish them freely (small: no progress of its own).
        speedLimits.update(listOf(area.id), speedLimits.available())
    }

    fun cleanupPartial(id: String) {
        search.cleanupPartial(id)
        File(maps.mapsDir, "$id.pmtiles.part").delete()
        File(maps.mapsDir, "$id.pmtiles.data").delete()
    }

    /** Deletes a map and the routing tiles no other installed country uses. */
    fun delete(id: String) {
        File(maps.mapsDir, "$id.pmtiles").delete()
        cleanupPartial(id)
        prefs.edit().remove("build_$id").apply()
        search.delete(id)
        speedLimits.delete(id)
        val area = catalog.byId[id] ?: return
        val stillNeeded = maps.installedMaps().mapNotNull { catalog.byId[it.nameWithoutExtension] }.flatMap { it.routing }.toSet()
        routing.delete(area.routing.filterNot { it in stillNeeded })
    }

    private fun daysBetween(a: String, b: String) =
        runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a), java.time.LocalDate.parse(b)) }.getOrDefault(0L)

    private fun pretty(build: String) = build.removeSuffix(".pmtiles").let {
        if (it.length == 8) "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}" else it
    }

    companion object {
        const val BUILDS_URL = "https://build-metadata.protomaps.dev/builds.json"
        /** Beyond 14 MapLibre scales the vector tiles up, so more detail isn't needed. */
        const val MAX_ZOOM = 14
        /** Bigger countries (USA, Canada, Russia) are downloaded by state/region instead. */
        const val MAX_WHOLE_BYTES = 4_000_000_000L
        /** Protomaps builds daily; only suggest an update once the map is this old. */
        const val STALE_DAYS = 30
    }
}
