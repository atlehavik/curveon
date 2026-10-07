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
 */
class RoutingData(private val context: Context) {

    val segmentsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "routing/segments4").apply { mkdirs() }

    fun installedTiles(): List<File> =
        segmentsDir.listFiles { f -> f.name.endsWith(".rd5") }?.sortedBy { it.name }.orEmpty()

    fun missingNorwayTiles(): List<String> {
        val have = installedTiles().map { it.nameWithoutExtension }.toSet()
        return NORWAY_TILES.filterNot { it in have }
    }

    fun isReady() = missingNorwayTiles().isEmpty()

    fun describe(): String {
        val tiles = installedTiles()
        val missing = missingNorwayTiles()
        val size = tiles.sumOf { it.length() } / 1e6
        return when {
            missing.isEmpty() -> context.getString(R.string.routing_installed, tiles.size, size)
            tiles.isEmpty() -> context.getString(R.string.routing_none, segmentsDir.toString())
            else -> context.getString(R.string.routing_incomplete, missing.joinToString())
        }
    }

    /** Downloads missing Norway tiles from brouter.de. Needs a connection (e.g. the phone's hotspot). */
    suspend fun downloadNorway(onProgress: (done: Int, total: Int, file: String) -> Unit) = withContext(Dispatchers.IO) {
        val missing = missingNorwayTiles()
        val client = OkHttpClient()
        missing.forEachIndexed { i, tile ->
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

    companion object {
        const val SEGMENTS_URL = "https://brouter.de/brouter/segments4/"

        /** 5x5 degree tiles that intersect mainland Norway (named by their south-west corner). */
        val NORWAY_TILES = listOf(
            "E0_N55", "E0_N60", "E5_N55", "E5_N60",
            "E10_N55", "E10_N60", "E10_N65",
            "E15_N60", "E15_N65", "E15_N70",
            "E20_N65", "E20_N70", "E25_N65", "E25_N70", "E30_N65", "E30_N70"
        )
    }
}
