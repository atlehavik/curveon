package com.curveon.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {
  private var now = 1_000_000L
  private fun fix(speed: Float) = Fix(59.9, 10.7, speed, 90f, 3f)

  @Test fun movingRiderIsSentEveryFiveSeconds() {
    val s = PositionSharer({ now })
    assertNotNull(s.offer(fix(20f)))
    now += 1_000
    assertNull(s.offer(fix(20f)))
    now += 3_999
    assertNull(s.offer(fix(20f)))
    now += 1
    assertNotNull(s.offer(fix(20f)))
  }

  @Test fun dataSaverSlowsItDown() {
    val s = PositionSharer({ now }).also { it.dataSaver = true }
    assertNotNull(s.offer(fix(20f)))
    now += 10_000
    assertNull(s.offer(fix(20f)))
    now += 5_000
    assertNotNull(s.offer(fix(20f)))
  }

  @Test fun aRedLightIsNotStoppedButAMinuteIs() {
    val s = PositionSharer({ now })
    s.offer(fix(20f))
    now += 5_000
    val light = s.offer(fix(0f))!!
    assertFalse("a few seconds at a light", light.stopped)
    now += 30_000
    s.offer(fix(0f))
    now += 30_000
    // 60 s below walking speed: the state flips at once, and everyone is told
    val stopped = s.offer(fix(0f))!!
    assertTrue(stopped.stopped)
    assertEquals(now - 60_000, stopped.stoppedSince)
  }

  @Test fun whileStoppedItIsSentLessOftenAndMovingOnSpeedsItUp() {
    val s = PositionSharer({ now })
    s.offer(fix(0f))
    now += 60_000
    assertTrue(s.offer(fix(0f))!!.stopped)
    now += 10_000
    assertNull("30 s apart while stopped", s.offer(fix(0f)))
    now += 20_000
    assertNotNull(s.offer(fix(0f)))
    now += 1_000
    val moving = s.offer(fix(15f))
    assertNotNull("moving again is told at once", moving)
    assertFalse(moving!!.stopped)
    assertNull(moving.stoppedSince)
  }

  @Test fun resetSendsTheNextOneAtOnce() {
    val s = PositionSharer({ now })
    s.offer(fix(20f))
    now += 100
    assertNull(s.offer(fix(20f)))
    s.reset()
    assertNotNull(s.offer(fix(20f)))
  }

  @Test fun batteryIsPassedAlong() {
    assertEquals(55, PositionSharer({ now }).offer(fix(10f), battery = 55)!!.battery)
  }

  @Test fun sosTrackerCountsWhoSawItAndWhoIsComing() {
    val t = SosTracker("me")
    val first = t.received("ola", Payload.Sos(68.1, 13.6), 10)
    assertTrue(first.active)
    assertFalse(first.mine)
    assertTrue(first.acks.isEmpty())

    assertEquals(mapOf("kari" to false), t.answered("kari", "ola", false)!!.acks)
    assertEquals(mapOf("kari" to false, "per" to true), t.answered("per", "ola", true)!!.acks)
    // "on the way" is not turned back into "seen"
    assertEquals(mapOf("kari" to false, "per" to true), t.answered("per", "ola", false)!!.acks)
    assertEquals(mapOf("kari" to true, "per" to true), t.answered("kari", "ola", true)!!.acks)

    assertNull(t.answered("kari", "unknown", true))
    assertEquals(1, t.active().size)

    val ended = t.cancelled("ola")!!
    assertFalse(ended.active)
    assertTrue(t.active().isEmpty())
    assertNull(t.cancelled("ola"))
  }

  @Test fun theRidersOwnSosIsMarkedAsMine() {
    val t = SosTracker("me")
    assertTrue(t.started(Payload.Sos(1.0, 2.0), 5).mine)
    // the same SOS sent again keeps its answers
    t.answered("ola", "me", false)
    assertEquals(mapOf("ola" to false), t.received("me", Payload.Sos(1.0, 2.0), 5).acks)
    // a new one (new time) starts clean
    assertTrue(t.received("me", Payload.Sos(1.0, 2.0), 99).acks.isEmpty())
  }
}
