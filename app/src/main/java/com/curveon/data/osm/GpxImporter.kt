package com.curveon.data.osm

import android.content.Context
import android.net.Uri
import com.curveon.data.model.GpxTrack
import com.curveon.data.repository.CurveonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Imports GPX files: picked in the app, or copied over USB into
 * Android/data/com.curveon.tablet/files/gpx/ (imported at startup, then moved to gpx/imported/).
 * Each track in a file becomes its own entry; a file with several tracks becomes a group.
 */
class GpxImporter(private val context: Context, private val repository: CurveonRepository) {

    val inboxDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "gpx").apply { mkdirs() }

    /** Returns the names of the imported tracks. */
    suspend fun import(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val fileName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "track.gpx"
        resolver.openInputStream(uri)!!.use { save(it, fileName) }
    }

    /** Imports everything waiting in the inbox folder. */
    suspend fun importInbox(): List<String> = withContext(Dispatchers.IO) {
        val files = inboxDir.listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }.orEmpty()
        val done = File(inboxDir, "imported").apply { mkdirs() }
        files.flatMap { f ->
            val names = runCatching { f.inputStream().use { save(it, f.name) } }.getOrDefault(emptyList())
            f.renameTo(File(done, f.name))
            names
        }
    }

    private suspend fun save(input: InputStream, fileName: String): List<String> {
        val tracks = GpxParser.parseAll(input, fileName.substringBeforeLast('.')).filter { it.points.size >= 2 }
        require(tracks.isNotEmpty()) { "No track or route in $fileName" }
        // Waypoints belong to the file; give each one to the track it is closest to.
        val waypoints = tracks.first().waypoints
        val byTrack = waypoints.groupBy { w ->
            tracks.indices.minBy { i -> nearestKm(tracks[i].points, w.point) }
        }
        // Several tracks: put new ones in a group named after the file (made only when needed).
        var group: Long? = null
        val groupName = com.curveon.data.gpx.GpxGroups.defaultName(GpxParser.prettyName(fileName.substringBeforeLast('.')), tracks.map { it.name })
        return tracks.mapIndexed { i, t ->
            // Re-importing (e.g. a fresh TET download) updates the track and keeps the rider's
            // map settings for it.
            val existing = repository.findGpxTrack(t.name)
            val groupId = if (existing != null) existing.groupId
            else if (tracks.size > 1) group ?: repository.gpxGroupNamed(groupName).id.also { group = it }
            else null
            repository.saveGpxTrack(
                GpxTrack(
                    id = existing?.id ?: 0,
                    showOnMap = existing?.showOnMap ?: false,
                    color = existing?.color ?: 0,
                    showPois = existing?.showPois ?: true,
                    reversed = existing?.reversed ?: false,
                    groupId = groupId,
                    name = t.name,
                    filename = fileName,
                    totalDistanceKm = t.totalDistanceKm,
                    elevationGainMeters = t.elevationGainMeters,
                    maxElevationMeters = t.maxElevationMeters,
                    pointCount = t.points.size,
                    trackPointsJson = GpxParser.encodePoints(t.points),
                    waypointsJson = com.curveon.data.gpx.GpxOverlay.encodePois(byTrack[i].orEmpty()),
                    category = "Track"
                )
            )
            t.name
        }
    }

    private fun nearestKm(points: List<com.curveon.data.model.GeoPoint>, p: com.curveon.data.model.GeoPoint): Double {
        var best = Double.MAX_VALUE
        // Every 5th point is plenty to decide which track a waypoint belongs to.
        for (i in points.indices step 5) {
            val q = points[i]
            best = minOf(best, GpxParser.haversineDistanceKm(p.latitude, p.longitude, q.latitude, q.longitude))
        }
        return best
    }
}
