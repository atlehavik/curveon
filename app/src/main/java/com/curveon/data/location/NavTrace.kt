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
            "${"%.7f".format(Locale.US, f.latitude)},${"%.7f".format(Locale.US, f.longitude)}," +
            "${f.accuracyMeters ?: ""},${f.altitudeMeters?.let { "%.1f".format(Locale.US, it) } ?: ""}," +
            "${f.speedMps ?: ""},${f.speedAccuracyMps ?: ""},${f.bearingDeg ?: ""},${f.bearingAccuracyDeg ?: ""}," +
            "$sats,${if (estimated) 1 else 0}"
    )

    fun match(snapped: Boolean, distFromRoute: Double, routeBearing: Double, along: Double, offRoute: Boolean, heading: Float) = line(
        "N,${mono()},${if (snapped) 1 else 0},${"%.1f".format(Locale.US, distFromRoute)},${"%.0f".format(Locale.US, routeBearing)}," +
            "${"%.1f".format(Locale.US, along)},${if (offRoute) 1 else 0},${"%.0f".format(Locale.US, heading)}"
    )

    fun drawn(lat: Double, lon: Double, bearing: Double, zoom: Double, tilt: Double, following: Boolean) = line(
        "D,${mono()},${"%.7f".format(Locale.US, lat)},${"%.7f".format(Locale.US, lon)},${"%.0f".format(Locale.US, bearing)}," +
            "${"%.2f".format(Locale.US, zoom)},${"%.0f".format(Locale.US, tilt)},${if (following) 1 else 0}"
    )

    fun route(points: List<com.curveon.routing.LatLon>) = line(
        "R,${mono()}," + points.joinToString(";") { "%.6f %.6f".format(Locale.US, it.lat, it.lon) }
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
