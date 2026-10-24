package com.curveon.data.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.curveon.mapstyle.MapStyle
import com.curveon.pmtiles.PmtilesIndex
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Offline vector maps are PMTiles archives (Protomaps schema) stored in the app's external files
 * directory, e.g. /sdcard/Android/data/com.curveon.app/files/maps/norway.pmtiles, one per
 * country. They are downloaded in Settings (MapDownloads), copied there over USB or imported
 * through the system file picker; all of them are shown together. Elsewhere the map is drawn from
 * OpenFreeMap's online tiles when there is a connection (and from MapLibre's cache when not).
 */
class OfflineMaps(private val context: Context) {

    val mapsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "maps").apply { mkdirs() }

    fun installedMaps(): List<File> =
        mapsDir.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") }?.sortedBy { it.name }.orEmpty()

    /**
     * Builds a MapLibre style JSON for [flavor] ("dark" or "light") over every installed map. Each
     * map gets its own source and a copy of each layer right after the first map's, so water, roads
     * and labels still stack in the right order where countries meet. Under them all go the online
     * layers (OpenFreeMap, see tools/map/gen-online-style.py), which the map shows only where no
     * downloaded map covers the view ([covers]). The day map draws the white roads
     * [road] grey with no edge (light paths [path]) and the main roads' edge black, main roads staying yellow.
     */
    fun styleJson(flavor: String, road: String = HC_ROAD, path: String = HC_PATH): String =
        MapStyle.build(flavor, asset("map/styles/$flavor.json"), asset("map/styles/online-$flavor.json"),
            installedMaps().map { "pmtiles://file://${it.absolutePath}" }, road, path)

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }

    private data class Coverage(val key: String, val maps: List<PmtilesIndex>, val at: Long)
    @Volatile private var coverage: Coverage? = null

    /**
     * Whether a downloaded map has tiles at this spot at [zoom] (a country's file has a few
     * coarse tiles reaching past its border but no detail there). Call off the main thread.
     */
    fun covers(lat: Double, lon: Double, zoom: Double): Boolean = indexes().any { map ->
        val z = zoom.toInt().coerceIn(0, map.maxZoom)
        val n = 1 shl z
        val x = ((lon + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = ((1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        runCatching { map.has(z, x, y) }.getOrDefault(false)
    }

    @Synchronized
    private fun indexes(): List<PmtilesIndex> {
        // Asked 25 times per look at the view (up to once a second while riding): read the folder only now and then.
        coverage?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < COVERAGE_RECHECK_MS }?.let { return it.maps }
        val maps = installedMaps()
        val key = maps.joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        coverage?.takeIf { it.key == key }?.let { coverage = it.copy(at = android.os.SystemClock.elapsedRealtime()); return it.maps }
        return maps.mapNotNull { runCatching { PmtilesIndex(it) }.getOrNull() }.also { coverage = Coverage(key, it, android.os.SystemClock.elapsedRealtime()) }
    }

    suspend fun import(uri: Uri, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.takeIf { it.endsWith(".pmtiles") } ?: "imported.pmtiles"
        val total = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        val target = File(mapsDir, name)
        val tmp = File(mapsDir, "$name.part")
        resolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    copied += n
                    if (total > 0) onProgress(copied.toFloat() / total)
                }
            }
        }
        target.delete()
        tmp.renameTo(target)
        target
    }

    companion object {
        /** The map's colours and the online source, as [MapStyle] draws them (other code refers to them here). */
        const val HC_ROAD = MapStyle.HC_ROAD
        const val HC_PATH = MapStyle.HC_PATH
        const val NIGHT_ROAD_HC = MapStyle.NIGHT_ROAD_HC
        const val NIGHT_PATH_HC = MapStyle.NIGHT_PATH_HC
        const val NIGHT_ROAD = MapStyle.NIGHT_ROAD
        const val NIGHT_PATH = MapStyle.NIGHT_PATH
        const val LAND_LIGHT = MapStyle.LAND_LIGHT
        const val LAND_DARK = MapStyle.LAND_DARK
        const val ONLINE_SOURCE = MapStyle.ONLINE_SOURCE
        /** How long the list of downloaded maps is trusted before the folder is looked at again. */
        private const val COVERAGE_RECHECK_MS = 10_000L
    }
}
