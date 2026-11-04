package com.curveon.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GpxShareTest {
  private fun track(name: String, points: String) = SharedGpxTrack(
    name, "$name.gpx", 12.5f, 300, 900, points.count { it == ';' } + 1, points, "[{\"name\":\"Camp\"}]", true, "TET Section",
    showOnMap = true, color = 0xFF112233.toInt(), showPois = false, reversed = true,
  )

  private val group = SharedGpx(
    "TET Sør", isGroup = true,
    listOf(track("S-01", "59.798800,5.207600,12;59.798900,5.207700,13"), track("S-02", "-33.100000,-70.200000,0,1760000000000;-33.100100,-70.200100,-3,1760000005000")),
    visibility = 1, direction = 2, sameColor = true, color = 0xFF445566.toInt(),
  )

  /** Road-like points that compress about as real ones: [count] steps with some noise. */
  private fun road(count: Int, seed: Long): String {
    val r = Random(seed)
    var lat = 59.0; var lon = 5.0; var ele = 100
    return (0 until count).joinToString(";") {
      lat += r.nextGaussian() * 0.0003; lon += r.nextGaussian() * 0.0005; ele += r.nextInt(5) - 2
      String.format(java.util.Locale.ROOT, "%.6f,%.6f,%d", lat, lon, ele)
    }
  }

  private fun parts(s: SharedGpx) = GpxShare.pack(s, "abc123")!!.map { GpxShare.parsePart(it.doc)!! }

  @Test fun aGroupComesOutJustAsItWentIn() {
    assertEquals(group, GpxShare.unpack(parts(group)))
  }

  @Test fun oneLooseTrackComesOutAsATrack() {
    val one = SharedGpx("S-01", isGroup = false, listOf(group.tracks[0]))
    val back = GpxShare.unpack(parts(one))!!
    assertEquals(one, back)
    assertEquals(false, back.isGroup)
  }

  @Test fun pointsThatAreNotInTheUsualFormStayExactlyAsTheyAre() {
    for (odd in listOf("", "[]", "59.1,5.2,12.5", "59.798800,5.207600")) {
      assertNull(odd, GpxShare.deltas(odd))
      val s = SharedGpx("x", false, listOf(track("x", odd)))
      assertEquals(odd, GpxShare.unpack(parts(s))!!.tracks[0].points)
    }
  }

  @Test fun partsMayComeInAnyOrder() {
    val big = SharedGpx("Big", true, (1..6).map { track("T$it", road(30_000, it.toLong())) })
    val p = parts(big)
    assertTrue("needs several parts, got ${p.size}", p.size in 2..GpxShare.MAX_PARTS)
    assertEquals(big, GpxShare.unpack(p.reversed()))
  }

  @Test fun aMissingPartGivesNothing() {
    val big = SharedGpx("Big", true, (1..6).map { track("T$it", road(30_000, it.toLong())) })
    val p = parts(big)
    assertNull(GpxShare.unpack(p.drop(1)))
    assertNull(GpxShare.unpack(emptyList()))
  }

  @Test fun tooBigToShareIsSaid() {
    val huge = SharedGpx("Huge", true, (1..60).map { track("T$it", road(30_000, it.toLong())) })
    assertNull(GpxShare.pack(huge, "abc123"))
  }

  @Test fun everyPartSaysWhatIsOffered() {
    val p = parts(group).single()
    assertEquals("TET Sør", p.name)
    assertEquals(true, p.isGroup)
    assertEquals(2, p.tracks)
    assertEquals(25, p.km)
    assertTrue(GpxShare.isPart(GpxShare.pack(group, "abc123")!![0].tripId))
  }

  @Test fun anOlderAppDoesNotTakeAPartForATrip() {
    assertNull(TripDoc.fromJson(GpxShare.pack(group, "abc123")!![0].doc))
  }

  @Test fun anIdOrPlaceThatCouldNotBeOursIsRefused() {
    val good = GpxShare.pack(group, "abc123")!![0].doc
    assertNotNull(GpxShare.parsePart(good))
    assertNull(GpxShare.parsePart(good.replace("abc123", "../../evil")))
    assertNull(GpxShare.parsePart(good.replace("\"n\":1", "\"n\":99")))
    assertNull(GpxShare.parsePart(good.replace("\"i\":0", "\"i\":5")))
    assertNull(GpxShare.parsePart("{\"name\":\"a trip\"}"))
    assertNull(GpxShare.parsePart("not json"))
  }
}
