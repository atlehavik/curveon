package com.curveon.data.speed

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Official speed limits per country (speedlimits/<area id>.sqlite), built monthly by the public
 * curveon-data repo from free government road data (Norway: Statens vegvesen's NVDB) by
 * tools/speedlimits/build_speedlimits.py. Where a country has one, its limit for the road the
 * rider is on wins over OpenStreetMap's maxspeed, which is often missing on smaller roads.
 */
class SpeedLimits(private val context: Context) {

    val dir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "speedlimits").apply { mkdirs() }

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun file(id: String) = File(dir, "$id.sqlite")

    /** One stretch of road in a square: its limit and points (degrees). */
    private class Run(val kmh: Int, val lat: DoubleArray, val lon: DoubleArray)

    @Volatile private var dbs: List<SQLiteDatabase>? = null
    private val cells = LruCache<Long, List<Run>>(256)

    private fun open(): List<SQLiteDatabase> = dbs ?: synchronized(this) {
        dbs ?: dir.listFiles { f -> f.isFile && f.name.endsWith(".sqlite") }.orEmpty().mapNotNull {
            runCatching { SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()
        }.also { dbs = it }
    }

    private fun reopen() = synchronized(this) {
        dbs?.forEach { runCatching { it.close() } }
        dbs = null
        cells.evictAll()
    }

    /**
     * The official limit (km/h) of the road at [lat], [lon]: the nearest stretch within ~20 m that
     * runs the way the rider does ([headingDeg], either direction along it; null = any), so a road
     * crossing at a junction doesn't win. Null where there is none (no file, private road, abroad).
     */
    fun at(lat: Double, lon: Double, headingDeg: Double?): Int? {
        if (open().isEmpty()) return null
        val kx = cos(Math.toRadians(lat)) * 111_320.0
        var best: Int? = null
        var bestD = MAX_OFFSET_M
        val cy = floor(lat * 100).toInt()
        val cx = floor(lon * 100).toInt()
        for (dy in -1..1) for (dx in -1..1) for (r in runs(cy + dy, cx + dx)) {
            for (i in 0 until r.lat.size - 1) {
                val ax = (r.lon[i] - lon) * kx; val ay = (r.lat[i] - lat) * KY
                val bx = (r.lon[i + 1] - lon) * kx; val by = (r.lat[i + 1] - lat) * KY
                val sx = bx - ax; val sy = by - ay
                val len2 = sx * sx + sy * sy
                val t = if (len2 == 0.0) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * sx, ay + t * sy)
                if (d >= bestD) continue
                if (headingDeg != null && len2 > 1.0) {
                    val bearing = Math.toDegrees(kotlin.math.atan2(sx, sy))
                    val diff = abs(((headingDeg - bearing) % 180 + 270) % 180 - 90) // 0 = along, 90 = across
                    if (diff > MAX_ANGLE_DEG) continue
                }
                bestD = d
                best = r.kmh
            }
        }
        return best
    }

    private fun runs(latCell: Int, lonCell: Int): List<Run> {
        val key = (latCell + 9000).toLong() * 100_000 + (lonCell + 18000)
        cells.get(key)?.let { return it }
        val out = ArrayList<Run>()
        for (db in open()) runCatching {
            db.rawQuery("SELECT data FROM cells WHERE cell = ?", arrayOf(key.toString())).use { c ->
                if (c.moveToFirst()) decode(c.getBlob(0), latCell, lonCell, out)
            }
        }
        cells.put(key, out)
        return out
    }

    /** See the format in tools/speedlimits/build_speedlimits.py. */
    private fun decode(data: ByteArray, latCell: Int, lonCell: Int, out: MutableList<Run>) {
        var pos = 0
        fun varint(): Long {
            var shift = 0; var v = 0L
            while (true) {
                val b = data[pos++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return v
                shift += 7
            }
        }
        fun signed(): Long { val z = varint(); return (z ushr 1) xor -(z and 1) }
        while (pos < data.size) {
            val n = varint().toInt()
            val kmh = varint().toInt()
            val lat = DoubleArray(n); val lon = DoubleArray(n)
            var la = latCell * 1000L; var lo = lonCell * 1000L
            for (i in 0 until n) {
                la += signed(); lo += signed()
                lat[i] = la / 1e5; lon[i] = lo / 1e5
            }
            out += Run(kmh, lat, lon)
        }
    }

    /** Area ids with speed limits online and when each was built; null when offline. */
    suspend fun available(): Map<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$RELEASE/index.json").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val areas = JSONObject(r.body!!.string()).getJSONObject("areas")
                areas.keys().asSequence().associateWith { areas.getJSONObject(it).getString("built") }
            }
        }.getOrNull()
    }

    /** Which version of [id] is on the tablet: the date index.json gave it ("2026-10-11"), else its build date; null if none. */
    fun built(id: String): String? = file(id).takeIf { it.exists() }?.let { f ->
        File(dir, "$id.version").takeIf { it.exists() }?.readText()?.trim()?.let { return it }
        runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key = 'built'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        }.getOrNull()
    }

    /** Fetches the speed limits of [ids] that are online and missing or older here. */
    suspend fun update(ids: Collection<String>, available: Map<String, String>?) = withContext(Dispatchers.IO) {
        val online = available ?: return@withContext
        for (id in ids) {
            val newest = online[id] ?: continue
            val have = built(id)
            if (have != null && have >= newest) continue
            runCatching { download(id); File(dir, "$id.version").writeText(newest) }
                .onFailure { android.util.Log.w("SpeedLimits", "Download of $id failed", it) }
        }
    }

    private fun download(id: String) {
        val tmp = File(dir, "$id.sqlite.part")
        http.newCall(Request.Builder().url("$RELEASE/$id.sqlite.gz").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            GZIPInputStream(r.body!!.byteStream(), 1 shl 16).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        }
        reopen()
        file(id).delete()
        check(tmp.renameTo(file(id))) { "Could not move the speed limits into place" }
        reopen()
    }

    /** Each installed country's source and licence, for the credits: (area id, "Statens vegvesen, NVDB · NLOD 2.0 · 2026-10-11"). */
    fun sources(): List<Pair<String, String>> = dir.listFiles { f -> f.isFile && f.name.endsWith(".sqlite") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
        runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val meta = HashMap<String, String>()
                db.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
                f.nameWithoutExtension to listOfNotNull(meta["source"], meta["licence"], meta["built"]).joinToString(" · ")
            }
        }.getOrNull()
    }

    fun delete(id: String) {
        reopen()
        file(id).delete()
        File(dir, "$id.version").delete()
    }

    companion object {
        const val RELEASE = "https://github.com/atlehavik/curveon-data/releases/download/speedlimits"
        private const val KY = 110_574.0
        /** A road this close counts as the one the rider is on (GPS error plus half a wide road). */
        private const val MAX_OFFSET_M = 20.0
        private const val MAX_ANGLE_DEG = 40.0
    }
}
