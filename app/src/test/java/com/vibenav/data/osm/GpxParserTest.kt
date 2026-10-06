package com.vibenav.data.osm

import org.junit.Assert.assertEquals
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
}
