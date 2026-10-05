package com.vibenav.data.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline vector maps are PMTiles archives (Protomaps schema) stored in the app's external files
 * directory, e.g. /sdcard/Android/data/com.vibenav.tablet/files/maps/norway.pmtiles.
 * They can be copied there over USB or imported through the system file picker.
 */
class OfflineMaps(private val context: Context) {

    val mapsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "maps").apply { mkdirs() }

    fun installedMaps(): List<File> =
        mapsDir.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") }?.sortedBy { it.name }.orEmpty()

    /** The map to render. Only one region is supported for now; the largest file wins. */
    fun activeMap(): File? = installedMaps().maxByOrNull { it.length() }

    /** Builds a MapLibre style JSON for [flavor] ("dark" or "light"), or null when no map is installed. */
    fun styleJson(flavor: String): String? {
        val map = activeMap() ?: return null
        val template = context.assets.open("map/styles/$flavor.json").bufferedReader().use { it.readText() }
        return template.replace("__PMTILES_URL__", "file://${map.absolutePath}")
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
        /** Used when no offline map is installed and the tablet happens to be online. */
        const val ONLINE_FALLBACK_STYLE = """
            {"version":8,"sources":{"osm":{"type":"raster","tileSize":256,
             "tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
             "attribution":"© OpenStreetMap contributors"}},
             "layers":[{"id":"osm","type":"raster","source":"osm"}]}
        """
    }
}
