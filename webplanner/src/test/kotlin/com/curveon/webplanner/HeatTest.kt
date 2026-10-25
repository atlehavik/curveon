package com.curveon.webplanner

import com.curveon.routing.ProfileInstaller
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HeatTest {
  private val segments = File(System.getProperty("curveon.segments") ?: "")

  @Test fun `a file is counted in the background, then answered at once, and kept for the next start`() {
    assumeTrue(File(segments, "E5_N60.rd5").exists())
    val lookups = File(ProfileInstaller.install(Files.createTempDirectory("p").toFile()), "lookups.dat")
    val cache = Files.createTempDirectory("heat").toFile()
    val heat = Heat(segments, cache, lookups)
    // Not counted yet: no waiting for it, it is counted next.
    assertNull(heat.tile("E5_N60"))
    var body: ByteArray? = null
    for (i in 0 until 600) { body = heat.tile("E5_N60"); if (body != null) break; Thread.sleep(100) }
    val j = JSONObject(String(body ?: fail("not counted in 60 s") as ByteArray))
    assertEquals(5, j.getInt("lon")); assertEquals(60, j.getInt("lat")); assertEquals(16, j.getInt("n"))
    val c = j.getJSONArray("c")
    assertTrue(c.length() > 2000 && c.length() % 2 == 0)
    assertTrue((0 until c.length() step 2).all { c.getInt(it) in 0 until 80 * 80 })
    println("E5_N60: ${c.length() / 2} squares, ${body!!.size / 1024} kB")
    // A fresh service (a restart) has it from disk.
    assertTrue(Heat(segments, cache, lookups).tile("E5_N60") != null)
  }

  @Test fun `no routing data there is an empty answer, and a made-up name is refused`() {
    val lookups = File(ProfileInstaller.install(Files.createTempDirectory("p").toFile()), "lookups.dat")
    val heat = Heat(Files.createTempDirectory("segments").toFile(), Files.createTempDirectory("heat").toFile(), lookups)
    assertEquals("""{"c":[]}""", String(heat.tile("W75_S50")!!))
    for (bad in listOf("", "E5_N60/../x", "../E5_N60", "E5N60")) {
      try { heat.tile(bad); fail("took $bad") } catch (e: PlanRefused) { assertEquals(400, e.status) }
    }
  }
}
