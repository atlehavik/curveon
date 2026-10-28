package com.curveon.phone

import com.curveon.protocol.Message
import com.curveon.protocol.WeatherForecast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * MET's forecast fetched by the phone, for a tablet with no internet of its own: the tablet asks for a grid cell, the
 * phone answers with the forecast (or null). Like [RelayTunnelClient], [sink] writes to the link and is false if there is none.
 */
class WeatherTunnelClient(private val sink: (Message) -> Boolean) {
    private val next = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<WeatherForecast?>>()

    /** The forecast for the cell at [lat], [lon]; null if the phone could not get it in time or there is no link. */
    suspend fun get(lat: Double, lon: Double, timeoutMs: Long = 25_000): WeatherForecast? {
        val id = next.getAndIncrement()
        val answer = CompletableDeferred<WeatherForecast?>()
        waiting[id] = answer
        return try {
            if (!sink(Message.WeatherGet(id, lat, lon))) null else withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            waiting.remove(id)
        }
    }

    fun onMessage(m: Message) {
        if (m is Message.WeatherData) waiting.remove(m.id)?.complete(m.forecast)
    }

    /** The link to the phone went away: nothing asked will be answered. */
    fun linkLost() = waiting.keys.toList().forEach { waiting.remove(it)?.complete(null) }
}

/** The phone's end of [WeatherTunnelClient]: fetches what the tablet asks for on the phone's own internet, a few at a time. */
class WeatherTunnelServer(private val fetch: (Double, Double) -> WeatherForecast?, private val sink: (Message) -> Unit) {
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "curveon-weather").apply { isDaemon = true } }

    fun handle(m: Message) {
        if (m !is Message.WeatherGet) return
        // Only real places, and never more precise than MET allows.
        if (m.lat !in -90.0..90.0 || m.lon !in -180.0..180.0) return sink(Message.WeatherData(m.id, null))
        pool.execute { sink(Message.WeatherData(m.id, runCatching { fetch(m.lat, m.lon) }.getOrNull())) }
    }
}
