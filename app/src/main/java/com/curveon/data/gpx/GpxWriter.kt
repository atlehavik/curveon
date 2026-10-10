package com.curveon.data.gpx

import com.curveon.data.model.GpxGroup
import com.curveon.data.model.GpxTrack
import com.curveon.data.osm.GpxParser
import java.util.Locale

/** Writes tracks from the collection back out as GPX 1.1, one <trk> each. */
object GpxWriter {

    /** [tracks] are written in the direction they are ridden ([GpxGroups.reversed]), with their waypoints. */
    fun write(name: String, tracks: List<GpxTrack>, group: GpxGroup? = null): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Curveon" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        val start = tracks.firstNotNullOfOrNull { t -> GpxParser.decodePoints(t.trackPointsJson).firstNotNullOfOrNull { it.timeMillis } }
        append("<metadata><name>").append(esc(name)).append("</name>")
        start?.let { append("<time>").append(time(it)).append("</time>") }
        append("</metadata>\n")
        for (t in tracks) for (w in GpxOverlay.decodeWaypoints(t.waypointsJson)) {
            append(String.format(Locale.ROOT, "<wpt lat=\"%.6f\" lon=\"%.6f\">", w.point.latitude, w.point.longitude))
            if (w.hasElevation) append(String.format(Locale.ROOT, "<ele>%.1f</ele>", w.point.altitudeMeters))
            if (w.time.isNotEmpty()) append("<time>").append(esc(w.time)).append("</time>")
            append("<name>").append(esc(w.name)).append("</name>")
            if (w.comment.isNotEmpty()) append("<cmt>").append(esc(w.comment)).append("</cmt>")
            if (w.description.isNotEmpty()) append("<desc>").append(esc(w.description)).append("</desc>")
            for ((href, text) in w.links) {
                append("<link href=\"").append(esc(href).replace("\"", "&quot;")).append("\">")
                if (text.isNotEmpty()) append("<text>").append(esc(text)).append("</text>")
                append("</link>")
            }
            if (w.symbol.isNotEmpty()) append("<sym>").append(esc(w.symbol)).append("</sym>")
            if (w.type.isNotEmpty()) append("<type>").append(esc(w.type)).append("</type>")
            if (w.phone.isNotEmpty() || w.categories.isNotEmpty() || w.address.isNotEmpty()) {
                // Address lines are kept as read, so they go back out as one street line each.
                append("<extensions><gpxx:WaypointExtension xmlns:gpxx=\"http://www.garmin.com/xmlschemas/GpxExtensions/v3\">")
                if (w.phone.isNotEmpty()) append("<gpxx:PhoneNumber>").append(esc(w.phone)).append("</gpxx:PhoneNumber>")
                if (w.categories.isNotEmpty()) {
                    append("<gpxx:Categories>")
                    w.categories.forEach { append("<gpxx:Category>").append(esc(it)).append("</gpxx:Category>") }
                    append("</gpxx:Categories>")
                }
                if (w.address.isNotEmpty()) {
                    append("<gpxx:Address>")
                    w.address.forEach { append("<gpxx:StreetAddress>").append(esc(it)).append("</gpxx:StreetAddress>") }
                    append("</gpxx:Address>")
                }
                append("</gpxx:WaypointExtension></extensions>")
            }
            append("</wpt>\n")
        }
        for (t in tracks) {
            // Ridden the other way round, the times would run backwards: none then.
            val points = GpxParser.decodePoints(t.trackPointsJson).let { if (GpxGroups.reversed(t, group)) it.reversed().map { p -> p.copy(timeMillis = null) } else it }
            append("<trk><name>").append(esc(t.name)).append("</name><trkseg>\n")
            for (p in points) {
                append(String.format(Locale.ROOT, "<trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.0f</ele>", p.latitude, p.longitude, p.altitudeMeters))
                // When it was ridden, so the track can be lined up with a video of the ride.
                p.timeMillis?.let { append("<time>").append(time(it)).append("</time>") }
                append("</trkpt>\n")
            }
            append("</trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    /** "TET N-04: Lysefjorden" → "TET N-04 Lysefjorden.gpx" */
    fun fileName(name: String): String = name.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "").trim().ifEmpty { "track" } + ".gpx"

    /** UTC, with tenths when there are any: "2026-10-11T09:30:12.4Z". */
    fun time(epochMs: Long): String {
        val i = java.time.Instant.ofEpochMilli(epochMs)
        val base = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(i)
        val tenths = (epochMs % 1000) / 100
        return if (tenths != 0L) "$base.${tenths}Z" else "${base}Z"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
