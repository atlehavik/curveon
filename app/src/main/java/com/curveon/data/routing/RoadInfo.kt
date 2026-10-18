package com.curveon.data.routing

/**
 * What is known of a road a finger is held on: its name or number (from the map), class and
 * surface (OpenStreetMap, via the routing data) and speed limit, official where the country
 * publishes one.
 */
data class RoadInfo(
    val name: String?,
    val highway: String?,
    val surface: String?,
    val tracktype: String?,
    val unpaved: Boolean,
    val speedKmh: Int?,
    /** The limit is the official one (NVDB, Digiroad, …), not OpenStreetMap's. */
    val officialSpeed: Boolean,
    /** The road's number as its sign shows it; [name] is then the street name alone. */
    val ref: com.curveon.data.road.RoadRef? = null,
    /** A priority road (forkjørsveg), where the country's register says so. */
    val priority: Boolean = false,
    /** The routing data for this place isn't downloaded: road class and surface (and often the limit) are unknown. */
    val needsMap: Boolean = false,
    /** The country (or region) whose map would fill that in, when the catalogue knows it. */
    val mapArea: String? = null
)
