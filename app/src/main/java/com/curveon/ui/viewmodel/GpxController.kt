package com.curveon.ui.viewmodel

import android.app.Application
import android.net.Uri
import com.curveon.R
import com.curveon.data.gpx.GpxGroups
import com.curveon.data.gpx.GpxWriter
import com.curveon.data.model.GpxGroup
import com.curveon.data.model.GpxTrack
import com.curveon.data.repository.CurveonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The rider's GPX tracks and groups (GPX & rides): imported from a file or the gpx/ folder, shown on the map or not,
 * coloured, turned round, renamed, moved, written out to a file and deleted.
 */
class GpxController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val repository: CurveonRepository,
) {

    /** Text in the language the rider chose (the app context follows it). */
    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private val _gpxMessage = MutableStateFlow<String?>(null)
    val gpxMessage: StateFlow<String?> = _gpxMessage.asStateFlow()

    /** The last message goes (after a language change it would be in the old language). */
    fun clearMessage() { _gpxMessage.value = null }

    private val gpxImporter = com.curveon.data.osm.GpxImporter(app, repository)

    fun importGpx(uri: Uri) {
        scope.launch {
            runCatching { gpxImporter.import(uri) }
                .onSuccess { _gpxMessage.value = if (it.size == 1) str(R.string.gpx_imported_one, it[0])
                    else app.resources.getQuantityString(R.plurals.gpx_imported_tracks, it.size, it.size) }
                .onFailure { _gpxMessage.value = str(R.string.import_failed, it.message ?: "") }
        }
    }

    private val _showGpxPanel = MutableStateFlow(false)
    /** A GPX file was opened from another app: show GPX & rides (the screen resets it once open). */
    val showGpxPanel: StateFlow<Boolean> = _showGpxPanel.asStateFlow()
    fun gpxPanelShown() { _showGpxPanel.value = false }

    /** A GPX file opened or shared from another app: imported, and GPX & rides shows it. */
    fun openGpx(uri: Uri) {
        _gpxMessage.value = null
        importGpx(uri)
        _showGpxPanel.value = true
    }

    /** GPX files copied over USB into the gpx/ folder are imported at startup. */
    fun importGpxInbox() = scope.launch {
        val names = runCatching { gpxImporter.importInbox() }.getOrDefault(emptyList())
        if (names.isNotEmpty()) _gpxMessage.value = app.resources.getQuantityString(R.plurals.gpx_imported_inbox, names.size, names.size)
    }

    fun setTrackOnMap(track: GpxTrack, show: Boolean) = scope.launch {
        // Pin the palette colour so it doesn't change when other tracks come and go.
        val pinned = track.copy(color = com.curveon.data.gpx.GpxOverlay.colorOf(track))
        inGroup(pinned) { g, tracks -> GpxGroups.setTrackVisible(g, tracks, track.id, show) } ?: repository.updateGpxTrack(pinned.copy(showOnMap = show))
    }

    fun setTrackColor(track: GpxTrack, color: Int) = scope.launch {
        inGroup(track) { g, tracks -> GpxGroups.setTrackColor(g, tracks, track.id, color) } ?: repository.updateGpxTrack(track.copy(color = color))
    }

    fun setTrackReversed(track: GpxTrack, reversed: Boolean) = scope.launch {
        inGroup(track) { g, tracks -> GpxGroups.setTrackReversed(g, tracks, track.id, reversed) } ?: repository.updateGpxTrack(track.copy(reversed = reversed))
    }

    /**
     * Applies a change to one track of a group (which may turn a group override back to custom);
     * null when the track has no group.
     */
    private suspend fun inGroup(track: GpxTrack, change: (GpxGroup, List<GpxTrack>) -> Pair<GpxGroup, List<GpxTrack>>): Unit? {
        val g = track.groupId?.let { repository.gpxGroup(it) } ?: return null
        val tracks = repository.gpxTracksInGroup(g.id).map { if (it.id == track.id) track else it }
        val (g2, changed) = change(g, tracks)
        repository.updateGpxTracks(changed)
        repository.updateGpxGroup(g2)
        return Unit
    }

    fun renameTrack(track: GpxTrack, name: String) = scope.launch {
        if (name.isNotBlank()) repository.updateGpxTrack(track.copy(name = name.trim()))
    }

    fun moveTrack(track: GpxTrack, groupId: Long?, newGroup: String?) = scope.launch {
        val target = newGroup?.takeIf { it.isNotBlank() }?.let { repository.createGpxGroup(it.trim()) } ?: groupId
        // Keep how it looks now: its old group's overrides become its own settings.
        val old = track.groupId?.let { repository.gpxGroup(it) }
        repository.updateGpxTrack(
            track.copy(
                groupId = target,
                showOnMap = GpxGroups.visible(track, old),
                reversed = GpxGroups.reversed(track, old),
                color = GpxGroups.color(track, old)
            )
        )
    }

    fun toggleGroupVisibility(g: GpxGroup) = scope.launch {
        val tracks = repository.gpxTracksInGroup(g.id)
        val next = GpxGroups.nextVisibility(g, tracks)
        // Pin palette colours, as for a single track.
        repository.updateGpxTracks(tracks.filter { it.color == 0 }.map { it.copy(color = com.curveon.data.gpx.GpxOverlay.colorOf(it)) })
        repository.updateGpxGroup(next)
    }

    fun toggleGroupDirection(g: GpxGroup) = scope.launch {
        repository.updateGpxGroup(GpxGroups.nextDirection(g, repository.gpxTracksInGroup(g.id)))
    }

    /** [color] null: back to each track's own colour; otherwise all tracks in that colour. */
    fun setGroupColor(g: GpxGroup, color: Int?) = scope.launch {
        repository.updateGpxGroup(if (color == null) g.copy(sameColor = false) else g.copy(sameColor = true, color = color))
    }

    fun renameGroup(g: GpxGroup, name: String) = scope.launch {
        if (name.isNotBlank()) repository.updateGpxGroup(g.copy(name = name.trim()))
    }

    /** Ungroup keeps the tracks (looking as they do now); delete removes them too. */
    fun deleteGroup(g: GpxGroup, keepTracks: Boolean) = scope.launch {
        if (keepTracks) repository.updateGpxTracks(repository.gpxTracksInGroup(g.id).map {
            it.copy(showOnMap = GpxGroups.visible(it, g), reversed = GpxGroups.reversed(it, g), color = GpxGroups.color(it, g))
        })
        repository.deleteGpxGroup(g.id, keepTracks)
    }

    /** Writes a track, or a whole group when [track] is null, to a GPX file the rider picked. */
    fun exportGpx(group: GpxGroup?, track: GpxTrack?, uri: Uri) = scope.launch(Dispatchers.IO) {
        val result = runCatching {
            val g = group ?: track?.groupId?.let { repository.gpxGroup(it) }
            val tracks = if (track != null) listOf(track) else repository.gpxTracksInGroup(g!!.id).sortedBy { it.name.lowercase() }
            val xml = GpxWriter.write(track?.name ?: g!!.name, tracks, g)
            app.contentResolver.openOutputStream(uri)!!.use { it.write(xml.toByteArray()) }
        }
        _gpxMessage.value = result.fold({ str(R.string.gpx_exported, track?.name ?: group?.name ?: "") }, { str(R.string.gpx_export_failed, it.message ?: "") })
    }

    fun setTrackPois(track: GpxTrack, show: Boolean) = scope.launch { repository.updateGpxTrack(track.copy(showPois = show)) }

    fun deleteGpx(track: GpxTrack) {
        scope.launch { repository.deleteGpxTrack(track.id) }
    }
}
