package com.vibenav.data.location

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
    val timeMillis: Long
)

fun Location.toGpsFix() = GpsFix(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    timeMillis = time
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

    @SuppressLint("MissingPermission") // callers check permission first
    fun fixes(intervalMillis: Long = 1000L): Flow<GpsFix> = callbackFlow {
        val listener = LocationListener { location -> trySend(location.toGpsFix()) }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMillis,
            0f,
            listener,
            Looper.getMainLooper()
        )
        awaitClose { locationManager.removeUpdates(listener) }
    }
}
