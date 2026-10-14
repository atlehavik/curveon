package com.curveon.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One GPS reading, decoupled from android.location.Location so it is easy to test. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val accuracyMeters: Float?,
    val timeMillis: Long,
    /** When the position was measured, on the System.nanoTime() clock (for drawing between fixes). */
    val atNanos: Long = System.nanoTime(),
    val speedAccuracyMps: Float? = null,
    val bearingAccuracyDeg: Float? = null
)

/**
 * [Location.getAltitude] is height above the WGS84 ellipsoid, some 40 m above sea level in Norway.
 * Height above sea level comes from the receiver's own NMEA (GGA), see [LocationSource], or – from
 * Android 14 – from the platform when it adds it.
 */
fun Location.toGpsFix() = GpsFix(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = if (android.os.Build.VERSION.SDK_INT >= 34 && hasMslAltitude()) mslAltitudeMeters
        else if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    timeMillis = time,
    // Location's own timestamp is on the elapsedRealtime clock; carry its age over to nanoTime.
    atNanos = System.nanoTime() - (android.os.SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).coerceIn(0L, 10_000_000_000L),
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null
)

/**
 * Raw satellite GPS via the platform LocationManager. Deliberately avoids Play Services fused
 * location: the tablet is usually offline and we want 1 Hz GNSS fixes, not network positioning.
 */
class LocationSource(context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission") // callers check permission first
    fun lastKnown(): GpsFix? =
        runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.toGpsFix() }.getOrNull()

    /** Satellites used in the last fix (for the ride trace); -1 until the receiver says. */
    @Volatile var satellitesUsed = -1
        private set

    /** Height above sea level from the receiver's last GGA sentence, and when it came. */
    @Volatile private var mslAltitude: Double? = null
    @Volatile private var mslAt = 0L

    private fun onNmea(sentence: String) {
        Nmea.ggaAltitude(sentence)?.let { mslAltitude = it; mslAt = System.currentTimeMillis() }
    }

    /** A fix with the sea-level height when the receiver gave one in the last few seconds. */
    private fun withSeaLevel(fix: GpsFix): GpsFix {
        val msl = mslAltitude?.takeIf { System.currentTimeMillis() - mslAt < 5_000 } ?: return fix
        return fix.copy(altitudeMeters = msl)
    }

    @SuppressLint("MissingPermission") // callers check permission first
    fun fixes(intervalMillis: Long = 1000L): Flow<GpsFix> = callbackFlow {
        val listener = LocationListener { location -> trySend(withSeaLevel(location.toGpsFix())) }
        val nmea = android.location.OnNmeaMessageListener { message, _ -> onNmea(message) }
        val gnss = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
            }
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMillis,
            0f,
            listener,
            Looper.getMainLooper()
        )
        // The receiver sends a dozen NMEA sentences and a satellite report every second; they only
        // set two fields, so take them on a thread of their own and let the main thread sleep.
        val side = android.os.HandlerThread("GnssSide").apply { start() }
        val sideHandler = android.os.Handler(side.looper)
        runCatching { locationManager.registerGnssStatusCallback(gnss, sideHandler) }
        runCatching { locationManager.addNmeaListener(nmea, sideHandler) }
        awaitClose {
            locationManager.removeUpdates(listener)
            runCatching { locationManager.removeNmeaListener(nmea) }
            runCatching { locationManager.unregisterGnssStatusCallback(gnss) }
            side.quitSafely()
        }
    }
}

/** The bits of NMEA sentences Curveon reads. */
object Nmea {
    /**
     * Height above sea level (metres) from a GGA sentence ("$GPGGA,…" or "$GNGGA,…"): field 9, in
     * metres when field 10 is "M"; null without a fix (field 6 = 0) or a height.
     */
    fun ggaAltitude(sentence: String): Double? {
        if (sentence.length < 7 || !sentence.startsWith("$") || sentence.substring(3, 6) != "GGA") return null
        val f = sentence.substringBefore('*').split(',')
        if (f.size < 11 || f[6].isEmpty() || f[6] == "0" || f[10] != "M") return null
        return f[9].toDoubleOrNull()
    }
}
