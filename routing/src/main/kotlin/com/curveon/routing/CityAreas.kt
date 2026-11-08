package com.curveon.routing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Where the map is a town: squares of about 1.7 × 1.7 km ([LAT_DIV] × [LON_DIV] per degree) with so much road in them
 * ([DENSE_KM] of residential and unclassified road or more) that the small roads there are only clutter until zoomed
 * in close. Measured by how much road there is, never by what a road is called: out in the countryside, where roads
 * are few, every road shows as before, whatever its kind. Counted out of the routing data (any country on the device),
 * one segment file (5° × 5°) at a time; a file takes seconds and is kept in [cacheDir] until it changes.
 *
 * Measured 2026-11-08 (km in one square): the middle of Bergen, Oslo, Stavanger and Haugesund 20–27, Voss 19,
 * Grorud and Hemsedal 11, Norheimsund 8, cabin areas (Geilo, Trysil, Sjusjøen) 2–8, villages (Bremnes, Kvinnherad) 3–5.
 * At 10 km all of central Bergen is town and Norheimsund, Leirvik, Bremnes and Geilo are not.
 */
class CityAreas(
    private val segmentDir: File, private val cacheDir: File, private val reader: SurfaceReader,
    /** Called before each row of a file while counting: may wait, to leave the CPU to something more urgent. */
    private val pause: () -> Unit = {},
    /** Road in one square (km) from which it is town. */
    private val denseKm: Double = DENSE_KM,
) {
    private val loaded = HashMap<String, List<Long>>()

    /** The segment files on this device. */
    fun tiles(): List<String> = segmentDir.listFiles { f -> f.name.endsWith(".rd5") }.orEmpty().map { it.name.removeSuffix(".rd5") }.sorted()

    /** The town squares in one segment file, as [square] keys; slow the first time. */
    fun squares(tile: String): List<Long> {
        synchronized(loaded) { loaded[tile] }?.let { return it }
        val found = readCache(tile) ?: count(tile).also { writeCache(tile, it) }
        synchronized(loaded) { loaded[tile] = found }
        return found
    }

    private fun count(tile: String): List<Long> {
        val (lon0, lat0) = GravelHeat.origin(tile) ?: return emptyList()
        val km = HashMap<Long, Double>()
        val lonIdx0 = (lon0 + 180) * 32
        val latIdx0 = (lat0 + 90) * 32
        for (y in 0 until 5 * 32) {
            pause()
            for (x in 0 until 5 * 32) {
                val ways = reader.cellRoads(lonIdx0 + x, latIdx0 + y) ?: continue
                for (w in ways) {
                    if (w.rank != 1) continue
                    val c = w.coords
                    var k = 2
                    while (k < c.size) {
                        val key = square((c[k] + c[k - 2]) / 2, (c[k + 1] + c[k - 1]) / 2)
                        km[key] = (km[key] ?: 0.0) + GravelHeat.length(doubleArrayOf(c[k - 2], c[k - 1], c[k], c[k + 1]))
                        k += 2
                    }
                }
            }
        }
        val dense = km.filterValues { it >= denseKm }.keys.toHashSet()
        // A square of a town with less road in it (a park, a lake, a hill in the middle): the town goes on round it.
        for ((key, v) in km) if (key !in dense && v >= denseKm / 3) {
            val (x, y) = unsquare(key)
            if (listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).count { (a, b) -> squareKey(a, b) in dense } >= 3) dense += key
        }
        return dense.sorted()
    }

    private fun cacheFile(tile: String) = File(cacheDir, "$tile.town")

    private fun readCache(tile: String): List<Long>? {
        val rd5 = File(segmentDir, "$tile.rd5")
        val f = cacheFile(tile).takeIf { it.exists() } ?: return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readInt() != VERSION || d.readLong() != rd5.length() || d.readLong() != rd5.lastModified()) return null
                List(d.readInt()) { d.readLong() }
            }
        }.getOrNull()
    }

    private fun writeCache(tile: String, squares: List<Long>) {
        val rd5 = File(segmentDir, "$tile.rd5")
        runCatching {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "$tile.town.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { d ->
                d.writeInt(VERSION); d.writeLong(rd5.length()); d.writeLong(rd5.lastModified())
                d.writeInt(squares.size)
                squares.forEach { d.writeLong(it) }
            }
            tmp.renameTo(cacheFile(tile))
        }
    }

    companion object {
        /** Squares per degree of latitude and longitude: about 1.7 × 1.7 km in southern Norway. */
        const val LAT_DIV = 64
        const val LON_DIV = 32
        /** Residential and unclassified road in one square (km) from which it is a town. */
        const val DENSE_KM = 10.0
        /** A map tile is town when this share of it is town squares. */
        const val TOWN_TILE_SHARE = 0.4
        private const val VERSION = 4

        /**
         * The map tiles at [zoom] (x, y in the usual web map scheme) that are town: [TOWN_TILE_SHARE] of each lies in town
         * [squares]. The map joins all of a tile's small roads into one line, so a tile is quieted whole or not at all.
         */
        fun townTiles(squares: Collection<Long>, zoom: Int): List<Pair<Int, Int>> {
            val n = 1 shl zoom
            val count = HashMap<Long, Int>()
            for (key in squares) {
                val (x, y) = unsquare(key)
                val lon = (x + 0.5) / LON_DIV; val lat = (y + 0.5) / LAT_DIV
                val tx = Math.floor((lon + 180) / 360 * n).toInt()
                val ty = Math.floor((1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n).toInt()
                val k = squareKey(tx, ty)
                count[k] = (count[k] ?: 0) + 1
            }
            return count.mapNotNull { (k, c) ->
                val (tx, ty) = unsquare(k)
                val (south, west, north, east) = tileBounds(tx, ty, zoom)
                val squaresInTile = (east - west) * LON_DIV * (north - south) * LAT_DIV
                (tx to ty).takeIf { c >= squaresInTile * TOWN_TILE_SHARE }
            }
        }

        /** South, west, north, east of a web map tile. */
        fun tileBounds(x: Int, y: Int, zoom: Int): List<Double> {
            val n = (1 shl zoom).toDouble()
            fun lat(ty: Int) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * ty / n))))
            return listOf(lat(y + 1), x / n * 360 - 180, lat(y), (x + 1) / n * 360 - 180)
        }

        fun square(lon: Double, lat: Double) = squareKey(Math.floor(lon * LON_DIV).toInt(), Math.floor(lat * LAT_DIV).toInt())
        fun squareKey(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        fun unsquare(key: Long) = (key shr 32).toInt() to key.toInt()
    }
}
