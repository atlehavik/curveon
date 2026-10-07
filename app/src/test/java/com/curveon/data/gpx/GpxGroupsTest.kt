package com.curveon.data.gpx

import com.curveon.data.model.GpxGroup
import com.curveon.data.model.GpxGroup.Override.CUSTOM
import com.curveon.data.model.GpxGroup.Override.OFF
import com.curveon.data.model.GpxGroup.Override.ON
import com.curveon.data.model.GpxTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class GpxGroupsTest {
    private fun track(id: Long, shown: Boolean = false, reversed: Boolean = false, color: Int = 0) = GpxTrack(
        id = id, name = "t$id", totalDistanceKm = 1f, elevationGainMeters = 0, maxElevationMeters = 0, pointCount = 2,
        trackPointsJson = "", showOnMap = shown, reversed = reversed, color = color, groupId = 1
    )

    private val group = GpxGroup(id = 1, name = "TET")

    @Test
    fun eyeCyclesThroughAllShownAllHiddenAndBackToTheRidersMix() {
        val tracks = listOf(track(1, shown = true), track(2), track(3, shown = true))
        assertEquals(Mix.MIXED, GpxGroups.mix(tracks.map { GpxGroups.visible(it, group) }))
        val on = GpxGroups.nextVisibility(group, tracks)
        assertEquals(ON, on.visibility)
        assertEquals(Mix.ALL, GpxGroups.mix(tracks.map { GpxGroups.visible(it, on) }))
        val off = GpxGroups.nextVisibility(on, tracks)
        assertEquals(OFF, off.visibility)
        val back = GpxGroups.nextVisibility(off, tracks)
        assertEquals(CUSTOM, back.visibility)
        assertEquals(listOf(true, false, true), tracks.map { GpxGroups.visible(it, back) })
    }

    @Test
    fun eyeJustTogglesWhenTheTracksAgree() {
        val tracks = listOf(track(1), track(2))
        val on = GpxGroups.nextVisibility(group, tracks)
        assertEquals(ON, on.visibility)
        assertEquals(OFF, GpxGroups.nextVisibility(on, tracks).visibility)
        assertEquals(ON, GpxGroups.nextVisibility(GpxGroups.nextVisibility(on, tracks), tracks).visibility)
    }

    @Test
    fun hidingOneTrackOfAShownGroupKeepsTheOthersShown() {
        val tracks = listOf(track(1), track(2), track(3))
        val all = group.copy(visibility = ON)
        val (g, changed) = GpxGroups.setTrackVisible(all, tracks, trackId = 2, show = false)
        assertEquals(CUSTOM, g.visibility)
        val after = tracks.map { t -> changed.firstOrNull { it.id == t.id } ?: t }
        assertEquals(listOf(true, false, true), after.map { GpxGroups.visible(it, g) })
    }

    @Test
    fun directionCyclesStandardInvertedCustom() {
        val tracks = listOf(track(1, reversed = true), track(2))
        val std = GpxGroups.nextDirection(group, tracks)
        assertEquals(OFF, std.direction)
        assertEquals(listOf(false, false), tracks.map { GpxGroups.reversed(it, std) })
        val inv = GpxGroups.nextDirection(std, tracks)
        assertEquals(listOf(true, true), tracks.map { GpxGroups.reversed(it, inv) })
        val custom = GpxGroups.nextDirection(inv, tracks)
        assertEquals(listOf(true, false), tracks.map { GpxGroups.reversed(it, custom) })
    }

    @Test
    fun sameColourIsOverriddenButOwnColoursAreKept() {
        val tracks = listOf(track(1, color = 1), track(2, color = 2))
        val same = group.copy(sameColor = true, color = 9)
        assertEquals(listOf(9, 9), tracks.map { GpxGroups.color(it, same) })
        assertEquals(listOf(1, 2), tracks.map { GpxGroups.color(it, same.copy(sameColor = false)) })
    }

    @Test
    fun groupIsNamedAfterWhatItsTracksShare() {
        assertEquals("TET S", GpxGroups.defaultName("S.gpx", listOf("TET S-01", "TET S-02", "TET S-13")))
        assertEquals("Trollstigen loop", GpxGroups.defaultName("Trollstigen_loop.gpx", listOf("Day 1", "Day 2")))
        assertEquals("Lofoten", GpxGroups.defaultName("trip.gpx", listOf("Lofoten north", "Lofoten south")))
    }
}
