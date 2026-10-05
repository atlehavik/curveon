package com.vibenav.routing

/**
 * Turns a GPX track into a navigable route: ride from where you are to the track (joining it
 * where you are closest if you are already on it), then follow the track through dense via
 * points so the router stays on the same roads but still produces turn instructions.
 */
class TrackFollower(private val engine: BRouterEngine) {

    fun route(from: LatLon, track: List<LatLon>, options: RouteOptions): Route {
        require(track.size >= 2) { "Track has no points" }
        val joinIndex = nearestIndex(from, track).takeIf { Geo.distance(from, track[it]) < ON_TRACK_M } ?: 0
        val vias = sample(track.subList(joinIndex, track.size), VIA_SPACING_M)
        // Tight snapping keeps us on the track's roads; the approach leg uses normal snapping
        // because the rider may start far from any road the track uses.
        val onTrack = engine.route(vias, options, viaCatchingRangeMeters = VIA_CATCHING_RANGE_M)
        if (Geo.distance(from, vias.first()) < 50) return onTrack
        val approach = engine.route(listOf(from, vias.first()), options)
        return Routes.concat(approach, onTrack)
    }

    companion object {
        const val VIA_SPACING_M = 1_000.0
        const val ON_TRACK_M = 300.0
        const val VIA_CATCHING_RANGE_M = 150.0

        fun nearestIndex(p: LatLon, track: List<LatLon>): Int =
            track.indices.minByOrNull { Geo.distance(p, track[it]) } ?: 0

        /** Every point that is at least [spacing] metres along the track from the previous kept one, plus the end. */
        fun sample(track: List<LatLon>, spacing: Double): List<LatLon> {
            val out = mutableListOf(track.first())
            var acc = 0.0
            for (i in 1 until track.size) {
                acc += Geo.distance(track[i - 1], track[i])
                if (acc >= spacing) {
                    out += track[i]
                    acc = 0.0
                }
            }
            if (out.last() != track.last()) {
                if (Geo.distance(out.last(), track.last()) < spacing / 4 && out.size > 1) out[out.lastIndex] = track.last()
                else out += track.last()
            }
            return out
        }
    }
}
