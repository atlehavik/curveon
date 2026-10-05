package com.vibenav.data.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vibenav.routing.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.ln

data class SearchResult(
    val name: String,
    val area: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
    val online: Boolean = false
)

/**
 * Destination search. Offline first, from the index built by tools/search/build_search_index.py
 * (Android/data/com.vibenav.tablet/files/search/norway-search.sqlite); when the tablet is online
 * (phone hotspot) Photon/OpenStreetMap results are added.
 */
class PlaceSearch(private val context: Context) {

    val indexDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "search").apply { mkdirs() }

    private val indexFile get() = File(indexDir, INDEX_FILE)

    private var db: SQLiteDatabase? = null

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    }

    fun hasOfflineIndex() = indexFile.exists()

    fun describe(): String =
        if (hasOfflineIndex()) "Offline search index installed (%.0f MB)".format(indexFile.length() / 1e6)
        else "No offline search index. Copy $INDEX_FILE to $indexDir. Online search works when connected."

    suspend fun search(query: String, nearLat: Double, nearLon: Double, limit: Int = 30): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2) return@withContext emptyList()
            val offline = runCatching { searchOffline(q, nearLat, nearLon, limit) }.getOrDefault(emptyList())
            val online = if (offline.size < 5 && isOnline()) runCatching { searchOnline(q, nearLat, nearLon) }.getOrDefault(emptyList()) else emptyList()
            (offline + online.filter { o -> offline.none { it.name == o.name && Geo.distance(it.lat, it.lon, o.lat, o.lon) < 300 } })
                .take(limit)
        }

    private fun openDb(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        if (!indexFile.exists()) return null
        return SQLiteDatabase.openDatabase(indexFile.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    internal fun searchOffline(query: String, nearLat: Double, nearLon: Double, limit: Int): List<SearchResult> {
        val database = openDb() ?: return emptyList()
        val parsed = parseQuery(query)
        if (parsed.terms.isEmpty()) return emptyList()
        val match = parsed.terms.joinToString(" ") { "$it*" }
        val rows = mutableListOf<Pair<SearchResult, Double>>()
        database.rawQuery(
            """SELECT e.id, e.name, e.kind, e.area, e.rank, e.lat, e.lon FROM entries_fts f
               JOIN entries e ON e.id = f.docid WHERE f.search MATCH ? LIMIT 400""",
            arrayOf(match)
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                var name = c.getString(1)
                val kind = c.getString(2)
                var lat = c.getDouble(5)
                var lon = c.getDouble(6)
                if (kind == "street" && parsed.houseNumber != null) {
                    val hit = houseNumber(database, id, parsed.houseNumber) ?: continue
                    name = "$name ${hit.first}"
                    lat = hit.second; lon = hit.third
                }
                val d = Geo.distance(nearLat, nearLon, lat, lon) / 1000.0
                val result = SearchResult(name, c.getString(3) ?: "", kind, lat, lon, d)
                // A typed house number means the rider wants that address, not shops on the street.
                val addressBonus = if (kind == "street" && parsed.houseNumber != null) 80 else 0
                rows += result to score(result, c.getInt(4) + addressBonus, parsed)
            }
        }
        return rows.sortedByDescending { it.second }.map { it.first }.distinctBy { "${it.name}|${it.area}" }.take(limit)
    }

    /** The requested house number, or the numerically closest one on that street. */
    private fun houseNumber(db: SQLiteDatabase, entryId: Long, number: String): Triple<String, Double, Double>? {
        val wanted = number.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        var best: Triple<String, Double, Double>? = null
        var bestScore = Int.MAX_VALUE
        db.rawQuery("SELECT number, lat, lon FROM housenumbers WHERE entry_id = ?", arrayOf(entryId.toString())).use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)
                val score = if (n.equals(number, ignoreCase = true)) -1
                else n.takeWhile { it.isDigit() }.toIntOrNull()?.let { kotlin.math.abs(it - wanted) * 2 + (if (n.length > it.toString().length) 1 else 0) }
                    ?: continue
                if (score < bestScore) {
                    bestScore = score
                    best = Triple(n, c.getDouble(1), c.getDouble(2))
                }
            }
        }
        // Don't send the rider to the far end of a long street for a number that doesn't exist.
        return best?.takeIf { bestScore <= 20 }
    }

    private fun score(r: SearchResult, rank: Int, q: ParsedQuery): Double {
        val n = normalise(r.name)
        val nameBonus = when {
            n == q.text -> 60.0
            n.startsWith(q.text) -> 40.0
            n.startsWith(q.terms.first()) -> 20.0
            else -> 0.0
        }
        // Nearby things win, but a city 300 km away still beats a farm with the same name next door.
        return rank + nameBonus - 12.0 * ln(1.0 + r.distanceKm)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun searchOnline(query: String, nearLat: Double, nearLon: Double): List<SearchResult> {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("lat", nearLat.toString())
            .addQueryParameter("lon", nearLon.toString())
            .addQueryParameter("limit", "10")
            .build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", "VibeNav/1.0").build()).execute()
            .use { if (it.isSuccessful) it.body?.string() else null } ?: return emptyList()
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val street = p.optString("street").takeIf { it.isNotEmpty() }
            val name = p.optString("name").takeIf { it.isNotEmpty() }
                ?: street?.let { s -> p.optString("housenumber").takeIf { it.isNotEmpty() }?.let { "$s $it" } ?: s }
                ?: return@mapNotNull null
            val area = listOf(p.optString("city"), p.optString("county")).firstOrNull { it.isNotEmpty() } ?: p.optString("country")
            val lat = coords.getDouble(1)
            val lon = coords.getDouble(0)
            SearchResult(name, area, p.optString("osm_value", "place"), lat, lon, Geo.distance(nearLat, nearLon, lat, lon) / 1000, online = true)
        }
    }

    /** A point of interest or camera near the route; [along] is filled in by the caller. */
    data class Poi(val name: String, val lat: Double, val lon: Double, val maxSpeed: Int? = null)

    /** Fuel stations inside a bounding box (for "next fuel along the route"). */
    suspend fun fuelInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Poi> = withContext(Dispatchers.IO) {
        val db = openDb() ?: return@withContext emptyList()
        runCatching {
            db.rawQuery(
                "SELECT name, lat, lon FROM entries WHERE kind = 'fuel' AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
            ).use { c -> buildList { while (c.moveToNext()) add(Poi(c.getString(0), c.getDouble(1), c.getDouble(2))) } }
        }.getOrDefault(emptyList())
    }

    /** Fixed speed cameras inside a bounding box. Empty with an index built before cameras were added. */
    suspend fun camerasInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Poi> = withContext(Dispatchers.IO) {
        val db = openDb() ?: return@withContext emptyList()
        runCatching {
            db.rawQuery(
                "SELECT lat, lon, maxspeed FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
            ).use { c ->
                buildList { while (c.moveToNext()) add(Poi("Speed camera", c.getDouble(0), c.getDouble(1), if (c.isNull(2)) null else c.getInt(2))) }
            }
        }.getOrDefault(emptyList())
    }

    /** Closest places of one kind, e.g. the nearest fuel stations. */
    suspend fun nearest(kind: String, lat: Double, lon: Double, limit: Int = 10): List<SearchResult> = withContext(Dispatchers.IO) {
        val db = openDb() ?: return@withContext emptyList()
        var radius = 0.1
        var rows: List<SearchResult> = emptyList()
        while (radius <= 1.6 && rows.size < limit) { // widen the box until we have enough
            rows = runCatching {
                db.rawQuery(
                    "SELECT name, area, lat, lon FROM entries WHERE kind = ? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                    arrayOf(kind, (lat - radius).toString(), (lat + radius).toString(), (lon - radius * 2).toString(), (lon + radius * 2).toString())
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            SearchResult(c.getString(0), c.getString(1) ?: "", kind, c.getDouble(2), c.getDouble(3),
                                Geo.distance(lat, lon, c.getDouble(2), c.getDouble(3)) / 1000)
                        )
                    }
                }
            }.getOrDefault(emptyList())
            radius *= 2
        }
        rows.sortedBy { it.distanceKm }.take(limit)
    }

    internal data class ParsedQuery(val text: String, val terms: List<String>, val houseNumber: String?)

    companion object {
        const val INDEX_FILE = "norway-search.sqlite"

        private val HOUSE_NUMBER = Regex("^\\d{1,4}[a-z]?$")

        /** Same normalisation as the index builder: lowercase, æ→ae, ø→o, å→a, no accents or punctuation. */
        fun normalise(s: String): String {
            val lowered = s.lowercase()
                .replace("æ", "ae").replace("ø", "o").replace("å", "a").replace("ß", "ss")
            val stripped = Normalizer.normalize(lowered, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            return stripped.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex("\\s+"), " ")
        }

        internal fun parseQuery(query: String): ParsedQuery {
            val tokens = normalise(query).split(' ').filter { it.isNotEmpty() }
            val number = tokens.drop(1).firstOrNull { HOUSE_NUMBER.matches(it) }
            val terms = tokens.filter { it != number }
            return ParsedQuery(terms.joinToString(" "), terms, number)
        }
    }
}
