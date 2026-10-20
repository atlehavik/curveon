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
     * downloaded map covers the view ([covers]). The day map draws the white roads
     * dark grey with no edge and the main roads' edge black, main roads staying yellow.
     */
    fun styleJson(flavor: String): String {
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
        var out = JSONArray()
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
        for (k in 0 until out.length()) { roadLabels(out.getJSONObject(k)); placeHalo(out.getJSONObject(k), flavor); roadNameColours(out.getJSONObject(k), flavor) }
        // Names last, so no road is drawn over them (and their halo): the place names, and the online map's
        // labels, which would otherwise lie under the overlay lines (tarmac and gravel dashes) added over the offline map.
        val rest = JSONArray(); val names = JSONArray()
        for (k in 0 until out.length()) out.getJSONObject(k).let {
            val id = it.getString("id").substringBefore('~')
            if (id.startsWith("places_") || (id.startsWith("online-") && it.optString("type") == "symbol")) names.put(it) else rest.put(it)
        }
        for (k in 0 until names.length()) rest.put(names.getJSONObject(k))
        out = rest
        for (k in 0 until out.length()) {
            val layer = out.getJSONObject(k)
            if (flavor == "light") highContrast(layer) else nightRoadWidths(layer)
            minorRoadsFromSurfaceZoom(layer)
        }
        style.put("layers", out)
        return style.toString()
    }

    /** Place names get a wide halo (the map's land colour; white text on the night map), so a road under them doesn't show between the letters. */
    private fun placeHalo(layer: JSONObject, flavor: String) {
        val id = layer.getString("id").substringBefore('~')
        if (!id.startsWith("places_") && id != "online-places") return
        val paint = layer.optJSONObject("paint") ?: return
        paint.put("text-halo-width", PLACE_HALO)
        if (flavor == "light") paint.put("text-halo-color", LAND_LIGHT)
        else { paint.put("text-halo-color", LAND_DARK); paint.put("text-color", "#ffffff") }
    }

    /** Road names on the map's land colour (white text at night), like the place names. */
    private fun roadNameColours(layer: JSONObject, flavor: String) {
        val id = layer.getString("id").substringBefore('~')
        if (id != "roads_labels_minor" && id != "roads_labels_major" && id != "online-roads_labels") return
        val paint = layer.optJSONObject("paint") ?: return
        paint.put("text-halo-width", ROAD_NAME_HALO)
        if (flavor == "dark") { paint.put("text-color", "#ffffff"); paint.put("text-halo-color", LAND_DARK) }
        else paint.put("text-halo-color", LAND_LIGHT)
    }

    /**
     * Road names: all from the same zoom (the closest in of the layers' own, so none shows earlier than
     * the rest), and beside the road instead of across it.
     */
    private fun roadLabels(layer: JSONObject) {
        val id = layer.getString("id").substringBefore('~')
        if (id != "roads_labels_minor" && id != "roads_labels_major" && id != "online-roads_labels") return
        layer.put("minzoom", ROAD_LABEL_ZOOM)
        val layout = layer.optJSONObject("layout") ?: return
        layout.put("text-offset", JSONArray().put(0).put(1.15))
    }

    /**
     * One layer of the day style in high contrast: the white roads and light paths a flat dark grey (the
     * same at every zoom, with no edge), the main roads' edge black.
     */
    private fun highContrast(layer: JSONObject) {
        val paint = layer.optJSONObject("paint") ?: return
        val id = layer.getString("id").substringBefore('~')
        when (id) {
            // Place names: dark text on a wide white halo, so they stay readable over the dark roads.
            "places_subplace", "places_locality", "places_region", "online-places" -> {
                paint.put("text-color", "#1f1f1f"); paint.put("text-halo-color", LAND_LIGHT)
            }
            "roads_minor", "roads_minor_service", "roads_bridges_minor", "online-roads_minor" -> { paint.put("line-color", HC_ROAD); atLeast(paint, HC_MIN_WIDTH) }
            "roads_other", "roads_bridges_other", "online-roads_other" -> { paint.put("line-color", HC_PATH); atLeast(paint, HC_MIN_WIDTH) }
            "roads_minor_casing", "roads_minor_service_casing", "roads_bridges_minor_casing", "online-roads_minor_casing" ->
                // Not hidden by visibility: the online layers' visibility is switched by the map view.
                paint.put("line-opacity", 0)
            "roads_link_casing", "roads_major_casing_early", "roads_major_casing_late", "roads_highway_casing_early", "roads_highway_casing_late",
            "roads_bridges_link_casing", "roads_bridges_major_casing", "roads_bridges_highway_casing",
            "online-roads_major_casing", "online-roads_highway_casing" -> {
                paint.put("line-color", "#000000")
                // Edge at least this wide wherever it is drawn at all (a 0 stop is where it starts).
                when (val w = paint.opt("line-width")) {
                    is JSONArray -> for (i in 4 until w.length() step 2) if (w.optDouble(i, 0.0) > 0.0) w.put(i, HC_EDGE)
                    is Number -> paint.put("line-width", HC_EDGE)
                }
            }
        }
    }

    /**
     * Keeps a line at least [min] px wide wherever it is drawn at all: a thinner one is only part-covered
     * pixels, and a dark grey one fades to nearly white on the light map when zoomed out.
     */
    private fun atLeast(paint: JSONObject, min: Double) {
        when (val w = paint.opt("line-width")) {
            is JSONArray -> for (i in 4 until w.length() step 2) { val v = w.optDouble(i, 0.0); if (v > 0.0 && v < min) w.put(i, min) }
            is Number -> if (w.toDouble() in 0.0..min) paint.put("line-width", min)
        }
    }

    /**
     * The night map's small roads and paths are never thinner than the day map's (see [highContrast]): the
     * hair-thin ones fade out, and a gravel dash that ends in nothing looks like it joins no road.
     */
    private fun nightRoadWidths(layer: JSONObject) {
        val paint = layer.optJSONObject("paint") ?: return
        when (layer.getString("id").substringBefore('~')) {
            "roads_minor", "roads_minor_service", "roads_bridges_minor", "online-roads_minor",
            "roads_other", "roads_bridges_other", "online-roads_other" -> atLeast(paint, HC_MIN_WIDTH)
        }
    }

    /**
     * The small roads are drawn from the zoom the gravel and offroad dashes start at (day and night map), at least
     * [HC_MIN_WIDTH] wide from there: a dashed road always meets a road on the map, not bare ground.
     */
    private fun minorRoadsFromSurfaceZoom(layer: JSONObject) {
        val id = layer.getString("id").substringBefore('~')
        if (id != "roads_minor" && id != "online-roads_minor") return
        val w = layer.optJSONObject("paint")?.optJSONArray("line-width") ?: return
        for (i in 4 until w.length() step 2) if (w.optDouble(i - 1, 0.0) >= SURFACE_ZOOM && w.optDouble(i, 0.0) < HC_MIN_WIDTH) w.put(i, HC_MIN_WIDTH)
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
        /** High contrast: the greys that white roads and light paths become, and the main roads' black edge width (px). */
        const val HC_ROAD = "#707070"
        private const val HC_PATH = "#a0a0a0"
        private const val HC_EDGE = 1.5
        /** The day map's land colour: place names sit on a halo of it, so they blend in instead of standing out white. */
        private const val LAND_LIGHT = "#e2dfda"
        /** The night map's land colour (the dark styles' earth and background). */
        private const val LAND_DARK = "#1f1f1f"
        private const val ROAD_NAME_HALO = 2.0
        /** The width of the halo round place names (px). */
        private const val PLACE_HALO = 3.0
        /** Road names show from this zoom, all of them. */
        private const val ROAD_LABEL_ZOOM = 15
        /** The thinnest the grey roads and paths are drawn, at any zoom (px). */
        private const val HC_MIN_WIDTH = 1.6
        /** Where the gravel and offroad dashes start on the map (fast setting): the small roads start here too. */
        private const val SURFACE_ZOOM = 11.0
        /** How long the list of downloaded maps is trusted before the folder is looked at again. */
        private const val COVERAGE_RECHECK_MS = 10_000L

        /** The online layers' source; their ids start with "online-". */
        const val ONLINE_SOURCE = "online"
    }
}
