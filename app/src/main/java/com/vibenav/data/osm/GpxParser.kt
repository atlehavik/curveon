package com.vibenav.data.osm

import com.vibenav.data.model.GeoPoint
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
        val description: String = ""
    )

    fun parse(inputStream: InputStream, defaultName: String = "Imported GPX Track"): ParsedGpxResult {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var docName: String? = null
        val trackPoints = mutableListOf<GeoPoint>()
        val routePoints = mutableListOf<GeoPoint>()
        val waypoints = mutableListOf<GpxWaypoint>()

        val stack = ArrayDeque<String>()
        var lat = 0.0
        var lon = 0.0
        var ele = 0.0
        var ptName = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    stack.addLast(tag)
                    if (tag == "trkpt" || tag == "rtept" || tag == "wpt") {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                        ele = 0.0
                        ptName = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty() && stack.isNotEmpty()) {
                        val parent = stack.getOrNull(stack.size - 2)
                        when (stack.last()) {
                            "ele" -> ele = text.toDoubleOrNull() ?: 0.0
                            "name" -> when (parent) {
                                "trkpt", "rtept", "wpt" -> ptName = text
                                "trk", "rte", "metadata" -> if (docName == null) docName = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = stack.removeLastOrNull()
                    if (!lat.isNaN() && !lon.isNaN()) when (tag) {
                        "trkpt" -> trackPoints += GeoPoint(lat, lon, ele)
                        "rtept" -> routePoints += GeoPoint(lat, lon, ele)
                        "wpt" -> waypoints += GpxWaypoint(ptName.ifEmpty { "Waypoint ${waypoints.size + 1}" }, GeoPoint(lat, lon, ele))
                    }
                }
            }
            parser.next()
        }

        // Prefer the recorded/planned track; fall back to a route, then to bare waypoints.
        val points = when {
            trackPoints.size >= 2 -> trackPoints
            routePoints.size >= 2 -> routePoints
            else -> waypoints.map { it.point }
        }

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

        return ParsedGpxResult(
            name = docName ?: defaultName,
            points = points,
            totalDistanceKm = totalDist.toFloat(),
            elevationGainMeters = eleGain.toInt(),
            maxElevationMeters = maxEle.toInt(),
            waypoints = waypoints
        )
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
