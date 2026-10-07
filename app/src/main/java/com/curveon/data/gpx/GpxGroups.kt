package com.curveon.data.gpx

import com.curveon.data.model.GpxGroup
import com.curveon.data.model.GpxGroup.Override.CUSTOM
import com.curveon.data.model.GpxGroup.Override.OFF
import com.curveon.data.model.GpxGroup.Override.ON
import com.curveon.data.model.GpxTrack

/** How a group's tracks stand on one setting: all on, all off, or a mix. */
enum class Mix { ALL, NONE, MIXED }

/**
 * What a track looks like inside its group. A group setting is either an override for all its
 * tracks or [CUSTOM]; the tracks' own values are never touched by an override, so going back to
 * custom brings back what the rider set per track.
 */
object GpxGroups {

    fun visible(t: GpxTrack, g: GpxGroup?): Boolean = when (g?.visibility) {
        ON -> true
        OFF -> false
        else -> t.showOnMap
    }

    fun reversed(t: GpxTrack, g: GpxGroup?): Boolean = when (g?.direction) {
        ON -> true
        OFF -> false
        else -> t.reversed
    }

    fun color(t: GpxTrack, g: GpxGroup?): Int = if (g?.sameColor == true) groupColor(g) else GpxOverlay.colorOf(t)

    fun groupColor(g: GpxGroup): Int = if (g.color != 0) g.color else GpxOverlay.PALETTE[(g.id % GpxOverlay.PALETTE.size).toInt()]

    /**
     * Name for a group made from a file: what its tracks' names share, cut at a whole word
     * ("TET S-01", "TET S-02" in S.gpx → "TET S") when that says more than the file name.
     */
    fun defaultName(fileName: String, trackNames: List<String>): String {
        val file = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        if (trackNames.size < 2) return file
        var prefix = trackNames.reduce { a, b -> a.commonPrefixWith(b) }
        val first = trackNames.first()
        // Drop a word that was cut off ("TET S-0" from S-01/S-02).
        if (prefix.length < first.length && prefix.lastOrNull()?.isLetterOrDigit() == true && first[prefix.length].isLetterOrDigit()) {
            prefix = prefix.trimEnd { it.isLetterOrDigit() }
        }
        prefix = prefix.trimEnd { !it.isLetterOrDigit() }
        return if (prefix.length > file.length) prefix else file
    }

    fun mix(values: List<Boolean>): Mix = when {
        values.isNotEmpty() && values.all { it } -> Mix.ALL
        values.none { it } -> Mix.NONE
        else -> Mix.MIXED
    }

    /**
     * The group's next setting when its toggle is tapped: [first], then the opposite, then back to
     * the tracks' own settings – skipped when those are all the same anyway (custom would look
     * just like one of the two overrides).
     */
    fun next(current: Int, own: List<Boolean>, first: Int): Int {
        val second = if (first == ON) OFF else ON
        val ownLooks = when (mix(own)) { Mix.ALL -> ON; Mix.NONE -> OFF; Mix.MIXED -> CUSTOM }
        return when (if (current == CUSTOM) ownLooks else current) {
            CUSTOM -> first
            first -> second
            else -> if (ownLooks == CUSTOM) CUSTOM else first
        }
    }

    /** Eye: mixed → all shown → all hidden → the tracks' own visibility. */
    fun nextVisibility(g: GpxGroup, tracks: List<GpxTrack>): GpxGroup =
        g.copy(visibility = next(g.visibility, tracks.map { it.showOnMap }, first = ON))

    /** Direction: mixed → all as recorded → all inverted → the tracks' own direction. */
    fun nextDirection(g: GpxGroup, tracks: List<GpxTrack>): GpxGroup =
        g.copy(direction = next(g.direction, tracks.map { it.reversed }, first = OFF))

    /**
     * One track changed while its group overrides that setting: the group goes back to custom
     * with every other track kept as it looks now, so nothing else on the map changes.
     */
    fun setTrackVisible(g: GpxGroup, tracks: List<GpxTrack>, trackId: Long, show: Boolean): Pair<GpxGroup, List<GpxTrack>> {
        val tracks2 = tracks.map { t -> t.copy(showOnMap = if (t.id == trackId) show else visible(t, g)) }
        return g.copy(visibility = CUSTOM) to tracks2.changedFrom(tracks)
    }

    fun setTrackReversed(g: GpxGroup, tracks: List<GpxTrack>, trackId: Long, reversed: Boolean): Pair<GpxGroup, List<GpxTrack>> {
        val tracks2 = tracks.map { t -> t.copy(reversed = if (t.id == trackId) reversed else reversed(t, g)) }
        return g.copy(direction = CUSTOM) to tracks2.changedFrom(tracks)
    }

    fun setTrackColor(g: GpxGroup, tracks: List<GpxTrack>, trackId: Long, color: Int): Pair<GpxGroup, List<GpxTrack>> {
        val tracks2 = tracks.map { t -> t.copy(color = if (t.id == trackId) color else color(t, g)) }
        return g.copy(sameColor = false) to tracks2.changedFrom(tracks)
    }

    private fun List<GpxTrack>.changedFrom(old: List<GpxTrack>): List<GpxTrack> =
        filterIndexed { i, t -> t != old[i] }
}
