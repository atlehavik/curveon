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
    /** The rider ended a ride by hand: no new one until the bike has stood still once. */
    private var holdUntilStopped = false

    fun onFix(fix: GpsFix, speedKmh: Float, leanDeg: Float?): Event {
        val t = now()
        val moving = speedKmh >= MOVING_KMH
        if (moving) lastMovingAt = t

        var ride = current
        if (ride == null) {
            if (holdUntilStopped) { if (!moving) holdUntilStopped = false; return Event.NONE }
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

    /** [finish], for a ride the rider ended by hand: riding on does not start a new one straight away. */
    fun stop(): Ride? = finish().also { holdUntilStopped = true }

    fun setId(id: Long) {
        current = current?.copy(id = id)
    }

    fun setTitle(title: String) {
        current = current?.copy(title = title)
    }

    private fun append(fix: GpsFix, startMs: Long) {
        if (track.isNotEmpty()) track.append(';')
        track.append(
            // Tenths of a second, so a GPX of the ride lines up with a video of it.
            String.format(Locale.ROOT, "%.5f,%.5f,%.0f,%.1f", fix.latitude, fix.longitude, fix.altitudeMeters ?: 0.0,
                ((fix.timeMillis - startMs) / 1000.0).coerceAtLeast(0.0))
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

        /**
         * Rides joined in time order into one, e.g. a day's ride split by a long lunch. The title runs
         * from where the first began to where the last ended; every track point keeps its time
         * counted from the first ride's start. Without an id: it is a new ride.
         */
        fun merge(rides: List<Ride>): Ride {
            val sorted = rides.sortedBy { it.startMs }
            val first = sorted.first()
            val last = sorted.last()
            val start = first.title.substringBefore(" → ")
            val end = last.title.substringAfterLast(" → ")
            val track = sorted.joinToString(";") { r ->
                val shift = (r.startMs - first.startMs) / 1000.0
                parseTrack(r.track).joinToString(";") {
                    String.format(Locale.ROOT, "%.5f,%.5f,%.0f,%.1f", it.lat, it.lon, it.ele, it.secondsFromStart + shift)
                }
            }
            return Ride(
                title = if (start.isNotEmpty() && end.isNotEmpty() && start != end) "$start → $end" else start.ifEmpty { end },
                startMs = first.startMs,
                endMs = last.endMs,
                distanceMeters = sorted.sumOf { it.distanceMeters },
                movingMillis = sorted.sumOf { it.movingMillis },
                maxSpeedKmh = sorted.maxOf { it.maxSpeedKmh },
                ascentMeters = sorted.sumOf { it.ascentMeters },
                maxLeanLeftDeg = sorted.maxOf { it.maxLeanLeftDeg },
                maxLeanRightDeg = sorted.maxOf { it.maxLeanRightDeg },
                track = track
            )
        }

        data class TrackPoint(val lat: Double, val lon: Double, val ele: Double, val secondsFromStart: Double)

        /** Rides recorded before 2026-10-11 have whole seconds, later ones tenths. */
        fun parseTrack(s: String): List<TrackPoint> = s.split(';').mapNotNull { p ->
            val f = p.split(',')
            if (f.size < 4) null else TrackPoint(f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f[3].toDouble())
        }
    }
}
