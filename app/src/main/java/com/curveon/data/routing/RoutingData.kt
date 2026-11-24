package com.curveon.data.routing

import android.content.Context
import com.curveon.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * BRouter routing data (.rd5 segment files, 5x5 degree tiles). Stored in
 * Android/data/com.curveon.app/files/routing/segments4 so they can also be copied over USB.
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

    /**
     * Of [tiles], those on the device that are [STALE_DAYS] or more old (the file's date is brouter.de's build of it, or
     * when it was downloaded). brouter.de rebuilds its tiles from OpenStreetMap about once a week, so these may lack
     * roads and fixes made since.
     */
    fun stale(tiles: Collection<String>): List<String> {
        val before = System.currentTimeMillis() - STALE_DAYS * 86_400_000L
        return tiles.filter { File(segmentsDir, "$it.rd5").let { f -> f.exists() && f.lastModified() < before } }
    }

    /** Tiles on the device that are not [stale]: what a country's download need not fetch again. */
    fun freshNames() = installedNames().let { have -> have - stale(have).toSet() }

    private val client by lazy { OkHttpClient() }

    /**
     * Downloads those of [tiles] that are missing from brouter.de, and the [stale] ones brouter.de has a newer build
     * of. Needs a connection (e.g. the phone's hotspot).
     */
    suspend fun download(tiles: List<String>, onProgress: (done: Int, total: Int, file: String) -> Unit, isCancelled: () -> Boolean = { false }) =
        withContext(Dispatchers.IO) {
            val todo = missing(tiles) + stale(tiles)
            todo.forEachIndexed { i, tile ->
                check(!isCancelled()) { "Cancelled" }
                onProgress(i, todo.size, tile)
                val got = get(tile, onlyIfNewer = tile !in missing(listOf(tile)), isCancelled = isCancelled)
                if (got == 404) throw IOException("Download of $tile failed: HTTP 404")
            }
            onProgress(todo.size, todo.size, "")
        }

    /**
     * Fetches newer builds of the [stale] ones of [tiles], one at a time, in the background while planning online:
     * the route being worked out uses the tile already here, the next one the new build.
     */
    suspend fun refreshStale(tiles: List<String>) = withContext(Dispatchers.IO) {
        val todo = stale(tiles)
        if (todo.isEmpty() || !online()) return@withContext
        for (tile in todo) runCatching { get(tile, onlyIfNewer = true) }
    }

    /**
     * Downloads [tile] from brouter.de into the segments folder: to a part file first, so the tile already here stays
     * usable until the new one is complete. With [onlyIfNewer], only when brouter.de's build is newer than the file
     * here (otherwise the file's date is moved on, so it is not asked about again for [STALE_DAYS]). The file gets
     * brouter.de's date of the build. Returns the HTTP code, 304 when nothing newer was there.
     */
    private suspend fun get(tile: String, onlyIfNewer: Boolean, isCancelled: () -> Boolean = { false }): Int = writing.computeIfAbsent(tile) { Mutex() }.withLock {
        check(!isCancelled()) { "Cancelled" }
        val file = File(segmentsDir, "$tile.rd5")
        // Done meanwhile by the other caller (a country's download and planning online can both get here).
        if (onlyIfNewer && file.exists() && !stale(listOf(tile)).contains(tile)) return@withLock 304
        if (!onlyIfNewer && file.exists()) return@withLock 200
        val request = Request.Builder().url("$SEGMENTS_URL$tile.rd5")
        if (onlyIfNewer && file.exists()) request.header("If-Modified-Since", httpDate(file.lastModified()))
        val call = client.newCall(request.build())
        call.execute().use { response ->
            if (response.code == 304) { file.setLastModified(System.currentTimeMillis()); return@withLock 304 }
            if (response.code == 404) return@withLock 404
            if (!response.isSuccessful) throw IOException("Download of $tile failed: HTTP ${response.code}")
            val built = response.headers.getDate("Last-Modified")?.time
            // Older than the tile here (a stale mirror): keep ours.
            if (onlyIfNewer && built != null && built <= file.lastModified()) { file.setLastModified(System.currentTimeMillis()); return@withLock 304 }
            val tmp = File(segmentsDir, "$tile.rd5.part")
            try {
                response.body!!.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (isCancelled()) { call.cancel(); error("Cancelled") }
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                if (!tmp.renameTo(file)) throw IOException("Could not store $tile")
            } finally {
                tmp.delete() // left only when something failed: the tile already here stays as it was
            }
            if (built != null) file.setLastModified(built)
            response.code
        }
    }

    private fun httpDate(ms: Long): String =
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC))

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
        todo.forEachIndexed { i, tile ->
            onProgress(i, todo.size)
            if (get(tile, onlyIfNewer = false) == 404) absent += tile
        }
    }

    /** True when the device has a working internet connection. */
    fun online(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    companion object {
        const val SEGMENTS_URL = "https://brouter.de/brouter/segments4/"

        /** Routing tiles this old are fetched again when brouter.de has a newer build (as maps, see MapDownloads). */
        const val STALE_DAYS = 30

        /**
         * Each tile written by one at a time, whoever asks (a country's download, planning online); a tile being
         * fetched in the background never holds up another one a route waits for.
         */
        private val writing = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

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
