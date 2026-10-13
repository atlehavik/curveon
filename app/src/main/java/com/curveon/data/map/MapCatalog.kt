package com.curveon.data.map

import android.content.Context
import com.curveon.R
import com.curveon.pmtiles.TileMask
import org.json.JSONObject
import java.util.Locale

/** A country (or a state/region of a very large one) that can be downloaded. */
class CatalogArea(
    val id: String,
    /** English name from Natural Earth; [displayName] translates countries. */
    val name: String,
    val iso: String,
    val continent: String,
    /** Id of the country this state/region belongs to, or "" for a country. */
    val parent: String,
    val bbox: DoubleArray,
    /** Map size at the time the catalog was built. */
    val mapBytes: Long,
    /** BRouter tiles (5x5 degrees) the area needs, e.g. "E10_N60". */
    val routing: List<String>,
    /** Zoom-10 tiles as [start, end) Hilbert index pairs. */
    private val mask: LongArray
) {
    fun displayName(locale: Locale): String =
        if (parent.isEmpty() && iso.isNotEmpty()) Locale("", iso).getDisplayCountry(locale).ifBlank { name } else name

    fun tileMask() = TileMask(mask, bbox[0], bbox[1], bbox[2], bbox[3])

    fun contains(lat: Double, lon: Double) = lon >= bbox[0] && lat >= bbox[1] && lon <= bbox[2] && lat <= bbox[3]
}

/**
 * Every country in the world, by continent, built by tools/map/build-countries.sh from Natural
 * Earth borders (assets/map/countries.json). The USA, Canada and Russia are too big for one
 * download and come as states/provinces/federal districts.
 */
class MapCatalog(private val context: Context) {

    val areas: List<CatalogArea> by lazy { load() }
    val byId by lazy { areas.associateBy { it.id } }
    private val routingSizes = HashMap<String, Long>()

    fun children(id: String) = areas.filter { it.parent == id }

    /** Countries without states/regions, and big ones with them (which aren't downloaded whole). */
    fun countries(continent: String) = areas.filter { it.continent == continent && it.parent.isEmpty() }

    fun isSplit(area: CatalogArea) = areas.any { it.parent == area.id }

    fun routingBytes(tile: String) = routingSizes[tile] ?: 0L

    /** Where the rider is (country first, then the state if the country is split), for opening the list there. */
    fun areaAt(lat: Double, lon: Double): CatalogArea? =
        areas.filter { it.parent.isEmpty() && it.contains(lat, lon) }.minByOrNull { (it.bbox[2] - it.bbox[0]) * (it.bbox[3] - it.bbox[1]) }

    /** The area whose search index covers a place: the country, or its state/region when the country is split. */
    fun indexAreaAt(lat: Double, lon: Double): CatalogArea? {
        val country = areaAt(lat, lon) ?: return null
        if (!isSplit(country)) return country
        return children(country.id).filter { it.contains(lat, lon) }.minByOrNull { (it.bbox[2] - it.bbox[0]) * (it.bbox[3] - it.bbox[1]) }
    }

    private fun load(): List<CatalogArea> {
        val root = JSONObject(context.assets.open("map/countries.json").bufferedReader().use { it.readText() })
        root.getJSONObject("routingSizes").let { s -> s.keys().forEach { routingSizes[it] = s.getLong(it) } }
        val a = root.getJSONArray("areas")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val b = o.getJSONArray("bbox")
            val r = o.getJSONArray("routing")
            val m = o.getJSONArray("mask")
            // Stored as (gap from the previous end, length) pairs.
            val mask = LongArray(m.length())
            var end = 0L
            for (k in 0 until m.length() step 2) {
                val start = end + m.getLong(k)
                end = start + m.getLong(k + 1)
                mask[k] = start; mask[k + 1] = end
            }
            CatalogArea(
                o.getString("id"), o.getString("name"), o.getString("iso"), o.getString("continent"), o.getString("parent"),
                DoubleArray(4) { b.getDouble(it) }, o.getLong("mapBytes"), List(r.length()) { r.getString(it) }, mask
            )
        }
    }

    companion object {
        /** Natural Earth continent names, in the order the list shows them. */
        val CONTINENTS = listOf("Europe", "North America", "South America", "Asia", "Africa", "Oceania")

        fun continentName(continent: String) = when (continent) {
            "Europe" -> R.string.continent_europe
            "North America" -> R.string.continent_north_america
            "South America" -> R.string.continent_south_america
            "Asia" -> R.string.continent_asia
            "Africa" -> R.string.continent_africa
            else -> R.string.continent_oceania
        }
    }
}
