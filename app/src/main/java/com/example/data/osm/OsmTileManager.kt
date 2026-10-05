package com.example.data.osm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

class OsmTileManager(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "osm_tiles").apply {
        if (!exists()) mkdirs()
    }

    // In-memory cache: 24MB
    private val memCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    suspend fun getTile(zoom: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$zoom/$x/$y"
        memCache.get(key)?.let { return@withContext it }

        // Check disk cache
        val diskFile = File(cacheDir, "$zoom-$x-$y.png")
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bmp != null) {
                    memCache.put(key, bmp)
                    return@withContext bmp
                }
            } catch (e: Exception) {
                diskFile.delete()
            }
        }

        // Fetch from OSM server if online
        try {
            val url = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "VibeNav-Motorcycle-Dashboard/1.0 (Android Auto style; OSM Offline)")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    val bytes = input.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        try {
                            FileOutputStream(diskFile).use { it.write(bytes) }
                        } catch (_: Exception) {}
                        memCache.put(key, bmp)
                        return@withContext bmp
                    }
                }
            }
        } catch (_: Exception) {
            // Offline or failed: graceful fallback to procedural canvas tile or cached tiles
        }
        return@withContext null
    }

    fun getOfflineCacheCount(): Int {
        return cacheDir.listFiles()?.size ?: 0
    }

    fun getOfflineCacheSizeMb(): Float {
        val bytes = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        return bytes / (1024f * 1024f)
    }

    fun clearCache() {
        memCache.evictAll()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val TILE_SIZE = 256

        fun lonToTileX(lon: Double, zoom: Int): Double {
            return (lon + 180.0) / 360.0 * (1 shl zoom)
        }

        fun latToTileY(lat: Double, zoom: Int): Double {
            val latRad = Math.toRadians(lat)
            return (1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * (1 shl zoom)
        }

        fun tileXToLon(x: Double, zoom: Int): Double {
            return x / (1 shl zoom) * 360.0 - 180.0
        }

        fun tileYToLat(y: Double, zoom: Int): Double {
            val n = Math.PI - 2.0 * Math.PI * y / (1 shl zoom)
            return Math.toDegrees(atan(sinh(n)))
        }
    }
}
