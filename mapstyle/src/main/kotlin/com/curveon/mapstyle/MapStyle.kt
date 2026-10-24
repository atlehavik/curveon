package com.curveon.mapstyle

import org.json.JSONArray
import org.json.JSONObject

/**
 * The map's style as Curveon draws it, built from the Protomaps style (assets/map/styles/{light,dark}.json) and the
 * online one (online-*.json): one source per downloaded map with the layers copied for each, the online layers under
 * them all, road and place names placed and coloured, names on top, and the roads in the rider's contrast. Plain JSON
 * in and out, so the app (OfflineMaps) and the web planner (webplanner, Styles) draw the same map.
 */
object MapStyle {

    /**
     * The style for [flavor] ("dark" or "light") over the maps at [maps] (pmtiles:// urls, the first keeps the
     * layer ids). The online layers are hidden when there are maps, until the view is found not covered by one.
     * The day map draws the white roads [road] grey with no edge (light paths [path]) and the main roads' edge dark.
     */
    fun build(flavor: String, base: String, onlineStyle: String, maps: List<String>, road: String = HC_ROAD, path: String = HC_PATH): String {
        val style = JSONObject(base)
        val online = JSONObject(onlineStyle)
        val sources = style.getJSONObject("sources")
        val template = sources.getJSONObject(SOURCE)
        sources.remove(SOURCE)
        maps.forEachIndexed { i, url ->
            sources.put(sourceName(i), JSONObject(template.toString()).put("url", url))
        }
        sources.put(ONLINE_SOURCE, online.getJSONObject("source"))
        val layers = style.getJSONArray("layers")
        var out = JSONArray()
        for (k in 0 until layers.length()) {
            val layer = layers.getJSONObject(k)
            if (layer.optString("source") != SOURCE) {
                out.put(layer)
                if (layer.getString("id") == "background") {
                    val onlineLayers = online.getJSONArray("layers")
                    for (j in 0 until onlineLayers.length()) out.put(onlineLayers.getJSONObject(j).apply {
                        // Hidden until the map has checked the view isn't covered by a downloaded map.
                        if (maps.isNotEmpty()) put("layout", (optJSONObject("layout") ?: JSONObject()).put("visibility", "none"))
                    })
                }
                continue
            }
            for (i in maps.indices) {
                // The first map keeps the original ids (other code looks layers up by them).
                out.put(JSONObject(layer.toString()).put("source", sourceName(i)).apply { if (i > 0) put("id", "${layer.getString("id")}~$i") })
            }
        }
        for (k in 0 until out.length()) { roadLabels(out.getJSONObject(k)); placeHalo(out.getJSONObject(k), flavor); roadNameColours(out.getJSONObject(k), flavor) }
        // Names last, so no road is drawn over them (and their halo): the place names, and the online map's
        // labels, which would otherwise lie under the overlay lines (tarmac and gravel dashes) added over the offline map.
        val rest = JSONArray(); val names = JSONArray()
        for (k in 0 until out.length()) out.getJSONObject(k).let {
            val id = it.getString("id").substringBefore('~')
            if (id.startsWith("places_") || (id.startsWith("online-") && it.optString("type") == "symbol")) names.put(it) else rest.put(it)
        }
        for (k in 0 until names.length()) rest.put(names.getJSONObject(k))
        out = rest
        for (k in 0 until out.length()) {
            val layer = out.getJSONObject(k)
            if (flavor == "light") highContrast(layer, road, path) else nightRoads(layer, road, path)
            minorRoadsFromSurfaceZoom(layer)
        }
        style.put("layers", out)
        return style.toString()
    }

    /** Place names get a wide halo (the map's land colour; white text on the night map), so a road under them doesn't show between the letters. */
    private fun placeHalo(layer: JSONObject, flavor: String) {
        val id = layer.getString("id").substringBefore('~')
        if (!id.startsWith("places_") && id != "online-places") return
        val paint = layer.optJSONObject("paint") ?: return
        paint.put("text-halo-width", PLACE_HALO)
        if (flavor == "light") paint.put("text-halo-color", LAND_LIGHT)
        else { paint.put("text-halo-color", LAND_DARK); paint.put("text-color", "#ffffff") }
    }

