package com.curveon.data.search

/**
 * The search index (places, fuel, camping, …) of the country the rider is in, which isn't on the
 * tablet but is small enough to fetch without the map: offered in Where to?, and fetched by itself
 * when it is very small and the connection isn't metered.
 */
data class SearchOffer(
    val areaId: String,
    val name: String,
    val bytes: Long,
    /** What is happening while it downloads, e.g. "Downloading search – 3 of 40 MB"; null before it starts. */
    val progress: String? = null,
    val fraction: Float = 0f,
    val failed: Boolean = false
) {
    val running get() = progress != null && !failed
}
