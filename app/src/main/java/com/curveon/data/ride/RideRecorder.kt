package com.curveon.data.ride

import com.curveon.data.location.GpsFix
import com.curveon.data.location.TripTracker
import com.curveon.data.model.Ride
import java.util.Locale
import kotlin.math.abs

/**
 * Records rides automatically, no buttons: starts once the bike has been moving for a little
 * while and finishes after a long stop (a coffee break shorter than [END_AFTER_STOP_MS] stays
 * part of the same ride). Pure logic; the caller persists [current] when [onFix] says so.
 */
class RideRecorder(private val now: () -> Long = System::currentTimeMillis) {

    enum class Event { NONE, STARTED, UPDATED, FINISHED }

    var current: Ride? = null
        private set

    private val track = StringBuilder()
    private var lastPoint: GpsFix? = null
    private var lastEle: Double? = null
    private var movingSince = 0L
    private var lastMovingAt = 0L
    private var lastSavedAt = 0L

    fun onFix(fix: GpsFix, speedKmh: Float, leanDeg: Float?): Event {
        val t = now()
        val moving = speedKmh >= MOVING_KMH
        if (moving) lastMovingAt = t

        var ride = current
        if (ride == null) {
            if (!moving) { movingSince = 0L; return Event.NONE }
            if (movingSince == 0L) movingSince = t
            if (t - movingSince < START_AFTER_MS) return Event.NONE
            ride = Ride(startMs = t, endMs = t)
            current = ride
            track.clear()
            lastPoint = null
            lastEle = null
            lastSavedAt = t
            append(fix, ride.startMs)
            return Event.STARTED
        }

        if (t - lastMovingAt > END_AFTER_STOP_MS) return Event.FINISHED

        val prev = lastPoint
        var distance = ride.distanceMeters
        var movingMs = ride.movingMillis
        var ascent = ride.ascentMeters
        if (prev != null && (fix.accuracyMeters ?: 0f) <= 30f) {
            val d = TripTracker.distanceMeters(prev, fix)
            if (moving) {
                distance += d
                movingMs += (fix.timeMillis - prev.timeMillis).coerceIn(0, 5_000)
            }
        }
        fix.altitudeMeters?.let { e ->
            val last = lastEle
            if (last == null) lastEle = e
            else if (abs(e - last) > 3) { // ignore GPS altitude jitter
                if (e > last) ascent += e - last
                lastEle = e
            }
        }
        val lean = if (moving) leanDeg ?: 0f else 0f
        ride = ride.copy(
            endMs = t,
            distanceMeters = distance,
            movingMillis = movingMs,
            maxSpeedKmh = maxOf(ride.maxSpeedKmh, speedKmh),
            ascentMeters = ascent,
            maxLeanLeftDeg = maxOf(ride.maxLeanLeftDeg, -lean),
            maxLeanRightDeg = maxOf(ride.maxLeanRightDeg, lean)
        )
        if (prev == null || TripTracker.distanceMeters(prev, fix) >= POINT_SPACING_M - 1 || fix.timeMillis - prev.timeMillis > 30_000) {
            append(fix, ride.startMs)
        }
        current = ride.copy(track = track.toString())
        if (t - lastSavedAt > SAVE_EVERY_MS) {
            lastSavedAt = t
            return Event.UPDATED
        }
        return Event.NONE
    }

    /** The ride as it should be stored when finishing, or null if it was too short to keep. */
    fun finish(): Ride? {
        val r = current?.copy(track = track.toString())
        current = null
        movingSince = 0L
        return r?.takeIf { it.distanceMeters >= MIN_RIDE_M }
    }

    fun setId(id: Long) {
        current = current?.copy(id = id)
    }

    fun setTitle(title: String) {
        current = current?.copy(title = title)
    }

    private fun append(fix: GpsFix, startMs: Long) {
        if (track.isNotEmpty()) track.append(';')
        track.append(
            String.format(Locale.ROOT, "%.5f,%.5f,%.0f,%d", fix.latitude, fix.longitude, fix.altitudeMeters ?: 0.0,
                ((fix.timeMillis - startMs) / 1000).coerceAtLeast(0))
        )
        lastPoint = fix
    }

    companion object {
        const val MOVING_KMH = 12f
        const val START_AFTER_MS = 10_000L
        const val END_AFTER_STOP_MS = 20 * 60_000L
        const val MIN_RIDE_M = 1_000.0
        const val POINT_SPACING_M = 20.0
        const val SAVE_EVERY_MS = 60_000L

        data class TrackPoint(val lat: Double, val lon: Double, val ele: Double, val secondsFromStart: Long)

        fun parseTrack(s: String): List<TrackPoint> = s.split(';').mapNotNull { p ->
            val f = p.split(',')
            if (f.size < 4) null else TrackPoint(f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f[3].toLong())
        }
    }
}
