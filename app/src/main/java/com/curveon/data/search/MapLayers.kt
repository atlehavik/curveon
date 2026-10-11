package com.curveon.data.search

import com.curveon.R
import com.curveon.data.gpx.PoiKind

/**
 * Kinds of place the rider can have on the map at all times (Settings → Display), taken from the
 * offline search index of the downloaded countries. [maxWidthKm] keeps a layer off until the part of the map in view is small enough for it to be readable.
 */
enum class MapLayer(val label: Int, val icon: PoiKind, val maxWidthKm: Double, val kinds: List<String>) {
    FUEL(R.string.poi_fuel, PoiKind.FUEL, 40.0, listOf("fuel")),
    CAMERAS(R.string.layer_cameras, PoiKind.CAMERA, 25.0, emptyList()),
    VIEWPOINTS(R.string.layer_viewpoints, PoiKind.SCENIC, 80.0, listOf("viewpoint")),
    PASSES(R.string.layer_passes, PoiKind.PASS, 250.0, listOf("pass")),
    CAMPING(R.string.poi_camping, PoiKind.CAMPING, 40.0, listOf("camp_site")),
    FOOD(R.string.layer_food, PoiKind.CAFE, 12.0, listOf("cafe", "restaurant", "fast_food")),
    FERRIES(R.string.layer_ferries, PoiKind.FERRY, 120.0, listOf("ferry")),
    MOTORCYCLE(R.string.layer_motorcycle, PoiKind.WORKSHOP, 25.0, listOf("motorcycle_shop", "workshop"));

    companion object {
        /** What an index entry of [kind] is drawn as. */
        fun poiKind(kind: String): PoiKind = when (kind) {
            "fuel" -> PoiKind.FUEL
            "viewpoint" -> PoiKind.SCENIC
            "pass" -> PoiKind.PASS
            "camp_site" -> PoiKind.CAMPING
            "cafe" -> PoiKind.CAFE
            "restaurant", "fast_food" -> PoiKind.RESTAURANT
            "ferry" -> PoiKind.FERRY
            "motorcycle_shop", "workshop" -> PoiKind.WORKSHOP
            else -> PoiKind.INFO
        }
    }
}
