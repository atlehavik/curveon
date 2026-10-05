package com.example.data.osm

import android.util.Xml
import com.example.data.model.GeoPoint
import com.example.data.model.GpxTrack
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.*

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
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var trackName = defaultName
        val points = mutableListOf<GeoPoint>()
        val waypoints = mutableListOf<GpxWaypoint>()

        var currentTag = ""
        var currentWptName = ""
        var currentWptLat = 0.0
        var currentWptLon = 0.0

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (currentTag) {
                        "trkpt" -> {
                            val latStr = parser.getAttributeValue(null, "lat")
                            val lonStr = parser.getAttributeValue(null, "lon")
                            if (latStr != null && lonStr != null) {
                                val lat = latStr.toDoubleOrNull() ?: 0.0
                                val lon = lonStr.toDoubleOrNull() ?: 0.0
                                points.add(GeoPoint(lat, lon, 0.0))
                            }
                        }
                        "wpt" -> {
                            val latStr = parser.getAttributeValue(null, "lat")
                            val lonStr = parser.getAttributeValue(null, "lon")
                            currentWptLat = latStr?.toDoubleOrNull() ?: 0.0
                            currentWptLon = lonStr?.toDoubleOrNull() ?: 0.0
                            currentWptName = ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            "name" -> {
                                if (trackName == defaultName) {
                                    trackName = text
                                }
                                if (currentWptName.isEmpty()) {
                                    currentWptName = text
                                }
                            }
                            "ele" -> {
                                val ele = text.toDoubleOrNull() ?: 0.0
                                if (points.isNotEmpty()) {
                                    val last = points.last()
                                    points[points.size - 1] = last.copy(altitudeMeters = ele)
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "wpt" && (currentWptLat != 0.0 || currentWptLon != 0.0)) {
                        waypoints.add(
                            GpxWaypoint(
                                name = if (currentWptName.isNotEmpty()) currentWptName else "Waypoint ${waypoints.size + 1}",
                                point = GeoPoint(currentWptLat, currentWptLon)
                            )
                        )
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        var totalDist = 0.0
        var eleGain = 0.0
        var maxEle = 0.0

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            totalDist += haversineDistanceKm(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            if (p2.altitudeMeters > p1.altitudeMeters) {
                eleGain += (p2.altitudeMeters - p1.altitudeMeters)
            }
            if (p2.altitudeMeters > maxEle) {
                maxEle = p2.altitudeMeters
            }
        }

        return ParsedGpxResult(
            name = trackName,
            points = points,
            totalDistanceKm = totalDist.toFloat(),
            elevationGainMeters = eleGain.toInt(),
            maxElevationMeters = maxEle.toInt(),
            waypoints = waypoints
        )
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
