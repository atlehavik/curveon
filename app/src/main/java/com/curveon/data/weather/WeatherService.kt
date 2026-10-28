package com.curveon.data.weather

import android.content.Context
import com.curveon.protocol.WeatherForecast
import com.curveon.protocol.WeatherHour
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * MET's forecast for the places a trip goes, by grid cell (about 10 by 10 km), so nearby points and the alternatives
 * of a trip share one lookup. A cell is kept on disk until MET says it has expired, and used offline a while after
 * that (with its age), so a trip planned at home on wifi still has its weather on the road.
 *
 * [fetch] gets a cell's forecast: straight from MET with internet here, or through the linked phone (null = neither).
 */
class WeatherService(context: Context, private val fetch: suspend (Double, Double) -> WeatherForecast?) {
    private val dir = File(context.filesDir, "weather").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, WeatherForecast>()
    private val inFlight = ConcurrentHashMap<String, Deferred<WeatherForecast?>>()
    /** MET asks for few requests at a time from one app; four is plenty for a trip's cells. */
    private val gate = Semaphore(4)

    /** The forecast's hour at each point (null where there is none), fetching the cells not kept yet. */
    suspend fun hours(points: List<WeatherPoint>, now: Long = System.currentTimeMillis()): List<WeatherHour?> = coroutineScope {
        val cells = points.map { cellOf(it.point.lat, it.point.lon) }
        val forecasts = cells.distinct().map { c -> async { c to forecast(c, now) } }.awaitAll().toMap()
        points.zip(cells).map { (p, c) -> forecasts[c]?.at(p.time) }
    }

    /** When the oldest forecast used for [points] was made, roughly (null = all fresh): "Forecast from 08:00" offline. */
    fun staleSince(points: List<WeatherPoint>, now: Long = System.currentTimeMillis()): Long? =
        points.map { cellOf(it.point.lat, it.point.lon) }.distinct()
            .mapNotNull { memory[it.key] }.filter { it.expires < now }.minOfOrNull { it.expires - KEEP_FRESH_MS }

    private suspend fun forecast(cell: Cell, now: Long): WeatherForecast? {
        val kept = memory[cell.key] ?: read(cell)?.also { memory[cell.key] = it }
        if (kept != null && kept.expires > now) return kept
        val fresh = coroutineScope {
            inFlight.getOrPut(cell.key) {
                async { try { gate.withPermit { fetch(cell.lat, cell.lon) } } finally { inFlight.remove(cell.key) } }
            }.await()
        }
        if (fresh != null) {
            memory[cell.key] = fresh
            withContext(Dispatchers.IO) { runCatching { File(dir, "${cell.key}.json").writeText(fresh.toJson().toString()) } }
            return fresh
        }
        // No way to fetch it now: an older forecast is still better than none for a few hours.
        return kept?.takeIf { now - it.expires < USE_STALE_MS }
    }

    private suspend fun read(cell: Cell): WeatherForecast? = withContext(Dispatchers.IO) {
        runCatching { WeatherForecast.fromJson(JSONObject(File(dir, "${cell.key}.json").readText())) }.getOrNull()
    }

    /** Forecasts gone stale long ago are deleted now and then, so the folder does not grow. */
    fun tidy(now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.filter { now - it.lastModified() > 2 * 24 * 3_600_000L }?.forEach { it.delete() }
    }

    data class Cell(val lat: Double, val lon: Double) {
        val key get() = "%.2f_%.2f".format(java.util.Locale.ROOT, lat, lon)
    }

    companion object {
        /** About 11 km north–south; east–west twice the degrees (meridians are close in the north), at most 4 decimals for MET. */
        private const val LAT_STEP = 0.1
        private const val LON_STEP = 0.2
        /** Forecasts are fetched again after MET's Expires; offline an expired one is used this much longer. */
        private const val USE_STALE_MS = 6 * 3_600_000L
        /** MET's forecasts usually expire about half an hour after they are fetched. */
        private const val KEEP_FRESH_MS = 30 * 60_000L

        /** The middle of the grid cell [lat], [lon] is in: what is asked of MET, so it never learns the exact place. */
        fun cellOf(lat: Double, lon: Double) = Cell(
            Math.round((floor(lat / LAT_STEP) * LAT_STEP + LAT_STEP / 2) * 10000) / 10000.0,
            Math.round((floor(lon / LON_STEP) * LON_STEP + LON_STEP / 2) * 10000) / 10000.0
        )
    }
}
