package com.curveon.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class QrTest {
  private fun luma(m: QrCodec.Matrix) = ByteArray(m.size * m.size) { i -> if (m[i % m.size, i / m.size]) 0 else 255.toByte() }

  /** The picture turned a quarter, as a camera held the other way sees it. */
  private fun turned(pix: ByteArray, n: Int) = ByteArray(n * n) { i -> pix[(n - 1 - i % n) * n + i / n] }

  private val deviceLink = GroupState.create("relay.example.org", "Lofoten").deviceLink(1_800_000_000L).toUri("curveon")
  private val invite = GroupState.create("relay.example.org", "Lofoten").invite(1_800_000_000L).toUri()

  @Test fun aLinkSurvivesBeingDrawnAndRead() {
    for (text in listOf(invite, deviceLink)) {
      val m = QrCodec.encode(text)!!
      assertEquals(text, QrCodec.decode(luma(m), m.size, m.size))
    }
  }

  @Test fun theReadLinkIsAWorkingInvite() {
    val g = GroupState.create("relay.example.org", "Lofoten")
    val m = QrCodec.encode(g.deviceLink(1_800_000_000L).toUri("curveon"))!!
    val joined = GroupState.join(Invite.parse(QrCodec.decode(luma(m), m.size, m.size)!!)!!)
    org.junit.Assert.assertArrayEquals(g.riderId, joined.riderId)
  }

  @Test fun aSmallPictureAndATurnedOneAreStillRead() {
    val small = QrCodec.encode(deviceLink, 360)!!
    assertEquals(deviceLink, QrCodec.decode(luma(small), small.size, small.size))
    val pix = turned(luma(small), small.size)
    assertEquals(deviceLink, QrCodec.decode(pix, small.size, small.size))
  }

  @Test fun aPictureWithoutACodeGivesNothing() {
    assertNull(QrCodec.decode(ByteArray(400 * 400) { 255.toByte() }, 400, 400))
    val rnd = Random(1)
    assertNull(QrCodec.decode(ByteArray(400 * 400) { rnd.nextInt(256).toByte() }, 400, 400))
  }

  @Test fun theMatrixIsDrawnWithAQuietBorder() {
    val m = QrCodec.encode(invite, 400)!!
    assertNotNull(m)
    // the outermost rows and columns are light
    for (i in 0 until m.size) assertEquals(false, m[i, 0])
  }
}
