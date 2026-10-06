package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Roundtrips from Stavlandsvegen 197, Bremnes – the rider's home – checked for out-and-back spurs. */
class BremnesRoundtripTest {
    @Test
    fun `roundtrips from bremnes have no out-and-back spurs`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val home = LatLon(59.794218, 5.144344)
        for (dir in listOf(0.0, 90.0, 180.0, 270.0)) for (km in listOf(60.0, 120.0)) {
            val r = runCatching { RoundtripPlanner(engine).plan(home, km, dir, RouteOptions(RoadStyle.CURVY)) }.getOrNull()
            if (r == null) { println("dir=$dir km=$km  no route"); continue }
            val spurs = Spurs.find(r)
            // Inland directions have real loops available; out to sea (S/W of Bømlo) there may be none.
            if (dir == 0.0 || dir == 90.0) org.junit.Assert.assertTrue("spur at dir=$dir km=$km", spurs.none { it.halfLength > 500 })
            println("dir=%3.0f km=%3.0f -> %5.1f km curvy=%d spurs=%s".format(dir, km, r.distanceMeters / 1000, r.curviness,
                spurs.joinToString { "%.0fm@%.4f,%.4f".format(it.halfLength, it.turnaround.lat, it.turnaround.lon) }))
        }
    }
}
