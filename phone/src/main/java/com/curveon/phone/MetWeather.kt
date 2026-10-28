package com.curveon.phone

import com.curveon.protocol.WeatherForecast
import com.curveon.protocol.WeatherHour
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * MET Norway's Locationforecast (api.met.no), the forecast Yr shows: free, no key, commercial use allowed with credit.
 * MET's terms: an own User-Agent with a contact, at most four decimals in the coordinates, and no fetching again before
 * the answer has expired (the caller keeps it until [WeatherForecast.expires]).
 *
 * Only the next [KEEP_HOURS] are kept, and only what a rider cares about (rain, wind, temperature), so a forecast is a
 * few kilobytes and can go over the Bluetooth link to a tablet without internet.
 */
class MetWeather(
    private val userAgent: String,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
) {
    /** The forecast for [lat], [lon]; null when it could not be fetched (no internet, MET down, refused). */
    fun fetch(lat: Double, lon: Double): WeatherForecast? = runCatching {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=%.4f&lon=%.4f".format(java.util.Locale.ROOT, lat, lon)
        client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val expires = r.headers.getDate("Expires")?.time ?: (System.currentTimeMillis() + DEFAULT_KEEP_MS)
            parse(r.body?.string() ?: return null, lat, lon, expires)
        }
    }.getOrNull()

    companion object {
        const val KEEP_HOURS = 96
        private const val DEFAULT_KEEP_MS = 30 * 60_000L

        /** MET's JSON down to the hours a rider needs. Six-hour steps (after a few days) are spread over their hours. */
        fun parse(json: String, lat: Double, lon: Double, expires: Long, now: Long = System.currentTimeMillis()): WeatherForecast {
            val series = JSONObject(json).getJSONObject("properties").getJSONArray("timeseries")
            val until = now + KEEP_HOURS * 3_600_000L
            val hours = (0 until series.length()).mapNotNull { i ->
                val step = series.getJSONObject(i)
                val time = Instant.parse(step.getString("time")).toEpochMilli()
                if (time > until || time < now - 3_600_000L) return@mapNotNull null
                val data = step.getJSONObject("data")
                val instant = data.getJSONObject("instant").getJSONObject("details")
                val one = data.optJSONObject("next_1_hours")
                val six = data.optJSONObject("next_6_hours")
                val next = one ?: six ?: return@mapNotNull null
                val details = next.optJSONObject("details")
                val mm = details?.optDouble("precipitation_amount", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
                WeatherHour(
                    time = time,
                    tempC = instant.optDouble("air_temperature", 0.0),
                    precipMm = if (one != null) mm else mm / 6,
                    precipChance = details?.optDouble("probability_of_precipitation")?.takeIf { !it.isNaN() },
                    windMs = instant.optDouble("wind_speed", 0.0),
                    gustMs = instant.optDouble("wind_speed_of_gust").takeIf { !it.isNaN() },
                    symbol = next.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotEmpty() }
                )
            }
            return WeatherForecast(lat, lon, expires, hours)
        }
    }
}
