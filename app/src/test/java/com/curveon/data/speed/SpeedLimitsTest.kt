package com.curveon.data.speed

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reads speed limits as tools/speedlimits/build_speedlimits.py writes them: the test file is NVDB's
 * public roads in Bømlo, Stord and Fitjar (2026-10-11), the points spots on four of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedLimitsTest {
    private lateinit var limits: SpeedLimits

    @Before
    fun setUp() {
        limits = SpeedLimits(ApplicationProvider.getApplicationContext())
        javaClass.getResourceAsStream("/speedlimits/sunnhordland.sqlite")!!.use { input ->
            limits.file("sunnhordland").outputStream().use { input.copyTo(it) }
        }
    }

    @Test
    fun `limits of roads, either way along them`() {
        assertEquals(30, limits.at(59.76100482, 5.46759165, 217.0))
        assertEquals(50, limits.at(59.80294771, 5.52206549, 18.0))
        assertEquals(60, limits.at(59.79836785, 5.19977102, 270.0))
        assertEquals(80, limits.at(59.77602769, 5.24844787, 29.0))
        // Riding the other way along the same road.
        assertEquals(80, limits.at(59.77602769, 5.24844787, 209.0))
        // A few metres off the line (GPS) still counts.
        assertEquals(60, limits.at(59.79836785 + 0.0001, 5.19977102, 270.0))
    }

    @Test
    fun `nothing across the road, far from it, or abroad`() {
        assertNull(limits.at(59.77602769, 5.24844787, 119.0)) // crossing it
        assertNull(limits.at(59.7790, 5.2484, 29.0)) // ~300 m away
        assertNull(limits.at(48.85, 2.35, 0.0)) // Paris
    }
}
