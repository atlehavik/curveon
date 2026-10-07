package com.curveon.data.map

import com.curveon.pmtiles.ExtractProgress
import com.curveon.R
import android.content.Context
import com.curveon.pmtiles.Region
import com.curveon.pmtiles.RegionExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** A region that can be downloaded as an offline map. */
data class MapRegion(val id: String, /** String resource. */ val name: Int, val outlineAsset: String, val approxBytes: Long)

data class MapRegionStatus(
    val region: MapRegion,
    val installedBytes: Long,
    /** Protomaps build date of the installed map, e.g. "2026-10-05". */
    val installedBuild: String?,
    /** Newest build available online, when known. */
    val latestBuild: String?
) {
    val updateAvailable get() = installedBytes > 0 && latestBuild != null && (installedBuild == null || latestBuild > installedBuild)
}

sealed interface MapDownloadState {
    data object Idle : MapDownloadState
    data class Running(val regionId: String, val message: String, val fraction: Float) : MapDownloadState
    data class Failed(val message: String) : MapDownloadState
}

/**
 * Offline maps from the Protomaps daily world build (OpenStreetMap data), cut to a region on the
 * tablet with HTTP range requests. Needs a connection (phone hotspot) while downloading only.
 */
class MapDownloads(private val context: Context, private val maps: OfflineMaps) {

    private val prefs = context.getSharedPreferences("maps", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    val regions = listOf(MapRegion("norway", R.string.region_norway, "map/norway.geojson", 1_700_000_000L))

    fun file(region: MapRegion) = File(maps.mapsDir, "${region.id}.pmtiles")

    fun status(latestBuild: String?): List<MapRegionStatus> = regions.map { r ->
        val f = file(r)
        MapRegionStatus(r, if (f.exists()) f.length() else 0L, prefs.getString("build_${r.id}", null)?.let(::pretty), latestBuild?.let(::pretty))
    }

    /** Key of the newest Protomaps build, e.g. "20261006.pmtiles"; null when offline. */
    suspend fun latestBuild(): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(BUILDS_URL).build()).execute().use { r ->
                val a = JSONArray(r.body!!.string())
                (0 until a.length()).map { a.getJSONObject(it).getString("key") }.filter { it.endsWith(".pmtiles") }.maxOrNull()
            }
        }.getOrNull()
    }

    suspend fun download(region: MapRegion, build: String, onProgress: (String, Float) -> Unit, isCancelled: () -> Boolean) =
        withContext(Dispatchers.IO) {
            // The old map stays usable until the new one is complete, so we need room for a full copy.
            val needed = (region.approxBytes * 1.1).toLong()
            check(maps.mapsDir.usableSpace > needed) {
                context.getString(R.string.map_no_space, needed / 1e9, maps.mapsDir.usableSpace / 1e9)
            }
            val outline = Region.fromGeoJson(context.assets.open(region.outlineAsset).bufferedReader().use { it.readText() })
            RegionExtractor(http).extract("https://build.protomaps.com/$build", outline, MAX_ZOOM, file(region), onProgress = { p ->
                val msg = when (p.stage) {
                    ExtractProgress.Stage.READING_INDEX ->
                        if (p.parts > 0) context.getString(R.string.map_reading_index_parts, p.partsDone, p.parts) else context.getString(R.string.map_reading_index)
                    ExtractProgress.Stage.DOWNLOADING -> context.getString(R.string.map_downloading, p.doneBytes / 1e6, p.totalBytes / 1e6)
                    ExtractProgress.Stage.DONE -> context.getString(R.string.download_complete)
                }
                onProgress(msg, p.fraction)
            }, isCancelled = isCancelled)
            prefs.edit().putString("build_${region.id}", build.removeSuffix(".pmtiles")).apply()
        }

    fun cleanupPartial(region: MapRegion) {
        File(maps.mapsDir, "${region.id}.pmtiles.part").delete()
        File(maps.mapsDir, "${region.id}.pmtiles.data").delete()
    }

    fun delete(region: MapRegion) {
        file(region).delete()
        cleanupPartial(region)
        prefs.edit().remove("build_${region.id}").apply()
    }

    private fun pretty(build: String) = build.removeSuffix(".pmtiles").let {
        if (it.length == 8) "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}" else it
    }

    companion object {
        const val BUILDS_URL = "https://build-metadata.protomaps.dev/builds.json"
        /** Beyond 14 MapLibre scales the vector tiles up, so more detail isn't needed. */
        const val MAX_ZOOM = 14
    }
}
