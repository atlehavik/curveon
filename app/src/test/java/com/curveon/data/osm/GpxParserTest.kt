package com.curveon.data.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.curveon.data.gpx.PoiKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpxParserTest {
    @Test
    fun `each track in a file is its own result`() {
        val gpx = """
            <gpx version="1.1"><wpt lat="60" lon="10"><name>Fuel</name></wpt>
            <trk><name>TET_N-01-Øyermoen-Blåhø_20260827</name><trkseg>
              <trkpt lat="60.0" lon="10.0"/><trkpt lat="60.1" lon="10.0"/></trkseg></trk>
            <trk><name>TET_N-11_Kristiansand-Årdal_20260827</name><trkseg>
              <trkpt lat="58.1" lon="8.0"/><trkpt lat="58.2" lon="8.0"/><trkpt lat="58.3" lon="8.0"/></trkseg></trk>
            </gpx>""".trimIndent()
        val tracks = GpxParser.parseAll(gpx.byteInputStream())
        assertEquals(listOf("TET N-01 Øyermoen-Blåhø", "TET N-11 Kristiansand-Årdal"), tracks.map { it.name })
        assertEquals(listOf(2, 3), tracks.map { it.points.size })
        assertEquals(11.1, tracks[0].totalDistanceKm.toDouble(), 0.2)
    }

    @Test
    fun `waypoints keep everything the file says and survive export`() {
        val gpx = """
            <gpx version="1.1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3" xmlns:wptx1="http://www.garmin.com/xmlschemas/WaypointExtension/v1">
            <wpt lat="63.42" lon="15.84"><ele>465.4</ele><time>2025-03-15T11:39:09Z</time>
              <name>Borgvattnets Gamla Prästgård B&amp;B</name>
              <cmt>B&amp;B and Cafe</cmt><desc>Open 11-16, see www.example.se</desc>
              <link href="https://example.se/b?x=1&amp;y=2"><text>Website</text></link>
              <sym>Lodge</sym><type>user</type>
              <extensions>
                <gpxx:WaypointExtension><gpxx:Categories><gpxx:Category>Haunted</gpxx:Category></gpxx:Categories>
                  <gpxx:Address><gpxx:StreetAddress>Prästgården 1</gpxx:StreetAddress><gpxx:City>Borgvattnet</gpxx:City>
                  <gpxx:PostalCode>844 91</gpxx:PostalCode><gpxx:Country>SWE</gpxx:Country></gpxx:Address>
                  <gpxx:PhoneNumber>+46 123</gpxx:PhoneNumber></gpxx:WaypointExtension>
                <wptx1:WaypointExtension><wptx1:Address><wptx1:StreetAddress>Prästgården 1</wptx1:StreetAddress></wptx1:Address></wptx1:WaypointExtension>
              </extensions></wpt>
            <trk><name>T</name><trkseg><trkpt lat="63.4" lon="15.8"/><trkpt lat="63.5" lon="15.8"/></trkseg></trk>
            </gpx>""".trimIndent()
        val w = GpxParser.parseAll(gpx.byteInputStream()).first().waypoints.single()
        assertEquals("Open 11-16, see www.example.se", w.description)
        assertEquals("B&B and Cafe", w.comment)
        assertEquals(listOf("https://example.se/b?x=1&y=2" to "Website"), w.links)
        assertEquals(listOf("Prästgården 1", "844 91 Borgvattnet", "SWE"), w.address)
        assertEquals("+46 123", w.phone)
        assertEquals(listOf("Haunted"), w.categories)
        assertEquals(465.4, w.point.altitudeMeters, 0.01)
        assertEquals("2025-03-15T11:39:09Z", w.time)

        val poi = com.curveon.data.gpx.GpxOverlay.decodePois(com.curveon.data.gpx.GpxOverlay.encodePois(listOf(w))).single()
        assertEquals(com.curveon.data.gpx.PoiKind.LODGING, poi.kind)
        assertEquals(465.4, poi.elevation!!, 0.01)

        // Out as GPX and back in again: nothing lost.
        val track = com.curveon.data.model.GpxTrack(
            name = "T", totalDistanceKm = 1f, elevationGainMeters = 0, maxElevationMeters = 0, pointCount = 2,
            trackPointsJson = "63.4,15.8,0;63.5,15.8,0", waypointsJson = com.curveon.data.gpx.GpxOverlay.encodePois(listOf(w))
        )
        val out = com.curveon.data.gpx.GpxWriter.write("T", listOf(track))
        val again = GpxParser.parseAll(out.byteInputStream()).first().waypoints.single()
        assertEquals(w.copy(point = again.point), again)
        assertTrue(out.contains("<sym>Lodge</sym>"))
    }

    @Test
    fun `waypoint kinds come from the symbol, then the name`() {

        assertEquals(PoiKind.FERRY, PoiKind.of("Ferry", "Ferry to X"))
        assertEquals(PoiKind.SHOP, PoiKind.of("Shopping Center", "ICA"))
        assertEquals(PoiKind.FUEL, PoiKind.of("", "FUEL_B Circle K"))
        assertEquals(PoiKind.CAMPING, PoiKind.of("Campground", "Lake"))
        assertEquals(PoiKind.FERRY, PoiKind.of("Information", "INFO_Ferry to Geiranger"))
        assertEquals(PoiKind.RESTAURANT, PoiKind.of("Restaurant", "EAT_Pizza"))
    }

    @Test
    fun `track point times survive storing and export`() {
        val gpx = """
            <gpx version="1.1"><trk><name>Ride</name><trkseg>
              <trkpt lat="59.79" lon="5.14"><ele>12</ele><time>2026-10-11T09:30:12Z</time></trkpt>
              <trkpt lat="59.80" lon="5.15"><ele>14</ele><time>2026-10-11T11:30:13.4+02:00</time></trkpt>
            </trkseg></trk></gpx>""".trimIndent()
        val pts = GpxParser.parseAll(gpx.byteInputStream()).single().points
        assertEquals(listOf(1_791_711_012_000L, 1_791_711_013_400L), pts.map { it.timeMillis })
        // Stored in Room and back.
        val stored = GpxParser.decodePoints(GpxParser.encodePoints(pts))
        assertEquals(pts.map { it.timeMillis }, stored.map { it.timeMillis })
        // Written out: UTC, tenths kept.
        val xml = com.curveon.data.gpx.GpxWriter.write("Ride", listOf(com.curveon.data.model.GpxTrack(name = "Ride", totalDistanceKm = 1f,
            elevationGainMeters = 0, maxElevationMeters = 0, pointCount = 2, trackPointsJson = GpxParser.encodePoints(pts))))
        assertTrue(xml, "<time>2026-10-11T09:30:12Z</time></trkpt>" in xml)
        assertTrue(xml, "<time>2026-10-11T09:30:13.4Z</time></trkpt>" in xml)
        assertTrue(xml, "<metadata><name>Ride</name><time>2026-10-11T09:30:12Z</time></metadata>" in xml)
    }
}
