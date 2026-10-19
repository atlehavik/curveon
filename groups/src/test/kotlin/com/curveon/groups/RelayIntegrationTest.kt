package com.curveon.groups

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** WebSocket for tests, on the JDK's own client. */
private class JdkTransportFactory : TransportFactory {
  override fun open(url: String, listener: TransportListener): Transport {
    val done = AtomicBoolean(false)
    fun closed(code: Int) { if (done.compareAndSet(false, true)) listener.onClosed(code) }
    val text = StringBuilder()
    val socket = java.util.concurrent.atomic.AtomicReference<WebSocket?>()
    val future = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI(url), object : WebSocket.Listener {
      override fun onOpen(ws: WebSocket) { socket.set(ws); ws.request(1); listener.onOpen() }
      override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        text.append(data)
        if (last) { listener.onMessage(text.toString()); text.setLength(0) }
        ws.request(1)
        return null
      }
      override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { closed(statusCode); return null }
      override fun onError(ws: WebSocket, error: Throwable) { closed(TransportListener.FAILED) }
    })
    future.exceptionally { closed(TransportListener.FAILED); null }
    return object : Transport {
      @Synchronized override fun send(text: String) = try {
        socket.get()?.sendText(text, true)?.join() != null
      } catch (_: Exception) { false }
      override fun close(code: Int) { socket.get()?.sendClose(code, "") }
    }
  }
}

/** The real relay (~/develop/curveon-relay, Node) with real clients. Skipped where it is not installed. */
class RelayIntegrationTest {
  private val relayDir = File(System.getProperty("user.home"), "develop/curveon-relay")
  private val port = 18790
  private var relay: Process? = null
  private val riders = mutableListOf<Rider>()

  private inner class Rider(val name: String, val state: GroupState) {
    val events = CopyOnWriteArrayList<GroupEvent>()
    private val consumed = HashSet<GroupEvent>()
    val client = GroupClient(state, JdkTransportFactory(), { events.add(it) }, scheme = "ws", retryDelaysMs = listOf(200)).also {
      it.setProfile(Payload.Hello(name, 0xFF000000.toInt() or name.hashCode(), state.agreement.publicKey))
      riders.add(this)
    }
    val id: String get() = B64.encode(state.riderId)

    /** Waits for an event [pick] accepts that has not been used by an earlier wait. */
    fun <T> await(timeoutMs: Long = 6000, pick: (GroupEvent) -> T?): T {
      val end = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < end) {
        for (e in events.toList()) {
          if (e in consumed) continue
          val r = pick(e)
          if (r != null) { consumed.add(e); return r }
        }
        Thread.sleep(25)
      }
      throw AssertionError("$name: no matching event; got $events")
    }

