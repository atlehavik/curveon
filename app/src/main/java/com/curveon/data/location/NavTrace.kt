package com.curveon.data.location

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A plain-text log of what the GPS said and what the map showed, for tuning how the rider is
 * drawn (smoothness, lag, leaving the route) from real rides. One file per app start in
 * Android/data/<app>/files/navtrace/, pulled with adb (see device-checks.md). Lines:
 *
 * - `F,wallMs,monoMs,ageMs,lat,lon,acc,alt,speed,speedAcc,bearing,bearingAcc,sats,estimated` a fix
 * - `N,monoMs,snapped,distFromRoute,routeBearing,along,offRoute,heading` how it matched the route
 * - `D,monoMs,lat,lon,bearing,zoom,tilt,following` where the rider was drawn (a few times a second)
 * - `R,monoMs,lat lon;lat lon;…` a new route
 * - `E,monoMs,text` something happened (reroute, wrong turn, …)
 *
 * monoMs is System.nanoTime() in ms, the same clock for all lines.
 */
object NavTrace {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "NavTrace").apply { isDaemon = true } }
    private var writer: BufferedWriter? = null
    private var lastFlush = 0L

    fun start(context: Context) {
        if (writer != null) return
        val dir = context.getExternalFilesDir("navtrace") ?: return
        io.execute {
            runCatching {
                dir.mkdirs()
                prune(dir)
                val name = "trace-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
                writer = File(dir, name).bufferedWriter()
            }
        }
    }

    fun mono() = System.nanoTime() / 1_000_000

    fun fix(f: GpsFix, sats: Int, estimated: Boolean) = line(
        "F,${f.timeMillis},${f.atNanos / 1_000_000},${(System.nanoTime() - f.atNanos) / 1_000_000}," +
            "${num(f.latitude, 7)},${num(f.longitude, 7)}," +
            "${f.accuracyMeters ?: ""},${f.altitudeMeters?.let { num(it, 1) } ?: ""}," +
            "${f.speedMps ?: ""},${f.speedAccuracyMps ?: ""},${f.bearingDeg ?: ""},${f.bearingAccuracyDeg ?: ""}," +
            "$sats,${if (estimated) 1 else 0}"
    )

    fun match(snapped: Boolean, distFromRoute: Double, routeBearing: Double, along: Double, offRoute: Boolean, heading: Float) = line(
        "N,${mono()},${if (snapped) 1 else 0},${num(distFromRoute, 1)},${num(routeBearing, 0)}," +
            "${num(along, 1)},${if (offRoute) 1 else 0},${num(heading, 0)}"
    )

    fun drawn(lat: Double, lon: Double, bearing: Double, zoom: Double, tilt: Double, following: Boolean) = line(
        "D,${mono()},${num(lat, 7)},${num(lon, 7)},${num(bearing, 0)}," +
            "${num(zoom, 2)},${num(tilt, 0)},${if (following) 1 else 0}"
    )

    fun route(points: List<com.curveon.routing.LatLon>) = line(
        "R,${mono()}," + points.joinToString(";") { num(it.lat, 6) + " " + num(it.lon, 6) }
    )

    fun event(text: String) = line("E,${mono()},${text.replace('\n', ' ')}")

    private fun line(s: String) {
        if (writer == null) return
        io.execute {
            val w = writer ?: return@execute
            runCatching {
                w.write(s); w.newLine()
                val now = System.currentTimeMillis()
                if (now - lastFlush > 5_000) { w.flush(); lastFlush = now }
            }
        }
    }

    private val scales = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000)

    /**
     * [v] with [decimals] decimals, as "%.Nf" would write it: String.format is slow on Android, and
     * this runs several times a second.
     */
    internal fun num(v: Double, decimals: Int): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val scale = scales[decimals]
        val r = Math.round(kotlin.math.abs(v) * scale)
        val sb = StringBuilder(16)
        if (v < 0 && r != 0L) sb.append('-')
        sb.append(r / scale)
        if (decimals > 0) {
            val frac = (r % scale).toString()
            sb.append('.')
            repeat(decimals - frac.length) { sb.append('0') }
            sb.append(frac)
        }
        return sb.toString()
    }

    private fun num(v: Float, decimals: Int) = num(v.toDouble(), decimals)

    fun flush() = io.execute { runCatching { writer?.flush() } }

    /** Keep two weeks, and never more than 300 MB. */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("trace-") }?.sortedByDescending { it.name } ?: return
        val cutoff = System.currentTimeMillis() - 14 * 24 * 3600_000L
        var total = 0L
        for (f in files) {
            total += f.length()
            if (f.lastModified() < cutoff || total > 300L * 1024 * 1024) f.delete()
        }
    }
}
