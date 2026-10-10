package com.curveon.data.osm

import com.curveon.data.model.GeoPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.*

/** Reads GPX 1.0/1.1 tracks (trk/trkseg/trkpt), routes (rte/rtept) and waypoints (wpt). */
object GpxParser {

    data class ParsedGpxResult(
        val name: String,
        val points: List<GeoPoint>,
        val totalDistanceKm: Float,
        val elevationGainMeters: Int,
        val maxElevationMeters: Int,
        val waypoints: List<GpxWaypoint>
    )

    data class GpxWaypoint(
        val name: String,
        val point: GeoPoint,
        val description: String = "",
        /** GPX <sym>, e.g. "Campground", "Gas Station". */
        val symbol: String = "",
        /** <cmt>, when it says something else than the description. */
        val comment: String = "",
        /** <link href> with its <text> (may be empty). */
        val links: List<Pair<String, String>> = emptyList(),
        /** <time>, as written in the file. */
        val time: String = "",
        /** <type>, e.g. "user". */
        val type: String = "",
        /** From Garmin extensions: street, postal code + city, state, country. */
        val address: List<String> = emptyList(),
        val phone: String = "",
        /** Garmin categories the creator sorted it into. */
        val categories: List<String> = emptyList(),
        val hasElevation: Boolean = false
    )

    /** First track of the file, or all tracks joined when [parseAll] finds just one. */
    fun parse(inputStream: InputStream, defaultName: String = "Imported GPX Track"): ParsedGpxResult =
        parseAll(inputStream, defaultName).first()

    /**
     * Every <trk> (and <rte>) as its own result: files like the TET country downloads hold many
     * sections that must not be joined into one line.
     */
    fun parseAll(inputStream: InputStream, defaultName: String = "Imported GPX Track"): List<ParsedGpxResult> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var docName: String? = null
        val tracks = mutableListOf<Pair<String?, MutableList<GeoPoint>>>() // (name, points) per trk/rte
        var current: MutableList<GeoPoint>? = null
        var currentName: String? = null
        val waypoints = mutableListOf<GpxWaypoint>()

        val stack = ArrayDeque<String>()
        var lat = 0.0
        var lon = 0.0
        var ele = 0.0
        var ptName = ""
        var ptDesc = ""
        var ptSym = ""
        var ptCmt = ""
        var ptTime = ""
        var trkTime: Long? = null
        var ptType = ""
        var ptPhone = ""
        var ptHasEle = false
        val ptLinks = mutableListOf<Pair<String, String>>()
        val ptAddr = LinkedHashMap<String, String>()
        val ptCats = LinkedHashSet<String>()
        val ptStreets = LinkedHashSet<String>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    stack.addLast(tag)
                    when (tag) {
                        "trk", "rte" -> { current = mutableListOf(); currentName = null }
                        "trkpt", "rtept", "wpt" -> {
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                            ele = 0.0
                            trkTime = null
                            ptName = ""
                            ptDesc = ""
                            ptSym = ""
                            ptCmt = ""; ptTime = ""; ptType = ""; ptPhone = ""; ptHasEle = false
                            ptLinks.clear(); ptAddr.clear(); ptCats.clear(); ptStreets.clear()
                        }
                        "link" -> if ("wpt" in stack) parser.getAttributeValue(null, "href")?.let { ptLinks += it to "" }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty() && stack.isNotEmpty()) {
                        val parent = stack.getOrNull(stack.size - 2)
                        val inWpt = "wpt" in stack
                        when (stack.last()) {
                            "ele" -> { ele = text.toDoubleOrNull() ?: 0.0; if (parent == "wpt") ptHasEle = true }
                            "sym" -> if (parent == "wpt") ptSym = text
                            "desc" -> if (parent == "wpt") ptDesc = text
                            "cmt" -> if (parent == "wpt") ptCmt = text
                            "time" -> if (parent == "wpt") ptTime = text else if (parent == "trkpt" || parent == "rtept") trkTime = parseTime(text)
                            "type" -> if (parent == "wpt") ptType = text
                            "text" -> if (parent == "link" && inWpt && ptLinks.isNotEmpty()) ptLinks[ptLinks.size - 1] = ptLinks.last().first to text
                            // Garmin gpxx/wptx1 extensions repeat the same fields: keep the first.
                            "StreetAddress" -> if (inWpt) ptStreets += text
                            "City", "State", "PostalCode", "Country" -> if (inWpt) ptAddr.putIfAbsent(stack.last(), text)
                            "PhoneNumber" -> if (inWpt && ptPhone.isEmpty()) ptPhone = text
                            "Category" -> if (inWpt) ptCats += text
                            "name" -> when (parent) {
                                "trkpt", "rtept", "wpt" -> ptName = text
                                "trk", "rte" -> if (currentName == null) currentName = text
                                "metadata" -> if (docName == null) docName = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = stack.removeLastOrNull()
                    when (tag) {
                        "trk", "rte" -> current?.let { if (it.size >= 2) tracks += currentName to it }.also { current = null }
                        "trkpt", "rtept" -> if (!lat.isNaN() && !lon.isNaN()) current?.add(GeoPoint(lat, lon, ele, trkTime))
                        "wpt" -> if (!lat.isNaN() && !lon.isNaN()) {
                            val desc = ptDesc.ifEmpty { ptCmt }
                            waypoints += GpxWaypoint(
                                ptName.ifEmpty { "Waypoint ${waypoints.size + 1}" }, GeoPoint(lat, lon, ele), desc, ptSym,
                                comment = ptCmt.takeIf { it != desc }.orEmpty(),
                                links = ptLinks.toList(), time = ptTime, type = ptType,
                                address = ptStreets.toList() + listOfNotNull(
                                    listOfNotNull(ptAddr["PostalCode"], ptAddr["City"]).joinToString(" ").ifEmpty { null },
                                    ptAddr["State"], ptAddr["Country"]
                                ),
                                phone = ptPhone, categories = ptCats.toList(), hasElevation = ptHasEle
                            )
                        }
                    }
                }
            }
            parser.next()
        }

