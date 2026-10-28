package com.curveon.protocol

import org.json.JSONArray
import org.json.JSONObject

/** One hour of MET's forecast for a place: what a rider cares about, nothing more. */
data class WeatherHour(
    /** Start of the hour, ms since 1970. */
    val time: Long,
    val tempC: Double,
    /** Rain (or snow as water) in the hour. */
    val precipMm: Double,
    /** 0–100, the chance of any rain in the hour; null where MET gives none (after the first days). */
    val precipChance: Double?,
    val windMs: Double,
    /** Strongest gusts; null where MET gives none. */
    val gustMs: Double?,
    /** MET's symbol, e.g. "partlycloudy_day", "lightrainshowers_day". */
    val symbol: String?
)

/** MET's forecast for one grid cell, kept until [expires] (from MET's Expires header). */
data class WeatherForecast(val lat: Double, val lon: Double, val expires: Long, val hours: List<WeatherHour>) {

    /** The hour [time] falls in, or the nearest one within three hours (MET gives six-hour steps after a few days). */
    fun at(time: Long): WeatherHour? = hours.minByOrNull { kotlin.math.abs(it.time + HALF_HOUR - time) }
        ?.takeIf { kotlin.math.abs(it.time + HALF_HOUR - time) <= 3 * HOUR }

    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).put("expires", expires).put("hours", JSONArray(hours.map {
        JSONObject().put("t", it.time).put("temp", it.tempC).put("mm", it.precipMm).put("p", it.precipChance ?: JSONObject.NULL)
            .put("wind", it.windMs).put("gust", it.gustMs ?: JSONObject.NULL).put("sym", it.symbol ?: JSONObject.NULL)
    }))

    companion object {
        private const val HOUR = 3_600_000L
        private const val HALF_HOUR = HOUR / 2

        fun fromJson(o: JSONObject): WeatherForecast {
            val a = o.optJSONArray("hours") ?: JSONArray()
            return WeatherForecast(o.getDouble("lat"), o.getDouble("lon"), o.optLong("expires"), (0 until a.length()).map { i ->
                val h = a.getJSONObject(i)
                WeatherHour(
                    h.getLong("t"), h.optDouble("temp", 0.0), h.optDouble("mm", 0.0),
                    if (h.isNull("p")) null else h.optDouble("p"), h.optDouble("wind", 0.0),
                    if (h.isNull("gust")) null else h.optDouble("gust"), if (h.isNull("sym")) null else h.optString("sym")
                )
            })
        }
    }
}
