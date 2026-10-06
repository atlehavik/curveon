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
        val symbol: String = ""
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
                            ptName = ""
                            ptDesc = ""
                            ptSym = ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty() && stack.isNotEmpty()) {
                        val parent = stack.getOrNull(stack.size - 2)
                        when (stack.last()) {
                            "ele" -> ele = text.toDoubleOrNull() ?: 0.0
                            "sym" -> if (parent == "wpt") ptSym = text
                            "desc", "cmt" -> if (parent == "wpt" && ptDesc.isEmpty()) ptDesc = text
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
                        "trkpt", "rtept" -> if (!lat.isNaN() && !lon.isNaN()) current?.add(GeoPoint(lat, lon, ele))
                        "wpt" -> if (!lat.isNaN() && !lon.isNaN()) waypoints += GpxWaypoint(ptName.ifEmpty { "Waypoint ${waypoints.size + 1}" }, GeoPoint(lat, lon, ele), ptDesc, ptSym)
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

    /** Compact "lat,lon,ele;lat,lon,ele" encoding used to store tracks in Room. */
    fun encodePoints(points: List<GeoPoint>): String =
        points.joinToString(";") { "%.6f,%.6f,%.0f".format(java.util.Locale.ROOT, it.latitude, it.longitude, it.altitudeMeters) }

    fun decodePoints(s: String): List<GeoPoint> =
        if (s.isBlank() || s == "[]") emptyList()
        else s.split(';').mapNotNull { p ->
            val parts = p.split(',')
            if (parts.size < 2) null
            else GeoPoint(parts[0].toDouble(), parts[1].toDouble(), parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0)
        }

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
