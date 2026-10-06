package com.vibenav.data.osm

import android.content.Context
import android.net.Uri
import com.vibenav.data.model.GpxTrack
import com.vibenav.data.repository.VibeNavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Imports GPX files: picked in the app, or copied over USB into
 * Android/data/com.vibenav.tablet/files/gpx/ (imported at startup, then moved to gpx/imported/).
 * Each track in a file becomes its own entry.
 */
class GpxImporter(private val context: Context, private val repository: VibeNavRepository) {

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
        val tracks = GpxParser.parseAll(input, fileName.substringBeforeLast('.'))
        require(tracks.any { it.points.size >= 2 }) { "No track or route in $fileName" }
        return tracks.filter { it.points.size >= 2 }.map { t ->
            repository.saveGpxTrack(
                GpxTrack(
                    name = t.name,
                    filename = fileName,
                    totalDistanceKm = t.totalDistanceKm,
                    elevationGainMeters = t.elevationGainMeters,
                    maxElevationMeters = t.maxElevationMeters,
                    pointCount = t.points.size,
                    trackPointsJson = GpxParser.encodePoints(t.points),
                    category = "Track"
                )
            )
            t.name
        }
    }
}
