package com.curveon.data.weather

import android.content.Context
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Which parts of the world are land, for leaving the weather symbols off the sea: a small built-in map (0.05 degree cells,
 * tools/build_land_mask.py) that works in every country, offline, with or without the country's map and routing data.
 * Narrow islands and straits are rough; where there is routing data the symbol then goes on a road, or is left out.
 */
class LandMask(private val bits: ByteArray) {
    fun isLand(lat: Double, lon: Double): Boolean {
        val col = col(lon)
        return bits[row(lat) * ROW_BYTES + col / 8].toInt() shr (col % 8) and 1 == 1
    }

    /** Is any of this part of the world land? (Roughly: it may say yes a cell or few too eagerly.) */
    fun anyLand(south: Double, north: Double, west: Double, east: Double): Boolean {
        val from = col(west) / 8
        val to = col(east) / 8
        for (r in row(south)..row(north)) {
            val start = r * ROW_BYTES
            for (i in start + from..start + to) if (bits[i].toInt() != 0) return true
        }
        return false
    }

    private fun row(lat: Double) = ((lat + 90) * CELLS_PER_DEGREE).toInt().coerceIn(0, ROWS - 1)
    private fun col(lon: Double) = ((lon + 180) * CELLS_PER_DEGREE).toInt().coerceIn(0, COLS - 1)

    companion object {
        private const val CELLS_PER_DEGREE = 20
        private const val ROWS = 180 * CELLS_PER_DEGREE
        private const val COLS = 360 * CELLS_PER_DEGREE
        private const val ROW_BYTES = COLS / 8

        /** A few milliseconds and 3 MB: only when weather is shown, and not on the main thread. */
        fun read(gzipped: InputStream): LandMask? = runCatching {
            GZIPInputStream(gzipped).use { it.readBytes() }.takeIf { it.size == ROWS * ROW_BYTES }?.let(::LandMask)
        }.getOrNull()

        /** Not named .gz: Android would hand it over unpacked, then. */
        fun load(context: Context): LandMask? = runCatching { read(context.assets.open("land-mask.dat")) }.getOrNull()
            .also { if (it == null) android.util.Log.w("CurveonWeather", "No land mask: the weather can't keep off the sea") }
    }
}