    fun none(waitMs: Long = 600, pick: (GroupEvent) -> Boolean): Boolean {
      Thread.sleep(waitMs)
      return events.none { it !in consumed && pick(it) }
    }
  }

  @Before fun startRelay() {
    assumeTrue("relay not installed", File(relayDir, "src/server.ts").exists() && File(relayDir, "node_modules").exists())
    val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).exists() }
    assumeTrue("node not found", node != null)
    val db = Files.createTempFile("relay", ".db").toFile().also { it.delete(); it.deleteOnExit() }
    val pb = ProcessBuilder(node, "src/server.ts").directory(relayDir).redirectErrorStream(true)
    pb.environment().putAll(mapOf("PORT" to port.toString(), "DB_PATH" to db.path, "MAX_FRAMES_PER_10S" to "1000"))
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    relay = pb.start()
    val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    val end = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < end) {
      try {
        if (http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200) return
      } catch (_: Exception) { Thread.sleep(100) }
    }
    throw AssertionError("relay did not start")
  }

  @After fun stopRelay() {
    riders.forEach { it.client.shutdown() }
    relay?.destroy()
  }

  private fun group(): Pair<Rider, Invite> {
    val admin = GroupState.create("127.0.0.1:$port", "Test")
    return Rider("Ola", admin) to Invite.parse(admin.invite().toUri())!!
  }

  private fun joined(invite: Invite, name: String) = Rider(name, GroupState.join(invite))

  @Test fun ridersSeeEachOtherAndShareSos() {
    val (a, invite) = group()
    a.client.start()
    a.await { if (it is GroupEvent.Connected) it else null }
    val b = joined(invite, "Kari").also { it.client.start() }
    val c = joined(invite, "Per").also { it.client.start() }

    // hello: the relay replays the stored ones to late joiners, and forwards new ones
    b.await { (it as? GroupEvent.MemberUpdated)?.takeIf { e -> e.member.nickname == "Ola" } }
    a.await { (it as? GroupEvent.MemberUpdated)?.takeIf { e -> e.member.nickname == "Kari" } }
    a.await { (it as? GroupEvent.MemberUpdated)?.takeIf { e -> e.member.nickname == "Per" } }
    assertEquals(setOf("Kari", "Per"), a.client.members().map { it.nickname }.toSet())

    // positions reach the others, not the sender
    a.client.sendPosition(Payload.Pos(59.91, 10.75, 20f, 90f, 3f, battery = 70))
    val seen = b.await { (it as? GroupEvent.Position)?.takeIf { e -> e.riderId == a.id } }
    assertEquals(59.91, seen.pos.lat, 1e-9)
    assertEquals(70, seen.pos.battery)
    c.await { (it as? GroupEvent.Position)?.takeIf { e -> e.riderId == a.id } }
    assertTrue(a.none { it is GroupEvent.Position })

    // SOS: others get it, answer it, and the sender can cancel
    assertTrue(b.client.sendSos(Payload.Sos(68.1, 13.6)))
    a.await { (it as? GroupEvent.SosReceived)?.takeIf { e -> e.riderId == b.id } }
    // every device says at once that it got it, so the sender sees the delivery ...
    b.await { (it as? GroupEvent.SosAnswered)?.takeIf { e -> e.riderId == a.id && !e.coming } }
    b.await { (it as? GroupEvent.SosAnswered)?.takeIf { e -> e.riderId == c.id && !e.coming } }
    // ... and "I'm coming" is a separate answer
    a.client.answerSos(b.id, coming = true)
    val answer = b.await { (it as? GroupEvent.SosAnswered)?.takeIf { e -> e.coming } }
    assertEquals(a.id, answer.riderId)
    b.client.cancelSos()
    a.await { (it as? GroupEvent.SosCancelled)?.takeIf { e -> e.riderId == b.id } }

    // a route
    a.client.sendRoute(Payload.Route("r1", "Lofoten", "gpx", ByteArray(500) { it.toByte() }))
    assertEquals("Lofoten", c.await { (it as? GroupEvent.RouteReceived)?.route }.name)

    // leaving
    c.client.leave()
    a.await { (it as? GroupEvent.MemberLeft)?.takeIf { e -> e.riderId == c.id } }
  }

  @Test fun aRiderWhoIsRemovedIsLockedOutAndTheOthersCarryOn() {
    val (a, invite) = group()
    a.client.start()
    val b = joined(invite, "Kari").also { it.client.start() }
    val c = joined(invite, "Per").also { it.client.start() }
    val d = joined(invite, "Siri").also { it.client.start() } // will be offline during the change
    for (n in listOf("Kari", "Per", "Siri")) a.await { (it as? GroupEvent.MemberUpdated)?.takeIf { e -> e.member.nickname == n } }
    d.client.stop()
    val oldProof = c.state.keys.accessProof()

    assertTrue(a.client.removeRider(c.id))
    b.await { it as? GroupEvent.KeyChanged }
    c.await { it as? GroupEvent.Removed }
    assertEquals(1, a.state.epoch)
    assertEquals(1, b.state.epoch)

    // Kari reconnects with the new key and the admin's position reaches her, but not the removed rider
    b.await { if (it is GroupEvent.Connected && b.events.indexOf(it) >= 0 && b.state.epoch == 1) it else null }
    Thread.sleep(800)
    a.client.sendPosition(Payload.Pos(60.0, 11.0, 10f, 0f, 2f))
    b.await { (it as? GroupEvent.Position)?.takeIf { e -> e.riderId == a.id && e.pos.lat == 60.0 } }
    assertTrue(c.none { it is GroupEvent.Position })
    assertFalse(c.client.connected)

    // Siri was offline: she connects with the old key, is handed the rekey, and then reconnects
    d.client.start()
    d.await { it as? GroupEvent.KeyChanged }
    assertEquals(1, d.state.epoch)
    d.await { if (it is GroupEvent.Connected) it else null }
    Thread.sleep(500)
    a.client.sendPosition(Payload.Pos(61.0, 12.0, 10f, 0f, 2f))
    d.await { (it as? GroupEvent.Position)?.takeIf { e -> e.pos.lat == 61.0 } }
    assertTrue("the old proof must not work any more", oldProof != a.state.keys.accessProof())

    // an invite made after the change carries the new key and epoch, so a newcomer can join and be heard
    val late = joined(Invite.parse(a.state.invite().toUri())!!, "Nina").also { it.client.start() }
    a.await { (it as? GroupEvent.MemberUpdated)?.takeIf { e -> e.member.nickname == "Nina" } }
    Thread.sleep(300)
    a.client.sendPosition(Payload.Pos(62.0, 13.0, 10f, 0f, 2f))
    late.await { (it as? GroupEvent.Position)?.takeIf { e -> e.pos.lat == 62.0 } }
  }

  @Test fun aSecondDeviceOfTheSameRiderIsNotANewRider() {
    val (a, invite) = group()
    a.client.start()
    val b = joined(invite, "Kari").also { it.client.start() }
    a.await { (it as? GroupEvent.MemberUpdated)?.takeIf { e -> e.member.nickname == "Kari" } }

    // Ola's phone joins with the link from Ola's tablet
    val phone = Rider("Ola", GroupState.join(Invite.parse(a.state.deviceLink(System.currentTimeMillis() / 1000 + 600).toUri("curveon"))!!))
    assertEquals(a.id, phone.id)
    phone.client.start()
    phone.await { (it as? GroupEvent.MemberUpdated)?.takeIf { e -> e.member.nickname == "Kari" } }
    Thread.sleep(500)

    // the others see one Ola, not two
    assertEquals(listOf(a.id), b.client.members().filter { it.nickname == "Ola" }.map { it.id })

    // what Kari sends reaches both of Ola's devices; what one of Ola's devices sends reaches Kari, not the other device as a rider
    b.client.sendPosition(Payload.Pos(59.0, 10.0, 10f, 0f, 2f))
    a.await { (it as? GroupEvent.Position)?.takeIf { e -> e.pos.lat == 59.0 } }
    phone.await { (it as? GroupEvent.Position)?.takeIf { e -> e.pos.lat == 59.0 } }
    phone.client.sendPosition(Payload.Pos(59.5, 10.5, 10f, 0f, 2f))
    val seen = b.await { (it as? GroupEvent.Position)?.takeIf { e -> e.pos.lat == 59.5 } }
    assertEquals(a.id, seen.riderId)
    assertTrue(a.none { it is GroupEvent.Position && it.pos.lat == 59.5 })
  }

  @Test fun anSosMadeWithoutCoverageGoesOutWhenTheConnectionIsBack() {
    val (a, invite) = group()
    a.client.start()
    val b = joined(invite, "Kari").also { it.client.start() }
    a.await { (it as? GroupEvent.MemberUpdated)?.takeIf { e -> e.member.nickname == "Kari" } }
    b.client.stop()
    assertFalse(b.client.sendSos(Payload.Sos(1.0, 2.0)))
    b.client.start()
    a.await { (it as? GroupEvent.SosReceived)?.takeIf { e -> e.riderId == b.id } }
  }
}
