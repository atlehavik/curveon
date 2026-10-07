package com.curveon.data.gpx

import com.curveon.data.model.GeoPoint
import com.curveon.data.model.GpxTrack
import com.curveon.data.osm.GpxParser
import org.json.JSONArray
import org.json.JSONObject

/** Kinds of waypoint we can show and filter, from the GPX <sym> (or the name prefix TET uses). */
enum class PoiCategory(val label: String, val color: Int) {
    CAMPING("Camping", 0xFF2E7D32.toInt()),
    FUEL("Fuel", 0xFF00C853.toInt()),
    LODGING("Lodging", 0xFF8E24AA.toInt()),
    FOOD("Food", 0xFFFF7043.toInt()),
    SCENIC("Scenic", 0xFF039BE5.toInt()),
    INFO("Info", 0xFF90A4AE.toInt()),
    ALERT("Alert", 0xFFFF1744.toInt());

    companion object {
        fun of(symbol: String, name: String): PoiCategory {
            val s = (symbol + " " + name).lowercase()
            return when {
                "camp" in s -> CAMPING
                "gas" in s || "fuel" in s -> FUEL
                "lodg" in s || "hotel" in s || "hut" in s || "cabin" in s -> LODGING
                "restaurant" in s || "food" in s || "cafe" in s || s.startsWith("eat") || " eat_" in s -> FOOD
                "scenic" in s || "view" in s || "photo" in s -> SCENIC
                "alert" in s || "danger" in s || "warning" in s -> ALERT
                else -> INFO
            }
        }
    }
}

/**
 * What a waypoint is, finer than [PoiCategory]: decides the icon drawn on the map when zoomed
 * in. Each kind belongs to a category, which gives the colour and the filter.
 */
enum class PoiKind(val label: String, val category: PoiCategory) {
    CAMPING("Camping", PoiCategory.CAMPING),
    FUEL("Fuel", PoiCategory.FUEL),
    LODGING("Lodging", PoiCategory.LODGING),
    RESTAURANT("Restaurant", PoiCategory.FOOD),
    CAFE("Café", PoiCategory.FOOD),
    SHOP("Shop", PoiCategory.FOOD),
    SCENIC("Scenic", PoiCategory.SCENIC),
    PICNIC("Picnic area", PoiCategory.SCENIC),
    SWIMMING("Swimming", PoiCategory.SCENIC),
    MUSEUM("Museum", PoiCategory.SCENIC),
    FERRY("Ferry", PoiCategory.INFO),
    WATER("Drinking water", PoiCategory.INFO),
    TOILET("Toilet", PoiCategory.INFO),
    PARKING("Parking", PoiCategory.INFO),
    TOWN("Town", PoiCategory.INFO),
    INFO("Info", PoiCategory.INFO),
    ALERT("Alert", PoiCategory.ALERT);

    companion object {
        fun of(symbol: String, name: String): PoiKind {
            val sym = symbol.lowercase()
            val s = (symbol + " " + name).lowercase()
            return when {
                // The symbol says most; the name (TET uses "FUEL_…", "EAT_…") is the fallback.
                "ferry" in sym -> FERRY
                "shopping" in sym || "convenience" in sym || "grocery" in sym -> SHOP
                "picnic" in sym -> PICNIC
                "swim" in sym || "beach" in sym -> SWIMMING
                "museum" in sym -> MUSEUM
                "drinking water" in sym -> WATER
                "restroom" in sym || "toilet" in sym -> TOILET
                "parking" in sym -> PARKING
                "city" in sym -> TOWN
                "cafe" in sym || "coffee" in sym -> CAFE
                else -> when (PoiCategory.of(symbol, name)) {
                    PoiCategory.CAMPING -> CAMPING
                    PoiCategory.FUEL -> FUEL
                    PoiCategory.LODGING -> LODGING
                    PoiCategory.FOOD -> if ("cafe" in s || "café" in s || "kaffe" in s) CAFE else RESTAURANT
                    PoiCategory.SCENIC -> SCENIC
                    PoiCategory.ALERT -> ALERT
                    PoiCategory.INFO -> if ("ferry" in s || "ferje" in s || "färja" in s) FERRY else INFO
                }
            }
        }
    }
}

/** A waypoint from a GPX file, with everything the file says about it. */
data class GpxPoi(
    val name: String,
    val description: String,
    val category: PoiCategory,
    val lat: Double,
    val lon: Double,
    val kind: PoiKind = PoiKind.of("", name),
    /** The name as written in the file ("CAMPING_Dokkvatnet"). */
    val rawName: String = name,
    val symbol: String = "",
    val comment: String = "",
    val links: List<Pair<String, String>> = emptyList(),
    val time: String = "",
    val address: List<String> = emptyList(),
    val phone: String = "",
    val categories: List<String> = emptyList(),
    val elevation: Double? = null,
    /** The track it came with. */
    val trackName: String = ""
)

