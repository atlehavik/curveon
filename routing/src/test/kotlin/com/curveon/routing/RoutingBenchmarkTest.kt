package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Timing and ferry check for the routes the tablet calculates most. Run with
 * `./gradlew :routing:test --tests '*RoutingBenchmarkTest*' -Dcurveon.bench=true`.
 */
class RoutingBenchmarkTest {
    private val home = LatLon(59.794218, 5.144344)

    private fun engine(): BRouterEngine {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(System.getProperty("curveon.bench") == "true" && File(segments, "E5_N55.rd5").exists())
        return BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()),
            memoryClassMb = (System.getProperty("curveon.mem") ?: "64").toInt())
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        println("%-48s %6.1f s".format(label, (System.nanoTime() - t0) / 1e9))
        return r
    }

    @Test
    fun `destinations`() {
        val e = engine()
        val targets = mapOf("Bergen" to LatLon(60.3913, 5.3221), "Odda" to LatLon(60.0690, 6.5459), "Geiranger" to LatLon(62.1008, 7.2059))
        for ((name, to) in targets) for (style in RoadStyle.entries) {
            val r = timed("home -> $name $style") { e.route(listOf(home, to), RouteOptions(style)) }
            println("    %.0f km, ferries=%d".format(r.distanceMeters / 1000, r.ferryCount))
        }
        for ((name, to) in targets) {
            val r = timed("home -> $name CURVY, no ferries") { e.route(listOf(home, to), RouteOptions(RoadStyle.CURVY, avoidFerries = true)) }
            println("    %.0f km, ferries=%d".format(r.distanceMeters / 1000, r.ferryCount))
        }
    }

    @Test
    fun `roundtrips avoiding ferries`() {
        val e = engine()
        for ((dir, km) in (System.getProperty("curveon.cases") ?: "0:100,0:150,0:220,45:100,45:150,45:220,90:100,90:150,90:220,135:100,135:150,135:220").split(',').map { c -> c.split(':').let { it[0].toDouble() to it[1].toDouble() } }) {
            val opts = RouteOptions(RoadStyle.CURVY, avoidFerries = true)
            val r = timed("roundtrip dir=$dir km=$km") { runCatching { RoundtripPlanner(e, ::println).plan(home, km, dir, opts) } }
            r.onSuccess { println("    %.0f km, ferries=%d curvy=%d".format(it.distanceMeters / 1000, it.ferryCount, it.curviness)) }
                .onFailure { println("    failed: ${it.message}") }
        }
    }
}
