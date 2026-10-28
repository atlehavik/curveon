package com.curveon.phone

import com.curveon.protocol.Message
import com.curveon.protocol.WeatherForecast
import com.curveon.protocol.WeatherHour
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WeatherTest {
    private fun t(s: String) = Instant.parse(s).toEpochMilli()

    private val json = """
        {"properties":{"timeseries":[
          {"time":"2026-10-28T09:00:00Z","data":{"instant":{"details":{"air_temperature":5.0,"wind_speed":3.0}},
            "next_1_hours":{"summary":{"symbol_code":"cloudy"},"details":{"precipitation_amount":0.0}}}},
          {"time":"2026-10-28T10:00:00Z","data":{"instant":{"details":{"air_temperature":6.5,"wind_speed":4.0,"wind_speed_of_gust":11.2}},
            "next_1_hours":{"summary":{"symbol_code":"rain"},"details":{"precipitation_amount":1.8,"probability_of_precipitation":90.0}}}},
          {"time":"2026-10-31T12:00:00Z","data":{"instant":{"details":{"air_temperature":2.0,"wind_speed":6.0}},
            "next_6_hours":{"summary":{"symbol_code":"sleet"},"details":{"precipitation_amount":6.0}}}},
          {"time":"2026-11-06T12:00:00Z","data":{"instant":{"details":{"air_temperature":2.0,"wind_speed":6.0}}}}
        ]}}
    """.trimIndent()

    @Test fun parsesWhatARiderNeeds() {
        val f = MetWeather.parse(json, 60.4, 5.3, expires = 1L, now = t("2026-10-28T09:30:00Z"))
        assertEquals(3, f.hours.size) // the one far ahead (and without a forecast) is dropped
        val rain = f.hours[1]
        assertEquals(1.8, rain.precipMm, 1e-9)
        assertEquals(90.0, rain.precipChance!!, 1e-9)
        assertEquals(11.2, rain.gustMs!!, 1e-9)
        assertEquals("rain", rain.symbol)
        assertNull(f.hours[0].gustMs)
        assertEquals(1.0, f.hours[2].precipMm, 1e-9) // six hours spread over each hour
    }

    @Test fun picksTheHourATimeFallsIn() {
        val f = MetWeather.parse(json, 60.4, 5.3, expires = 1L, now = t("2026-10-28T09:30:00Z"))
        assertEquals("rain", f.at(t("2026-10-28T10:40:00Z"))?.symbol)
        assertEquals("cloudy", f.at(t("2026-10-28T09:10:00Z"))?.symbol)
        assertNull(f.at(t("2026-10-29T20:00:00Z"))) // nothing within three hours
    }

    @Test fun tunnelCarriesAForecast() = runBlocking {
        val forecast = WeatherForecast(60.4, 5.3, 99L, listOf(WeatherHour(1L, 3.0, 0.0, null, 2.0, null, "clearsky_day")))
        lateinit var client: WeatherTunnelClient
        // "Bluetooth": the messages are handed straight over, through JSON like on the link.
        val server = WeatherTunnelServer({ lat, _ -> forecast.takeIf { lat > 0 } }) { m -> client.onMessage(Message.fromJson(org.json.JSONObject(m.toJson().toString()))!!) }
        client = WeatherTunnelClient { m -> server.handle(Message.fromJson(org.json.JSONObject(m.toJson().toString()))!!); true }
        assertEquals(forecast, client.get(60.4, 5.3))
        assertNull(client.get(-10.0, 5.3)) // the phone could not get it
        assertNull(WeatherTunnelClient { false }.get(60.4, 5.3)) // no link
    }
}
