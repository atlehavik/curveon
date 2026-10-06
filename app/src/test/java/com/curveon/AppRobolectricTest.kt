package com.curveon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.curveon.data.osm.GpxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Curveon", appName)
    }


    @Test
    fun `gpx parser parses track and calculates distance`() {
        val gpxXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Curveon">
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
}
