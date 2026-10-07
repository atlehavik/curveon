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
        append("<metadata><name>").append(esc(name)).append("</name></metadata>\n")
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
            val points = GpxParser.decodePoints(t.trackPointsJson).let { if (GpxGroups.reversed(t, group)) it.reversed() else it }
            append("<trk><name>").append(esc(t.name)).append("</name><trkseg>\n")
            for (p in points) {
                append(String.format(Locale.ROOT, "<trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.0f</ele></trkpt>\n", p.latitude, p.longitude, p.altitudeMeters))
            }
            append("</trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    /** "TET N-04: Lysefjorden" → "TET N-04 Lysefjorden.gpx" */
    fun fileName(name: String): String = name.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "").trim().ifEmpty { "track" } + ".gpx"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
