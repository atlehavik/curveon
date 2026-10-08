package com.curveon.data.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline vector maps are PMTiles archives (Protomaps schema) stored in the app's external files
 * directory, e.g. /sdcard/Android/data/com.curveon.tablet/files/maps/norway.pmtiles, one per
 * country. They are downloaded in Settings (MapDownloads), copied there over USB or imported
 * through the system file picker; all of them are shown together.
 */
class OfflineMaps(private val context: Context) {

    val mapsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "maps").apply { mkdirs() }

    fun installedMaps(): List<File> =
        mapsDir.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") }?.sortedBy { it.name }.orEmpty()

    /**
     * Builds a MapLibre style JSON for [flavor] ("dark" or "light") over every installed map, or
     * null when none is installed. Each map gets its own source and a copy of each layer right
     * after the first map's, so water, roads and labels still stack in the right order where
     * countries meet.
     */
    fun styleJson(flavor: String): String? {
        val maps = installedMaps()
        if (maps.isEmpty()) return null
        val style = JSONObject(context.assets.open("map/styles/$flavor.json").bufferedReader().use { it.readText() })
        val sources = style.getJSONObject("sources")
        val template = sources.getJSONObject(SOURCE)
        maps.forEachIndexed { i, f ->
            sources.put(sourceName(i), JSONObject(template.toString()).put("url", "pmtiles://file://${f.absolutePath}"))
        }
        val layers = style.getJSONArray("layers")
        val out = JSONArray()
        for (k in 0 until layers.length()) {
            val layer = layers.getJSONObject(k)
            if (layer.optString("source") != SOURCE) { out.put(layer); continue }
            for (i in maps.indices) {
                // The first map keeps the original ids (other code looks layers up by them).
                out.put(JSONObject(layer.toString()).put("source", sourceName(i)).apply { if (i > 0) put("id", "${layer.getString("id")}~$i") })
            }
        }
        style.put("layers", out)
        return style.toString()
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

        /** Used when no offline map is installed and the tablet happens to be online. */
        const val ONLINE_FALLBACK_STYLE = """
            {"version":8,"sources":{"osm":{"type":"raster","tileSize":256,
             "tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
             "attribution":"© OpenStreetMap contributors"}},
             "layers":[{"id":"osm","type":"raster","source":"osm"}]}
        """
    }
}