/** A track drawn on the map, decoded once. */
data class OverlayTrack(
    val id: Long,
    val name: String,
    val color: Int,
    val points: List<GeoPoint>,
    val pois: List<GpxPoi>
)

object GpxOverlay {
    /** Distinct colours that read well on both the day and night map. */
    val PALETTE = listOf(
        0xFFFF6D00.toInt(), 0xFFE040FB.toInt(), 0xFF76FF03.toInt(), 0xFFFFD600.toInt(),
        0xFFFF1744.toInt(), 0xFF18FFFF.toInt(), 0xFF2979FF.toInt(), 0xFFFFFFFF.toInt()
    )

    fun colorOf(t: GpxTrack): Int = if (t.color != 0) t.color else PALETTE[(t.id % PALETTE.size).toInt()]

    /** The track as drawn: in its group's colour and direction when the group says so. */
    fun overlay(t: GpxTrack, g: com.curveon.data.model.GpxGroup? = null): OverlayTrack = OverlayTrack(
        id = t.id,
        name = t.name,
        color = GpxGroups.color(t, g),
        points = GpxParser.decodePoints(t.trackPointsJson).let { if (GpxGroups.reversed(t, g)) it.reversed() else it },
        pois = if (t.showPois) decodePois(t.waypointsJson).map { it.copy(trackName = t.name) } else emptyList()
    )

    fun encodePois(pois: List<GpxParser.GpxWaypoint>): String = JSONArray(pois.map { w ->
        JSONObject().put("name", w.name).put("desc", w.description).put("sym", w.symbol)
            .put("lat", w.point.latitude).put("lon", w.point.longitude).apply {
                if (w.comment.isNotEmpty()) put("cmt", w.comment)
                if (w.links.isNotEmpty()) put("links", JSONArray(w.links.map { (href, text) -> JSONObject().put("href", href).put("text", text) }))
                if (w.time.isNotEmpty()) put("time", w.time)
                if (w.type.isNotEmpty()) put("type", w.type)
                if (w.address.isNotEmpty()) put("addr", JSONArray(w.address))
                if (w.phone.isNotEmpty()) put("phone", w.phone)
                if (w.categories.isNotEmpty()) put("cats", JSONArray(w.categories))
                if (w.hasElevation) put("ele", w.point.altitudeMeters)
            }
    }).toString()

    /** Back to waypoints, for writing the file out again. */
    fun decodeWaypoints(json: String): List<GpxParser.GpxWaypoint> = objects(json).map { o ->
        GpxParser.GpxWaypoint(
            name = o.getString("name"),
            point = com.curveon.data.model.GeoPoint(o.getDouble("lat"), o.getDouble("lon"), o.optDouble("ele", 0.0)),
            description = o.optString("desc"), symbol = o.optString("sym"), comment = o.optString("cmt"),
            links = o.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { l -> l.getString("href") to l.optString("text") } } }.orEmpty(),
            time = o.optString("time"), type = o.optString("type"), address = strings(o.optJSONArray("addr")),
            phone = o.optString("phone"), categories = strings(o.optJSONArray("cats")), hasElevation = o.has("ele")
        )
    }

    fun decodePois(json: String): List<GpxPoi> = decodeWaypoints(json).map { w ->
        val kind = PoiKind.of(w.symbol, w.name)
        GpxPoi(
            name = cleanName(w.name) ?: kind.label, description = w.description, category = kind.category,
            lat = w.point.latitude, lon = w.point.longitude, kind = kind, rawName = w.name, symbol = w.symbol,
            comment = w.comment, links = w.links, time = w.time, address = w.address, phone = w.phone,
            categories = w.categories, elevation = w.point.altitudeMeters.takeIf { w.hasElevation }
        )
    }

    private fun objects(json: String): List<JSONObject> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    fun poiCount(t: GpxTrack) = if (t.waypointsJson.startsWith("[")) decodePois(t.waypointsJson).size else 0

    /**
     * "FUEL_B Cirkcle K Vågå" → "Cirkcle K Vågå", "INFO_Ferry to Geiranger" → "Ferry to Geiranger".
     * Null when only the prefix is there ("FUEL_"), so the category name is used instead.
     */
    internal fun cleanName(raw: String): String? =
        raw.replace(Regex("^[A-Z]{2,8}(_[A-Z])?(_|\\s|$)+"), "").trim().takeIf { it.isNotEmpty() }
}