    /** Road names on the map's land colour (white text at night), like the place names. */
    private fun roadNameColours(layer: JSONObject, flavor: String) {
        val id = layer.getString("id").substringBefore('~')
        if (id != "roads_labels_minor" && id != "roads_labels_major" && id != "online-roads_labels") return
        val paint = layer.optJSONObject("paint") ?: return
        paint.put("text-halo-width", ROAD_NAME_HALO)
        if (flavor == "dark") { paint.put("text-color", "#ffffff"); paint.put("text-halo-color", LAND_DARK) }
        else paint.put("text-halo-color", LAND_LIGHT)
    }

    /**
     * Road names: all from the same zoom (the closest in of the layers' own, so none shows earlier than
     * the rest), and beside the road instead of across it.
     */
    private fun roadLabels(layer: JSONObject) {
        val id = layer.getString("id").substringBefore('~')
        if (id != "roads_labels_minor" && id != "roads_labels_major" && id != "online-roads_labels") return
        layer.put("minzoom", ROAD_LABEL_ZOOM)
        val layout = layer.optJSONObject("layout") ?: return
        layout.put("text-offset", JSONArray().put(0).put(1.15))
    }

    /**
     * One layer of the day style in high contrast: the white roads and light paths a flat dark grey (the
     * same at every zoom, with no edge), the main roads' edge a near-black grey.
     */
    private fun highContrast(layer: JSONObject, road: String, path: String) {
        val paint = layer.optJSONObject("paint") ?: return
        val id = layer.getString("id").substringBefore('~')
        when (id) {
            // Place names: dark text on a wide white halo, so they stay readable over the dark roads.
            "places_subplace", "places_locality", "places_region", "online-places" -> {
                paint.put("text-color", "#1f1f1f"); paint.put("text-halo-color", LAND_LIGHT)
            }
            "roads_minor", "roads_minor_service", "roads_bridges_minor", "online-roads_minor" -> { paint.put("line-color", road); atLeast(paint, HC_MIN_WIDTH) }
            "roads_other", "roads_bridges_other", "online-roads_other" -> { paint.put("line-color", path); atLeast(paint, HC_MIN_WIDTH) }
            "roads_minor_casing", "roads_minor_service_casing", "roads_bridges_minor_casing", "online-roads_minor_casing" ->
                // Not hidden by visibility: the online layers' visibility is switched by the map view.
                paint.put("line-opacity", 0)
            "roads_link_casing", "roads_major_casing_early", "roads_major_casing_late", "roads_highway_casing_early", "roads_highway_casing_late",
            "roads_bridges_link_casing", "roads_bridges_major_casing", "roads_bridges_highway_casing",
            "online-roads_major_casing", "online-roads_highway_casing" -> {
                paint.put("line-color", HC_EDGE_COLOR)
                // The edge grows with the zoom, from a hairline far out to full width close in (from where it starts).
                val from = when (val w = paint.opt("line-width")) {
                    is JSONArray -> w.optDouble(3, 0.0)
                    else -> layer.optDouble("minzoom", 0.0)
                }
                paint.put("line-width", edgeWidth(from))
            }
        }
    }

    /**
     * Keeps a line at least [min] px wide wherever it is drawn at all: a thinner one is only part-covered
     * pixels, and a dark grey one fades to nearly white on the light map when zoomed out.
     */
    private fun edgeWidth(from: Double): JSONArray {
        // (zoom, px): thin where the whole map is in view, full width when riding close in.
        val steps = listOf(7.0 to 0.2, 10.0 to 0.4, 12.0 to 0.8, 14.0 to 1.2, 16.0 to HC_EDGE)
        fun at(z: Double): Double {
            val hi = steps.indexOfFirst { it.first >= z }
            return when {
                hi == 0 -> steps[0].second
                hi < 0 -> steps.last().second
                else -> { val (z0, v0) = steps[hi - 1]; val (z1, v1) = steps[hi]; v0 + (v1 - v0) * (z - z0) / (z1 - z0) }
            }
        }
        val out = JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
        out.put(from).put(0)
        out.put(from + 0.5).put(at(from + 0.5))
        for ((z, v) in steps) if (z > from + 0.5) out.put(z).put(v)
        return out
    }

