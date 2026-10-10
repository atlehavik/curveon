package com.curveon.data.routing

import android.content.Context
import com.curveon.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * BRouter routing data (.rd5 segment files, 5x5 degree tiles). Stored in
 * Android/data/com.curveon.tablet/files/routing/segments4 so they can also be copied over USB.
 * Each downloaded country brings the tiles it needs (see MapDownloads).
 */
class RoutingData(private val context: Context) {

    val segmentsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "routing/segments4").apply { mkdirs() }

    fun installedTiles(): List<File> =
        segmentsDir.listFiles { f -> f.name.endsWith(".rd5") }?.sortedBy { it.name }.orEmpty()

    fun installedNames() = installedTiles().map { it.nameWithoutExtension }.toSet()

    fun missing(tiles: List<String>): List<String> = installedNames().let { have -> tiles.filterNot { it in have } }

    fun isReady() = installedTiles().isNotEmpty()

    fun describe(): String {
        val tiles = installedTiles()
        return if (tiles.isEmpty()) context.getString(R.string.routing_none, segmentsDir.toString())
        else context.getString(R.string.routing_installed, tiles.size, tiles.sumOf { it.length() } / 1e6)
    }

    /** Downloads those of [tiles] that are missing from brouter.de. Needs a connection (e.g. the phone's hotspot). */
    suspend fun download(tiles: List<String>, onProgress: (done: Int, total: Int, file: String) -> Unit, isCancelled: () -> Boolean = { false }) =
        withContext(Dispatchers.IO) {
            val missing = missing(tiles)
            val client = OkHttpClient()
            missing.forEachIndexed { i, tile ->
                check(!isCancelled()) { "Cancelled" }
                onProgress(i, missing.size, tile)
                val request = Request.Builder().url("$SEGMENTS_URL$tile.rd5").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download of $tile failed: HTTP ${response.code}")
                    val tmp = File(segmentsDir, "$tile.rd5.part")
                    response.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                    tmp.renameTo(File(segmentsDir, "$tile.rd5"))
                }
            }
            onProgress(missing.size, missing.size, "")
        }

    fun delete(tiles: Collection<String>) = tiles.forEach { File(segmentsDir, "$it.rd5").delete() }

    /** Tiles brouter.de doesn't have (open sea): not asked for again this session. */
    private val absent = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Of [tiles], those not here yet that brouter.de may have. */
    fun toFetch(tiles: List<String>) = missing(tiles).filterNot { it in absent }

    /**
     * Fetches [tiles] that are missing, skipping ones brouter.de doesn't have (the sea). For planning
     * online without having downloaded the country: only the routing data, kept for next time.
     */
    suspend fun fetch(tiles: List<String>, onProgress: (done: Int, total: Int) -> Unit) = withContext(Dispatchers.IO) {
        val todo = toFetch(tiles)
        val client = OkHttpClient()
        todo.forEachIndexed { i, tile ->
            onProgress(i, todo.size)
            client.newCall(Request.Builder().url("$SEGMENTS_URL$tile.rd5").build()).execute().use { response ->
                if (response.code == 404) { absent += tile; return@use }
                if (!response.isSuccessful) throw IOException("Download of $tile failed: HTTP ${response.code}")
                val tmp = File(segmentsDir, "$tile.rd5.part")
                response.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                tmp.renameTo(File(segmentsDir, "$tile.rd5"))
            }
        }
    }

    /** True when the device has a working internet connection. */
    fun online(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    companion object {
        const val SEGMENTS_URL = "https://brouter.de/brouter/segments4/"

        /** BRouter's 5x5 degree tiles covering a box (with [marginDeg] around it), e.g. "E5_N60". */
        fun tilesFor(south: Double, west: Double, north: Double, east: Double, marginDeg: Double = 0.3): List<String> {
            fun floor5(v: Double) = (kotlin.math.floor(v / 5.0) * 5).toInt()
            val out = ArrayList<String>()
            var lat = floor5(south - marginDeg)
            while (lat <= floor5(north + marginDeg)) {
                var lon = floor5(west - marginDeg)
                while (lon <= floor5(east + marginDeg)) {
                    out += (if (lon < 0) "W${-lon}" else "E$lon") + "_" + (if (lat < 0) "S${-lat}" else "N$lat")
                    lon += 5
                }
                lat += 5
            }
            return out
        }
    }
}