        if (tracks.isEmpty()) tracks += (docName ?: defaultName) to waypoints.map { it.point }.toMutableList()
        return tracks.map { (name, points) ->
            summarize(prettyName(name ?: docName ?: defaultName), points, waypoints)
        }
    }

    /** "TET_N-04-Lysefjorden-Utne_20260827" → "TET N-04 Lysefjorden-Utne". */
    internal fun prettyName(raw: String): String =
        raw.replace(Regex("_\\d{8}$"), "")
            .replace(Regex("^(TET)_([A-Z]{1,3})-(\\d+)[-_]"), "$1 $2-$3 ")
            .replace('_', ' ')
            .trim()

    private fun summarize(name: String, points: List<GeoPoint>, waypoints: List<GpxWaypoint>): ParsedGpxResult {
        var totalDist = 0.0
        var eleGain = 0.0
        var maxEle = points.firstOrNull()?.altitudeMeters ?: 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            totalDist += haversineDistanceKm(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            if (p2.altitudeMeters > p1.altitudeMeters) eleGain += p2.altitudeMeters - p1.altitudeMeters
            maxEle = max(maxEle, p2.altitudeMeters)
        }
        return ParsedGpxResult(name, points, totalDist.toFloat(), eleGain.toInt(), maxEle.toInt(), waypoints)
    }

    /** Compact "lat,lon,ele[,epoch ms];…" encoding used to store tracks in Room (the time when known). */
    fun encodePoints(points: List<GeoPoint>): String =
        points.joinToString(";") {
            "%.6f,%.6f,%.0f".format(java.util.Locale.ROOT, it.latitude, it.longitude, it.altitudeMeters) + (it.timeMillis?.let { t -> ",$t" } ?: "")
        }

    fun decodePoints(s: String): List<GeoPoint> =
        if (s.isBlank() || s == "[]") emptyList()
        else s.split(';').mapNotNull { p ->
            val parts = p.split(',')
            if (parts.size < 2) null
            else GeoPoint(parts[0].toDouble(), parts[1].toDouble(), parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0, parts.getOrNull(3)?.toLongOrNull())
        }

    /** A GPX <time> ("2026-10-11T09:30:12Z", "…12.5Z", "…12+02:00") as epoch ms; null if unreadable. */
    fun parseTime(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()

    fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
