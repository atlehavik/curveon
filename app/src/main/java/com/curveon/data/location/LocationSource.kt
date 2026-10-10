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

fun Location.toGpsFix() = GpsFix(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = if (hasAltitude()) altitude else null,
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

    @SuppressLint("MissingPermission") // callers check permission first
    fun fixes(intervalMillis: Long = 1000L): Flow<GpsFix> = callbackFlow {
        val listener = LocationListener { location -> trySend(location.toGpsFix()) }
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
        runCatching { locationManager.registerGnssStatusCallback(gnss, android.os.Handler(Looper.getMainLooper())) }
        awaitClose {
            locationManager.removeUpdates(listener)
            runCatching { locationManager.unregisterGnssStatusCallback(gnss) }
        }
    }
}
