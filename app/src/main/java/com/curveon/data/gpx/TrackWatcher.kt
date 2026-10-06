package com.curveon.data.gpx

import com.curveon.data.model.GeoPoint
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.TrackIndex
import kotlin.math.abs

/** The visible track nearest to the rider. */
data class TrackHint(
    val trackId: Long,
    val name: String,
    val color: Int,
    /** Rider position and the nearest point on the track, for the dashed line between them. */
    val from: GeoPoint,
    val to: GeoPoint,
    val distanceMeters: Double,
    /** Close enough to count as riding it. */
    val onTrack: Boolean,
    /** Riding it against its recorded direction. */
    val reverse: Boolean
)

/**
 * Watches the rider against the tracks shown on the map: which one is nearest (for the "here's
 * your track" line), and whether the rider has been riding along one for a while, which is when
 * Curveon offers (or starts) navigation along it.
 */
class TrackWatcher(private val now: () -> Long = System::currentTimeMillis) {

    private class Indexed(val track: OverlayTrack, val index: TrackIndex)

    private var tracks: List<Indexed> = emptyList()
    private var alongTrackId: Long? = null
    private var alongReverse = false
    private var alongSince = 0L

    fun setTracks(overlays: List<OverlayTrack>) {
        val old = tracks.associateBy { it.track.id }
        tracks = overlays.map { t ->
            old[t.id]?.takeIf { it.track.points === t.points }?.let { Indexed(t, it.index) }
                ?: Indexed(t, TrackIndex(t.points.map { LatLon(it.latitude, it.longitude) }))
        }
    }

    fun onFix(position: LatLon, headingDeg: Double, speedKmh: Float): TrackHint? {
        val best = tracks.mapNotNull { t -> t.index.nearest(position, SHOW_WITHIN_M)?.let { t to it } }
            .minByOrNull { it.second.distanceMeters }
        if (best == null) { alongTrackId = null; return null }
        val (t, hit) = best
        val onTrack = hit.distanceMeters <= ON_TRACK_M
        val diff = abs(Geo.angleDiff(hit.trackBearing, headingDeg))
        val reverse = diff > 120
        val aligned = diff < 60 || reverse

        if (onTrack && aligned && speedKmh >= MIN_SPEED_KMH) {
            if (alongTrackId != t.track.id || alongReverse != reverse) {
                alongTrackId = t.track.id
                alongReverse = reverse
                alongSince = now()
            }
        } else if (!onTrack) {
            alongTrackId = null
        }

        return TrackHint(
            trackId = t.track.id, name = t.track.name, color = t.track.color,
            from = GeoPoint(position.lat, position.lon), to = GeoPoint(hit.point.lat, hit.point.lon),
            distanceMeters = hit.distanceMeters, onTrack = onTrack, reverse = reverse
        )
    }

    /** The track the rider has been riding along for a while (and in which direction), if any. */
    fun ridingAlong(): Pair<OverlayTrack, Boolean>? {
        val id = alongTrackId ?: return null
        if (now() - alongSince < CONFIRM_MS) return null
        return tracks.firstOrNull { it.track.id == id }?.let { it.track to alongReverse }
    }

    fun reset() { alongTrackId = null }

    companion object {
        /** Show the line to a track within this distance. */
        const val SHOW_WITHIN_M = 5_000.0
        const val ON_TRACK_M = 35.0
        const val MIN_SPEED_KMH = 12f
        /** Riding along a track this long counts as "following it". */
        const val CONFIRM_MS = 15_000L
    }
}
