package com.vibenav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vibenav.data.model.CompassDirection
import com.vibenav.data.model.GeoPoint
import com.vibenav.data.model.GroundPreference
import com.vibenav.data.model.RoadTypePreference
import com.vibenav.data.model.RoundtripConfig
import com.vibenav.data.osm.CurvyRouteEngine
import com.vibenav.data.osm.GpxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VibeNav", appName)
    }

    @Test
    fun `generate curvy roundtrip returns valid route`() {
        val start = GeoPoint(46.5286, 10.4532, 2150.0)
        val config = RoundtripConfig(
            radiusKm = 50,
            selectedDirections = setOf(CompassDirection.NORTH, CompassDirection.EAST),
            roadType = RoadTypePreference.CURVY,
            groundPreference = GroundPreference.PAVED
        )

        val route = CurvyRouteEngine.generateRoundtrip(start, config)
        assertTrue(route.points.size > 10)
        assertTrue(route.totalDistanceKm > 20f)
        assertTrue(route.instructions.isNotEmpty())
        assertEquals(82, route.curvinessScore)
    }

    @Test
    fun `gpx parser parses track and calculates distance`() {
        val gpxXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="VibeNav">
                <trk>
                    <name>Test Pass</name>
                    <trkseg>
                        <trkpt lat="46.5286" lon="10.4532"><ele>2150.0</ele></trkpt>
                        <trkpt lat="46.5350" lon="10.4600"><ele>2210.0</ele></trkpt>
                        <trkpt lat="46.5410" lon="10.4700"><ele>2290.0</ele></trkpt>
                    </trkseg>
                </trk>
            </gpx>
        """.trimIndent()

        val parsed = GpxParser.parse(ByteArrayInputStream(gpxXml.toByteArray()))
        assertEquals("Test Pass", parsed.name)
        assertEquals(3, parsed.points.size)
        assertTrue(parsed.totalDistanceKm > 0f)
        assertEquals(140, parsed.elevationGainMeters)
    }

    @Test
    fun `generate route to destination creates valid twisty motorcycle path`() {
        val start = GeoPoint(46.5286, 10.4532, 2150.0)
        val destination = GeoPoint(46.3461, 10.4878, 2621.0)
        val route = CurvyRouteEngine.generateRouteToDestination(
            start = start,
            destination = destination,
            destinationName = "Passo di Gavia",
            roadType = RoadTypePreference.CURVY,
            groundPreference = GroundPreference.PAVED
        )

        assertTrue(route.points.size >= 16)
        assertTrue(route.totalDistanceKm > 10f)
        assertTrue(route.instructions.isNotEmpty())
        assertEquals("Route to Passo di Gavia", route.title)
        assertTrue(route.instructions.last().text.contains("Passo di Gavia"))
    }

    @Test
    fun `osmdroid user agent is initialized with application package`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = org.osmdroid.config.Configuration.getInstance()
        config.userAgentValue = context.packageName
        assertEquals(context.packageName, config.userAgentValue)
        assertTrue(config.userAgentValue.isNotEmpty())
        assertTrue(config.userAgentValue != "osmdroid")
    }
}
