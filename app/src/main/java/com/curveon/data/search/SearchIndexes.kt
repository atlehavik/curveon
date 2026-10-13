package com.curveon.data.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.curveon.R
import com.curveon.pmtiles.TileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * One offline search index per country (search/<area id>.sqlite), from one of two sources:
 *  - FULL: built monthly in the cloud from OpenStreetMap by the public curveon-data repo, with
 *    POIs, house numbers and speed cameras (same builder as tools/search/build_search_index.py);
 *  - MAP: built on the tablet from the downloaded map, for areas the cloud doesn't cover or when
 *    it can't be reached: places, streets and the POIs the map has, no house numbers or cameras.
 */
class SearchIndexes(private val context: Context) {

    enum class Source { NONE, MAP, FULL }

    val dir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "search").apply { mkdirs() }

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    init {
        // The first, PC-built Norway index had its own name.
        val legacy = File(dir, "norway-search.sqlite")
        if (legacy.exists() && !file("norway").exists()) legacy.renameTo(file("norway"))
    }

    fun file(id: String) = File(dir, "$id.sqlite")

    fun installed(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".sqlite") }?.sortedBy { it.name }.orEmpty()

    fun source(id: String): Source {
        val f = file(id)
        if (!f.exists()) return Source.NONE
        return runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key = 'source'", null).use { c -> if (c.moveToFirst() && c.getString(0) == SOURCE_MAP) Source.MAP else Source.FULL }
            }
        }.getOrDefault(Source.FULL)
    }

    /** Area ids with a full index in the cloud, and their sizes; null when offline. */
    suspend fun available(): Map<String, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$RELEASE/index.json").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val areas = JSONObject(r.body!!.string()).getJSONObject("areas")
                areas.keys().asSequence().associateWith { areas.getJSONObject(it).getLong("bytes") }
            }
        }.getOrNull()
    }

    /** The full index from the cloud when there is one, else one built from [map]. */
    suspend fun install(id: String, map: File, available: Map<String, Long>?, onProgress: (String, Float) -> Unit, isCancelled: () -> Boolean) {
        if (available?.containsKey(id) == true) {
            try {
                return download(id, onProgress, isCancelled)
            } catch (e: IOException) {
                if (source(id) != Source.NONE) throw e // keep what we have rather than downgrade it
            }
        }
        if (source(id) == Source.NONE) buildFromMap(id, map, onProgress, isCancelled)
    }

    /** Fetches [id]'s full index from the cloud, without its map. */
    suspend fun fetch(id: String, onProgress: (String, Float) -> Unit, isCancelled: () -> Boolean) = download(id, onProgress, isCancelled)

    private suspend fun download(id: String, onProgress: (String, Float) -> Unit, isCancelled: () -> Boolean) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "$id.sqlite.part")
        http.newCall(Request.Builder().url("$RELEASE/$id.sqlite.gz").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val total = r.body!!.contentLength()
            val counting = CountingStream(r.body!!.byteStream())
            GZIPInputStream(counting, 1 shl 16).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        check(!isCancelled()) { "Cancelled" }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (counting.count - lastReport > 1_000_000) {
                            lastReport = counting.count
                            onProgress(context.getString(R.string.search_downloading, counting.count / 1e6, total / 1e6), if (total > 0) counting.count.toFloat() / total else 0f)
                        }
                    }
                }
            }
        }
        replace(id, tmp)
    }

    /** Places, POIs and street names from the map's own tiles. */
    suspend fun buildFromMap(id: String, map: File, onProgress: (String, Float) -> Unit, isCancelled: () -> Boolean) = withContext(Dispatchers.IO) {
        val entries = TileNames(map).read({ done, total ->
            onProgress(context.getString(R.string.search_building, if (total > 0) 100 * done / total else 0), if (total > 0) 0.8f * done / total else 0f)
        }, isCancelled)
        onProgress(context.getString(R.string.search_saving), 0.85f)
        val tmp = File(dir, "$id.sqlite.part").apply { delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(tmp, null)
        try {
            db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, area TEXT, rank INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL)")
            db.execSQL("CREATE VIRTUAL TABLE entries_fts USING fts4(search)")
            db.execSQL("CREATE TABLE housenumbers(entry_id INTEGER NOT NULL, number TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL)")
            db.execSQL("CREATE TABLE cameras(lat REAL NOT NULL, lon REAL NOT NULL, maxspeed INTEGER)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
            db.beginTransaction()
            val insert = db.compileStatement("INSERT INTO entries VALUES (?,?,?,?,?,?,?)")
            val fts = db.compileStatement("INSERT INTO entries_fts(docid, search) VALUES (?,?)")
            entries.forEachIndexed { i, e ->
                if (i % 20_000 == 0) check(!isCancelled()) { "Cancelled" }
                val rowId = i + 1L
                insert.bindLong(1, rowId); insert.bindString(2, e.name); insert.bindString(3, e.kind); insert.bindString(4, e.area)
                insert.bindLong(5, e.rank.toLong()); insert.bindDouble(6, e.lat); insert.bindDouble(7, e.lon)
                insert.executeInsert()
                fts.bindLong(1, rowId); fts.bindString(2, PlaceSearch.normalise("${e.name} ${e.area}"))
                fts.executeInsert()
            }
            db.execSQL("INSERT INTO meta VALUES ('source', '$SOURCE_MAP')")
            db.execSQL("INSERT INTO meta VALUES ('built', date('now'))")
            db.setTransactionSuccessful()
            db.endTransaction()
            onProgress(context.getString(R.string.search_saving), 0.95f)
            db.execSQL("CREATE INDEX entries_kind_lat ON entries(kind, lat)")
            db.execSQL("INSERT INTO entries_fts(entries_fts) VALUES('optimize')")
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
        }
        File(dir, "$id.sqlite.part-journal").delete()
        replace(id, tmp)
    }

    fun delete(id: String) {
        file(id).delete()
        File(dir, "$id.sqlite.part").delete()
        onChange()
    }

    fun cleanupPartial(id: String) {
        File(dir, "$id.sqlite.part").delete()
        File(dir, "$id.sqlite.part-journal").delete()
    }

    /** Called after indexes change, so open databases get reopened. */
    var onChange: () -> Unit = {}

    private fun replace(id: String, tmp: File) {
        val target = file(id)
        target.delete()
        check(tmp.renameTo(target)) { "Could not move the search index into place" }
        onChange()
    }

    private class CountingStream(private val input: java.io.InputStream) : java.io.FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        const val RELEASE = "https://github.com/atlehavik/curveon-data/releases/download/search"
        private const val SOURCE_MAP = "map"
    }
}
