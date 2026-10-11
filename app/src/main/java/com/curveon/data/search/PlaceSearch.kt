package com.curveon.data.search

import android.content.Context
import com.curveon.R
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.curveon.routing.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
 * Destination search. Offline first, over the search index of every downloaded country (see
 * [SearchIndexes]); when the tablet is online (phone hotspot) online results are worked in (see [search]).
 */
class PlaceSearch(private val context: Context) {

    val indexes = SearchIndexes(context).also { it.onChange = ::reload }

    val indexDir: File get() = indexes.dir

    @Volatile private var dbs: List<SQLiteDatabase>? = null

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    }

    fun hasOfflineIndex() = indexes.installed().isNotEmpty()

    fun describe(): String {
        val files = indexes.installed()
        return if (files.isEmpty()) context.getString(R.string.search_index_none)
        else context.resources.getQuantityString(R.plurals.search_index_installed, files.size, files.size, files.sumOf { it.length() } / 1e6)
    }

    /** Closes the open indexes, so added or removed countries are picked up. */
    @Synchronized
    fun reload() {
        dbs?.forEach { runCatching { it.close() } }
        dbs = null
    }

    /**
     * Offline results at once, then (when the tablet is online) the same list with online results
     * worked in: Photon/OpenStreetMap everywhere, plus Kartverket's place names and addresses in Norway.
     */
    fun search(query: String, nearLat: Double, nearLon: Double, limit: Int = 30): Flow<List<SearchResult>> = flow {
        val q = query.trim()
        if (q.length < 2) { emit(emptyList()); return@flow }
        val parsed = parseQuery(q)
        val offline = runCatching { scoredOffline(parsed, nearLat, nearLon) }.getOrDefault(emptyList())
        emit(offline.map { it.first }.take(limit))
        if (!isOnline()) return@flow
        val sources = buildList<() -> List<SearchResult>> {
            add { searchPhoton(q, nearLat, nearLon) }
            if (inNorway(nearLat, nearLon)) {
                add { searchPlaceNames(q, nearLat, nearLon) }
                // Kartverket's addresses are already offline once Norway is downloaded.
                if (parsed.houseNumber != null && offline.none { it.first.kind == "street" }) add { searchAddresses(q, nearLat, nearLon) }
            }
        }
        val online = coroutineScope { sources.map { async { runCatching(it).getOrDefault(emptyList()) } }.awaitAll() }.flatten()
        if (online.isEmpty()) return@flow
        val merged = offline.toMutableList()
        online.map { it to score(it, onlineRank(it, parsed), parsed) }.sortedByDescending { it.second }.forEach { (o, sc) ->
            val n = normalise(o.name)
            if (merged.none { normalise(it.first.name) == n && Geo.distance(it.first.lat, it.first.lon, o.lat, o.lon) < 1000 }) merged += o to sc
        }
        emit(merged.sortedByDescending { it.second }.map { it.first }.take(limit))
    }.flowOn(Dispatchers.IO)

    @Synchronized
    private fun openDbs(): List<SQLiteDatabase> {
        dbs?.let { list -> if (list.all { it.isOpen }) return list }
        return indexes.installed().mapNotNull { f ->
            runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()
        }.also { dbs = it }
    }

    /** Offline matches, best first, with their scores (so online results can be worked in). */
    private fun scoredOffline(parsed: ParsedQuery, nearLat: Double, nearLon: Double): List<Pair<SearchResult, Double>> {
        if (parsed.terms.isEmpty()) return emptyList()
        val match = parsed.terms.joinToString(" ") { "$it*" }
        val rows = mutableListOf<Pair<SearchResult, Double>>()
        for (database in openDbs()) runCatching { database.rawQuery(
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
        } }
        return rows.sortedByDescending { it.second }.distinctBy { "${it.first.name}|${it.first.area}" }
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

    private fun getJson(url: okhttp3.HttpUrl): String? =
        http.newCall(Request.Builder().url(url).header("User-Agent", "Curveon/1.0").build()).execute()
            .use { if (it.isSuccessful) it.body?.string() else null }

    private fun searchPhoton(query: String, nearLat: Double, nearLon: Double): List<SearchResult> {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("lat", nearLat.toString())
            .addQueryParameter("lon", nearLon.toString())
            .addQueryParameter("limit", "15")
            .build()
        val features = JSONObject(getJson(url) ?: return emptyList()).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val street = p.optString("street").takeIf { it.isNotEmpty() }
            val number = p.optString("housenumber").takeIf { it.isNotEmpty() }
            val name = p.optString("name").takeIf { it.isNotEmpty() }
                ?: street?.let { s -> number?.let { "$s $it" } ?: s }
                ?: return@mapNotNull null
            val area = listOf(p.optString("city"), p.optString("county")).firstOrNull { it.isNotEmpty() } ?: p.optString("country")
            val kind = when {
                p.optString("osm_key") == "place" -> p.optString("osm_value")
                number != null && p.optString("name").isEmpty() -> "street"
                p.optString("osm_key") == "highway" && p.optString("type") == "street" -> "street"
                else -> PHOTON_KINDS[p.optString("osm_value")] ?: p.optString("osm_value", "place")
            }
            val lat = coords.getDouble(1)
            val lon = coords.getDouble(0)
            SearchResult(name, area, kind, lat, lon, Geo.distance(nearLat, nearLon, lat, lon) / 1000, online = true)
        }
    }

    /** Kartverket's place names (Sentralt stedsnavnregister): farms, bays, lakes, hills, … that OSM often lacks. */
    private fun searchPlaceNames(query: String, nearLat: Double, nearLon: Double): List<SearchResult> {
        val url = "https://ws.geonorge.no/stedsnavn/v1/navn".toHttpUrl().newBuilder()
            .addQueryParameter("sok", "$query*")
            .addQueryParameter("treffPerSide", "50")
            .addQueryParameter("utkoordsys", "4258")
            .build()
        val names = JSONObject(getJson(url) ?: return emptyList()).optJSONArray("navn") ?: return emptyList()
        return (0 until names.length()).mapNotNull { i ->
            val n = names.getJSONObject(i)
            val name = n.optString("skrivemåte").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val point = n.optJSONObject("representasjonspunkt") ?: return@mapNotNull null
            val lat = point.optDouble("nord").takeIf { !it.isNaN() } ?: return@mapNotNull null
            val lon = point.optDouble("øst").takeIf { !it.isNaN() } ?: return@mapNotNull null
            val area = n.optJSONArray("kommuner")?.optJSONObject(0)?.optString("kommunenavn").orEmpty()
            SearchResult(name, area, PLACE_NAME_KINDS[n.optString("navneobjekttype")] ?: "place", lat, lon,
                Geo.distance(nearLat, nearLon, lat, lon) / 1000, online = true)
        }
    }

    /** Kartverket's address register, for a house number when no country index has the street. */
    private fun searchAddresses(query: String, nearLat: Double, nearLon: Double): List<SearchResult> {
        val url = "https://ws.geonorge.no/adresser/v1/sok".toHttpUrl().newBuilder()
            .addQueryParameter("sok", query)
            .addQueryParameter("treffPerSide", "10")
            .addQueryParameter("utkoordsys", "4258")
            .build()
        val found = JSONObject(getJson(url) ?: return emptyList()).optJSONArray("adresser") ?: return emptyList()
        return (0 until found.length()).mapNotNull { i ->
            val a = found.getJSONObject(i)
            val point = a.optJSONObject("representasjonspunkt") ?: return@mapNotNull null
            val lat = point.optDouble("lat")
            val lon = point.optDouble("lon")
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            val area = "${a.optString("postnummer")} ${a.optString("poststed").lowercase().replaceFirstChar { it.uppercase() }}".trim()
            SearchResult(a.optString("adressetekst"), area, "street", lat, lon, Geo.distance(nearLat, nearLon, lat, lon) / 1000, online = true)
        }
    }

    /** Rank like the offline index gives the same kind of thing, so online and offline results mix fairly. */
    private fun onlineRank(r: SearchResult, q: ParsedQuery): Int = when (r.kind) {
        "street" -> if (q.houseNumber != null && r.name.any { it.isDigit() }) 90 else 10
        "farm" -> 25
        "lake", "bay" -> 25
        "place" -> 15
        else -> PLACE_RANKS[r.kind] ?: 35
    }

    /** A point of interest or camera near the route; [along] is filled in by the caller. */
    data class Poi(val name: String, val lat: Double, val lon: Double, val maxSpeed: Int? = null)

    /** Fuel stations inside a bounding box (for "next fuel along the route"). */
    suspend fun fuelInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Poi> = withContext(Dispatchers.IO) {
        openDbs().flatMap { db -> runCatching {
            db.rawQuery(
                "SELECT name, lat, lon FROM entries WHERE kind = 'fuel' AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
            ).use { c -> buildList { while (c.moveToNext()) add(Poi(c.getString(0), c.getDouble(1), c.getDouble(2))) } }
        }.getOrDefault(emptyList()) }.distinctBy { "${it.name}|${(it.lat * 2000).toInt()}|${(it.lon * 1000).toInt()}" }
    }

    /** Fixed speed cameras inside a bounding box. Empty with an index built before cameras were added. */
    suspend fun camerasInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Poi> = withContext(Dispatchers.IO) {
        openDbs().flatMap { db -> runCatching {
            db.rawQuery(
                "SELECT lat, lon, maxspeed FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
            ).use { c ->
                buildList { while (c.moveToNext()) add(Poi(context.getString(R.string.speed_camera), c.getDouble(0), c.getDouble(1), if (c.isNull(2)) null else c.getInt(2))) }
            }
        }.getOrDefault(emptyList()) }.distinctBy { "${(it.lat * 5000).toInt()}|${(it.lon * 2500).toInt()}" }
    }

    /** Places of these [kinds] inside a bounding box, the more notable first, at most [limit]. */
    suspend fun poisInBox(kinds: List<String>, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int = 400): List<MapPlace> =
        withContext(Dispatchers.IO) {
            if (kinds.isEmpty()) return@withContext emptyList()
            val kindSql = kinds.joinToString(",") { "?" }
            openDbs().flatMap { db -> runCatching {
                db.rawQuery(
                    "SELECT name, kind, lat, lon FROM entries WHERE kind IN ($kindSql) AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY rank DESC LIMIT $limit",
                    (kinds + listOf(minLat, maxLat, minLon, maxLon).map { it.toString() }).toTypedArray()
                ).use { c -> buildList { while (c.moveToNext()) add(MapPlace(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3))) } }
            }.getOrDefault(emptyList()) }.distinctBy { "${it.kind}|${it.name}|${(it.lat * 2000).toInt()}|${(it.lon * 1000).toInt()}" }.take(limit)
        }

    /** A place of the index shown on the map. */
    data class MapPlace(val name: String, val kind: String, val lat: Double, val lon: Double)

    /** Closest places of one kind, e.g. the nearest fuel stations. */
    suspend fun nearest(kind: String, lat: Double, lon: Double, limit: Int = 10): List<SearchResult> =
        nearest(listOf(kind), lat, lon, limit)

    /** Closest places of any of these kinds, e.g. restaurants, cafés and fast food for "dining". */
    suspend fun nearest(kinds: List<String>, lat: Double, lon: Double, limit: Int = 10): List<SearchResult> = withContext(Dispatchers.IO) {
        val databases = openDbs()
        if (databases.isEmpty()) return@withContext emptyList()
        val kindSql = kinds.joinToString(",") { "?" }
        var radius = 0.1
        var rows: List<SearchResult> = emptyList()
        while (radius <= 1.6 && rows.size < limit) { // widen the box until we have enough
            rows = databases.flatMap { db -> runCatching {
                db.rawQuery(
                    "SELECT name, area, lat, lon, kind FROM entries WHERE kind IN ($kindSql) AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                    (kinds + listOf((lat - radius).toString(), (lat + radius).toString(), (lon - radius * 2).toString(), (lon + radius * 2).toString())).toTypedArray()
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            SearchResult(c.getString(0), c.getString(1) ?: "", c.getString(4), c.getDouble(2), c.getDouble(3),
                                Geo.distance(lat, lon, c.getDouble(2), c.getDouble(3)) / 1000)
                        )
                    }
                }
            }.getOrDefault(emptyList()) }.distinctBy { "${it.name}|${(it.lat * 2000).toInt()}" }
            radius *= 2
        }
        rows.sortedBy { it.distanceKm }.take(limit)
    }

    internal data class ParsedQuery(val text: String, val terms: List<String>, val houseNumber: String?)

    companion object {
        private fun inNorway(lat: Double, lon: Double) = lat in 57.5..81.0 && lon in 4.0..31.5

        private val PLACE_RANKS = mapOf(
            "city" to 100, "town" to 90, "village" to 70, "suburb" to 60, "borough" to 60, "quarter" to 50,
            "hamlet" to 45, "neighbourhood" to 40, "island" to 40, "locality" to 30, "isolated_dwelling" to 20,
        )

        /** OSM values the offline index calls something else. */
        private val PHOTON_KINDS = mapOf(
            "charging_station" to "charging", "ferry_terminal" to "ferry", "camp_site" to "camp_site",
            "motel" to "hotel", "guest_house" to "hotel", "hostel" to "hotel", "alpine_hut" to "hut",
            "wilderness_hut" to "hut", "motorcycle" to "motorcycle_shop", "convenience" to "shop",
            "car_repair" to "workshop", "tyres" to "workshop",
        )

        /** Kartverket's place name types → our kinds; anything else is a plain "place". */
        private val PLACE_NAME_KINDS = mapOf(
            "By" to "city", "Tettsted" to "town", "Tettbebyggelse" to "village", "Bydel" to "suburb",
            "Bygdelag (bygd)" to "hamlet", "Grend" to "hamlet", "Gard" to "farm", "Bruk (gardsbruk)" to "farm",
            "Øy" to "island", "Øy i sjø" to "island", "Holme i sjø" to "island", "Halvøy i sjø" to "island",
            "Fjell" to "peak", "Topp" to "peak", "Fjellområde" to "peak", "Ås" to "peak", "Haug" to "peak", "Høyde" to "peak",
            "Innsjø" to "lake", "Vann" to "lake", "Tjern" to "lake", "Vik" to "bay", "Bukt i sjø" to "bay", "Fjord" to "bay",
            "Fjellovergang" to "pass", "Severdighet" to "attraction", "Utsiktspunkt" to "viewpoint",
            "Ferjekai" to "ferry", "Kai" to "ferry", "Vegstrekning" to "street", "Adressenavn" to "street",
        )

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
