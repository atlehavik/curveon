package com.curveon.routing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** How much unpaved road there is around one point, for the gravel heat map. */
class HeatCell(val lon: Double, val lat: Double, val weight: Float)

/**
 * How much gravel and soft road each part of the map has (any country with routing data on the device), for planning: where it is worth
 * zooming in to look for gravel. Counted per [CELLS_PER_DEGREE] square out of the routing data, one
 * segment file (5° × 5°) at a time. Counting a file takes seconds, so each is kept in [cacheDir]
 * until its segment file changes.
 */
class GravelHeat(
    private val segmentDir: File, private val cacheDir: File, private val reader: SurfaceReader,
    /** Called before each row of the file while counting: may wait, to leave the CPU to something more urgent. */
    private val pause: () -> Unit = {},
) {
    private val loaded = HashMap<String, List<HeatCell>>()

    /** The segment files that cover the area and are on this device. */
    fun tiles(south: Double, west: Double, north: Double, east: Double): List<String> {
        val out = ArrayList<String>()
        var lat = Math.floorDiv(Math.floor(south).toInt(), 5) * 5
        while (lat <= north) {
            var lon = Math.floorDiv(Math.floor(west).toInt(), 5) * 5
            while (lon <= east) {
                val name = tileName(lon, lat)
                if (File(segmentDir, "$name.rd5").exists()) out += name
                lon += 5
            }
            lat += 5
        }
        return out
    }

    /** Already counted (in memory or on disk), so [cells] returns at once. */
    fun isReady(tile: String) = synchronized(loaded) { tile in loaded } || readCache(tile) != null

    /** The cells with unpaved road in one segment file; slow the first time. Empty if it can't be read. */
    fun cells(tile: String): List<HeatCell> {
        synchronized(loaded) { loaded[tile] }?.let { return it }
        val cells = readCache(tile) ?: count(tile).also { writeCache(tile, it) }
        synchronized(loaded) { loaded[tile] = cells }
        return cells
    }

    private fun count(tile: String): List<HeatCell> {
        val (lon0, lat0) = origin(tile) ?: return emptyList()
        val per = 32 / CELLS_PER_DEGREE
        val side = 5 * CELLS_PER_DEGREE
        val sums = FloatArray(side * side)
        val lonIdx0 = (lon0 + 180) * 32
        val latIdx0 = (lat0 + 90) * 32
        for (y in 0 until side * per) {
            pause()
            for (x in 0 until side * per) {
            val ways = reader.cellWays(lonIdx0 + x, latIdx0 + y) ?: continue
            var w = 0.0
            for (way in ways) w += length(way.coords) * weight(way)
            if (w > 0) sums[(y / per) * side + x / per] += w.toFloat()
            }
        }
        val size = 1.0 / CELLS_PER_DEGREE
        return sums.indices.filter { sums[it] > 0 }.map { i ->
            HeatCell(lon0 + (i % side + 0.5) * size, lat0 + (i / side + 0.5) * size, sums[i])
        }
    }

    private fun cacheFile(tile: String) = File(cacheDir, "$tile.heat")

    private fun readCache(tile: String): List<HeatCell>? {
        val rd5 = File(segmentDir, "$tile.rd5")
        val f = cacheFile(tile).takeIf { it.exists() } ?: return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readInt() != VERSION || d.readLong() != rd5.length() || d.readLong() != rd5.lastModified()) return null
                List(d.readInt()) { HeatCell(d.readDouble(), d.readDouble(), d.readFloat()) }
            }
        }.getOrNull()?.also { synchronized(loaded) { loaded[tile] = it } }
    }

    private fun writeCache(tile: String, cells: List<HeatCell>) {
        val rd5 = File(segmentDir, "$tile.rd5")
        runCatching {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "$tile.heat.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { d ->
                d.writeInt(VERSION); d.writeLong(rd5.length()); d.writeLong(rd5.lastModified())
                d.writeInt(cells.size)
                cells.forEach { d.writeDouble(it.lon); d.writeDouble(it.lat); d.writeFloat(it.weight) }
            }
            tmp.renameTo(cacheFile(tile))
        }
    }

    companion object {
        /** Heat cells per degree: 1/16°, about 7 × 3.5 km in southern Norway. */
        const val CELLS_PER_DEGREE = 16
        private const val VERSION = 1

        /**
         * Gravel roads count most, they are what a rider plans for; gravel tracks less (many are
         * closed to traffic), and soft or unknown ground least.
         */
        internal fun weight(way: SurfaceWay) = when {
            way.kind == SurfaceKind.PAVED || way.kind == SurfaceKind.ACCESS -> 0.0
            way.kind == SurfaceKind.GRAVEL && way.rank >= 1 -> 1.0
            way.kind == SurfaceKind.GRAVEL -> 0.5
            else -> 0.25
        }

        /** Kilometres along lon, lat, lon, lat, … */
        internal fun length(c: DoubleArray): Double {
            var km = 0.0
            for (k in 2 until c.size step 2) {
                val dx = (c[k] - c[k - 2]) * 111.32 * Math.cos(Math.toRadians(c[k + 1]))
                val dy = (c[k + 1] - c[k - 1]) * 110.57
                km += Math.hypot(dx, dy)
            }
            return km
        }

        internal fun tileName(lon: Int, lat: Int) = (if (lon < 0) "W${-lon}" else "E$lon") + "_" + (if (lat < 0) "S${-lat}" else "N$lat")

        /** "E5_N60" → (5, 60). */
        fun origin(tile: String): Pair<Int, Int>? {
            val m = Regex("([EW])(\\d+)_([NS])(\\d+)").matchEntire(tile) ?: return null
            val lon = m.groupValues[2].toInt() * (if (m.groupValues[1] == "W") -1 else 1)
            val lat = m.groupValues[4].toInt() * (if (m.groupValues[3] == "S") -1 else 1)
            return lon to lat
        }
    }
}
