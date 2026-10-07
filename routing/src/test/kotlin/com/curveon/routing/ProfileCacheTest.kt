package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * BRouter's profile cache once mixed up option sets whose parameter hash codes add up to the same
 * sum ("curvy, no ferries" and "very curvy, ferries"). Each route must match a fresh engine's.
 */
class ProfileCacheTest {
    @Test
    fun `each option set gets its own profile`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val home = LatLon(59.794218, 5.144344)
        val bergen = LatLon(60.3913, 5.3221)
        val all = RoadStyle.entries.flatMap { style -> listOf(false, true).map { RouteOptions(style, avoidFerries = it) } }
        val shared = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val sequential = all.associateWith { shared.route(listOf(home, bergen), it).let { r -> r.ferryCount to r.distanceMeters.toInt() / 1000 } }
        // Same requests again in reverse order: a cache mix-up would now hand out a different profile.
        for (o in all.reversed()) {
            val r = shared.route(listOf(home, bergen), o)
            assertEquals("$o", sequential[o], r.ferryCount to r.distanceMeters.toInt() / 1000)
        }
        all.filter { it.avoidFerries }.forEach { assertEquals("$it", 0, sequential[it]!!.first) }
    }
}
