package com.curveon.phone

import com.curveon.groups.Transport
import com.curveon.groups.TransportFactory
import com.curveon.groups.TransportListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The tunnel from a tablet (client) through "Bluetooth" (a direct hand-over) to a phone (server) and a fake relay. */
class RelayTunnelTest {
    /** A relay that echoes every text with "echo:" in front, and can be told to fail or close. */
    private class FakeRelay : TransportFactory {
        val opened = CopyOnWriteArrayList<String>()
        val received = CopyOnWriteArrayList<String>()
        val listeners = CopyOnWriteArrayList<TransportListener>()
        var failOnOpen = false
        var closedWith: Int? = null

        override fun open(url: String, listener: TransportListener): Transport {
            opened += url
            listeners += listener
            if (failOnOpen) listener.onClosed(TransportListener.FAILED) else listener.onOpen()
            return object : Transport {
                override fun send(text: String): Boolean { received += text; listener.onMessage("echo:$text"); return true }
                override fun close(code: Int) { closedWith = code }
            }
        }
    }

    private class Events : TransportListener {
        val log = CopyOnWriteArrayList<String>()
        override fun onOpen() { log += "open" }
        override fun onMessage(text: String) { log += "msg:$text" }
        override fun onClosed(code: Int) { log += "closed:$code" }
    }

    private class Rig(val relay: FakeRelay = FakeRelay(), var linkUp: Boolean = true) {
        lateinit var client: RelayTunnelClient
        val server = RelayTunnelServer(relay) { m -> client.onMessage(m) }
        init { client = RelayTunnelClient { m -> if (linkUp) { server.handle(m); true } else false } }
    }

    private fun eventually(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(10) }
        assertTrue("condition not reached", check())
    }

    @Test fun framesGoBothWaysInOrder() {
        val rig = Rig()
        val ev = Events()
        val t = rig.client.open("wss://relay.example/g/1", ev)
        eventually { ev.log == listOf("open") }
        (1..5).forEach { assertTrue(t.send("m$it")) }
        eventually { ev.log.size == 6 }
        assertEquals((1..5).map { "m$it" }, rig.relay.received.toList())
        assertEquals(listOf("open") + (1..5).map { "msg:echo:m$it" }, ev.log.toList())
    }

    @Test fun closingFromTheTabletReachesTheRelayAndIsNotAnsweredTwice() {
        val rig = Rig()
        val ev = Events()
        val t = rig.client.open("wss://relay.example/g/1", ev)
        eventually { ev.log == listOf("open") }
        t.close(1000)
        eventually { rig.relay.closedWith == 1000 }
        assertFalse(t.send("late"))
        assertEquals(listOf("open"), ev.log.toList())
    }

    @Test fun aRelayThatCannotBeReachedIsReportedAsFailed() {
        val rig = Rig(FakeRelay().apply { failOnOpen = true })
        val ev = Events()
        rig.client.open("wss://relay.example/g/1", ev)
        eventually { ev.log == listOf("closed:${TransportListener.FAILED}") }
    }

    @Test fun noLinkMeansFailedAtOnce() {
        val rig = Rig(linkUp = false)
        val ev = Events()
        rig.client.open("wss://relay.example/g/1", ev)
        eventually { ev.log == listOf("closed:${TransportListener.FAILED}") }
        assertTrue(rig.relay.opened.isEmpty())
    }

    @Test fun aLinkThatDropsFailsEverySocket() {
        val rig = Rig()
        val a = Events(); val b = Events()
        rig.client.open("wss://relay.example/g/1", a)
        rig.client.open("wss://relay.example/g/2", b)
        eventually { a.log == listOf("open") && b.log == listOf("open") }
        rig.client.linkLost()
        assertEquals(listOf("open", "closed:${TransportListener.FAILED}"), a.log.toList())
        assertEquals(listOf("open", "closed:${TransportListener.FAILED}"), b.log.toList())
    }

    @Test fun theRelayClosingIsPassedOn() {
        val rig = Rig()
        val ev = Events()
        rig.client.open("wss://relay.example/g/1", ev)
        eventually { ev.log == listOf("open") }
        rig.relay.listeners.single().onClosed(4001)
        eventually { ev.log == listOf("open", "closed:4001") }
    }

    @Test fun onlyWebSocketAddressesAreOpened() {
        val rig = Rig()
        val ev = Events()
        rig.client.open("https://evil.example/", ev)
        eventually { ev.log == listOf("closed:${TransportListener.FAILED}") }
        assertTrue(rig.relay.opened.isEmpty())
    }

    @Test fun aTabletThatLeavesClosesItsSockets() {
        val rig = Rig()
        val ev = Events()
        rig.client.open("wss://relay.example/g/1", ev)
        eventually { ev.log == listOf("open") }
        rig.server.closeAll()
        eventually { rig.relay.closedWith == 1001 }
    }
}
