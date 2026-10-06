package com.vibenav.data.gpx

import com.vibenav.data.model.GeoPoint
import com.vibenav.data.model.GpxTrack
import com.vibenav.data.osm.GpxParser
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

data class GpxPoi(val name: String, val description: String, val category: PoiCategory, val lat: Double, val lon: Double)

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

    fun overlay(t: GpxTrack): OverlayTrack = OverlayTrack(
        id = t.id,
        name = t.name,
        color = colorOf(t),
        points = GpxParser.decodePoints(t.trackPointsJson),
        pois = if (t.showPois) decodePois(t.waypointsJson) else emptyList()
    )

    fun encodePois(pois: List<GpxParser.GpxWaypoint>): String = JSONArray(pois.map { w ->
        JSONObject().put("name", w.name).put("desc", w.description).put("sym", w.symbol)
            .put("lat", w.point.latitude).put("lon", w.point.longitude)
    }).toString()

    fun decodePois(json: String): List<GpxPoi> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val raw = o.getString("name")
            val category = PoiCategory.of(o.optString("sym"), raw)
            GpxPoi(cleanName(raw) ?: category.label, o.optString("desc"), category, o.getDouble("lat"), o.getDouble("lon"))
        }
    }.getOrDefault(emptyList())

    fun poiCount(t: GpxTrack) = if (t.waypointsJson.startsWith("[")) decodePois(t.waypointsJson).size else 0

    /**
     * "FUEL_B Cirkcle K Vågå" → "Cirkcle K Vågå", "INFO_Ferry to Geiranger" → "Ferry to Geiranger".
     * Null when only the prefix is there ("FUEL_"), so the category name is used instead.
     */
    internal fun cleanName(raw: String): String? =
        raw.replace(Regex("^[A-Z]{2,8}(_[A-Z])?(_|\\s|$)+"), "").trim().takeIf { it.isNotEmpty() }
}