    private fun atLeast(paint: JSONObject, min: Double) {
        when (val w = paint.opt("line-width")) {
            is JSONArray -> for (i in 4 until w.length() step 2) { val v = w.optDouble(i, 0.0); if (v > 0.0 && v < min) w.put(i, min) }
            is Number -> if (w.toDouble() in 0.0..min) paint.put("line-width", min)
        }
    }

    /**
     * The night map's small roads and paths are never thinner than the day map's (see [highContrast]): the
     * hair-thin ones fade out, and a gravel dash that ends in nothing looks like it joins no road. Their
     * colours are [road] and [path] (white and light grey in high contrast, a little softer in normal);
     * tunnels keep their own.
     */
    private fun nightRoads(layer: JSONObject, road: String, path: String) {
        val paint = layer.optJSONObject("paint") ?: return
        when (layer.getString("id").substringBefore('~')) {
            "roads_minor", "roads_minor_service", "roads_bridges_minor", "online-roads_minor" -> { atLeast(paint, HC_MIN_WIDTH); nightColour(paint, road) }
            "roads_other", "roads_bridges_other", "online-roads_other" -> { atLeast(paint, HC_MIN_WIDTH); nightColour(paint, path) }
        }
    }

    /** The line colour [colour] (the online layers keep their tunnel colour: a "case" with the road colour last). */
    private fun nightColour(paint: JSONObject, colour: String) {
        if (colour == NIGHT_ROAD_HC) return // the style's own
        val old = paint.opt("line-color")
        if (old is JSONArray && old.optString(0) == "case") old.put(old.length() - 1, colour) else paint.put("line-color", colour)
    }

    /**
     * The small roads are drawn from the zoom the gravel and offroad dashes start at (day and night map), at least
     * [HC_MIN_WIDTH] wide from there: a dashed road always meets a road on the map, not bare ground.
     */
    private fun minorRoadsFromSurfaceZoom(layer: JSONObject) {
        val id = layer.getString("id").substringBefore('~')
        if (id != "roads_minor" && id != "online-roads_minor") return
        val w = layer.optJSONObject("paint")?.optJSONArray("line-width") ?: return
        for (i in 4 until w.length() step 2) if (w.optDouble(i - 1, 0.0) >= SURFACE_ZOOM && w.optDouble(i, 0.0) < HC_MIN_WIDTH) w.put(i, HC_MIN_WIDTH)
    }

    private fun sourceName(i: Int) = if (i == 0) SOURCE else "$SOURCE$i"

    private const val SOURCE = "protomaps"
    /** High contrast: the greys that white roads and light paths become, and the main roads' black edge width (px). */
    const val HC_ROAD = "#707070"
    const val HC_PATH = "#a0a0a0"
    /** Normal contrast (the default): the day map's small roads and light paths a softer grey. */
    const val DAY_ROAD = "#9a9a9a"
    const val DAY_PATH = "#c4c4c4"
    /** The night map's small roads and paths: white and light grey (high contrast, as the style draws them), or softer. */
    const val NIGHT_ROAD_HC = "#ffffff"
    const val NIGHT_PATH_HC = "#d4d4d4"
    const val NIGHT_ROAD = "#d0d0d0"
    const val NIGHT_PATH = "#a6a6a6"
    private const val HC_EDGE = 1.5
    /** The main roads' edge: dark, but not black, so it isn't so harsh next to the grey roads. */
    private const val HC_EDGE_COLOR = "#404040"
    /** The day map's land colour: place names sit on a halo of it, so they blend in instead of standing out white. */
    const val LAND_LIGHT = "#e2dfda"
    /** The night map's land colour (the dark styles' earth and background). */
    const val LAND_DARK = "#1f1f1f"
    private const val ROAD_NAME_HALO = 2.0
    /** The width of the halo round place names (px). */
    private const val PLACE_HALO = 3.0
    /** Road names show from this zoom, all of them. */
    private const val ROAD_LABEL_ZOOM = 15
    /** The thinnest the grey roads and paths are drawn, at any zoom (px). */
    private const val HC_MIN_WIDTH = 1.6
    /** Where the gravel and offroad dashes start on the map (fast setting): the small roads start here too. */
    private const val SURFACE_ZOOM = 11.0

    /** The online layers' source; their ids start with "online-". */
    const val ONLINE_SOURCE = "online"
}
