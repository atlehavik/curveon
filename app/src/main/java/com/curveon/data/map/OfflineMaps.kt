package com.curveon.data.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
     * downloaded map covers the view ([covers]). [highContrast] (day map only) draws the white roads
     * dark grey and every road's edge black, main roads staying yellow.
     */
    fun styleJson(flavor: String, highContrast: Boolean = false): String {
        val maps = installedMaps()
        val style = JSONObject(asset("map/styles/$flavor.json"))
        val online = JSONObject(asset("map/styles/online-$flavor.json"))
        val sources = style.getJSONObject("sources")
        val template = sources.getJSONObject(SOURCE)
        sources.remove(SOURCE)
        maps.forEachIndexed { i, f ->
            sources.put(sourceName(i), JSONObject(template.toString()).put("url", "pmtiles://file://${f.absolutePath}"))
        }
        sources.put(ONLINE_SOURCE, online.getJSONObject("source"))
        val layers = style.getJSONArray("layers")
        val out = JSONArray()
        for (k in 0 until layers.length()) {
            val layer = layers.getJSONObject(k)
            if (layer.optString("source") != SOURCE) {
                out.put(layer)
                if (layer.getString("id") == "background") {
                    val onlineLayers = online.getJSONArray("layers")
                    for (j in 0 until onlineLayers.length()) out.put(onlineLayers.getJSONObject(j).apply {
                        // Hidden until the map has checked the view isn't covered by a downloaded map.
                        if (maps.isNotEmpty()) put("layout", (optJSONObject("layout") ?: JSONObject()).put("visibility", "none"))
                    })
                }
                continue
            }
            for (i in maps.indices) {
                // The first map keeps the original ids (other code looks layers up by them).
                out.put(JSONObject(layer.toString()).put("source", sourceName(i)).apply { if (i > 0) put("id", "${layer.getString("id")}~$i") })
            }
        }
        if (highContrast && flavor == "light") for (k in 0 until out.length()) highContrast(out.getJSONObject(k))
        style.put("layers", out)
        return style.toString()
    }

    /** One layer of the day style in high contrast: dark grey where it was white, and black road edges. */
    private fun highContrast(layer: JSONObject) {
        val paint = layer.optJSONObject("paint") ?: return
        val id = layer.getString("id").substringBefore('~')
        when (id) {
            "roads_minor", "roads_minor_service", "roads_bridges_minor", "online-roads_minor" -> paint.put("line-color", HC_ROAD)
            "roads_other", "roads_bridges_other", "online-roads_other" -> paint.put("line-color", HC_PATH)
            "roads_minor_casing", "roads_minor_service_casing", "roads_bridges_minor_casing", "roads_link_casing",
            "roads_major_casing_early", "roads_major_casing_late", "roads_highway_casing_early", "roads_highway_casing_late",
            "roads_bridges_link_casing", "roads_bridges_major_casing", "roads_bridges_highway_casing",
            "online-roads_minor_casing", "online-roads_major_casing", "online-roads_highway_casing" -> {
                paint.put("line-color", "#000000")
                // Edge at least this wide wherever it is drawn at all (a 0 stop is where it starts).
                when (val w = paint.opt("line-width")) {
                    is JSONArray -> for (i in 4 until w.length() step 2) if (w.optDouble(i, 0.0) > 0.0) w.put(i, HC_EDGE)
                    is Number -> paint.put("line-width", HC_EDGE)
                }
            }
        }
    }

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

    private fun sourceName(i: Int) = if (i == 0) SOURCE else "$SOURCE$i"

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
        private const val SOURCE = "protomaps"
        /** High contrast: the dark grey that white roads and light paths become, and the black edge's width (px). */
        private const val HC_ROAD = "#555555"
        private const val HC_PATH = "#7a7a7a"
        private const val HC_EDGE = 1.5
        /** How long the list of downloaded maps is trusted before the folder is looked at again. */
        private const val COVERAGE_RECHECK_MS = 10_000L

        /** The online layers' source; their ids start with "online-". */
        const val ONLINE_SOURCE = "online"
    }
}
