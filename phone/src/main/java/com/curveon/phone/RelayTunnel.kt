package com.curveon.phone

import com.curveon.groups.Transport
import com.curveon.groups.TransportFactory
import com.curveon.groups.TransportListener
import com.curveon.protocol.Message
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The relay's WebSocket, carried over the Bluetooth link to the phone: for a tablet with no internet of its own. It looks
 * like any other [TransportFactory] to the group library, so a group does not know which way it is connected.
 *
 * Messages go out one at a time and in order ([sink] writes to the link; false if there is none), and a dropped link closes
 * every socket as failed, so the library reconnects by itself (over whichever way is there then).
 */
class RelayTunnelClient(private val sink: (Message) -> Boolean) : TransportFactory {
    private val next = AtomicInteger(1)
    private val sockets = ConcurrentHashMap<Int, TransportListener>()
    private val out = Executors.newSingleThreadExecutor { r -> Thread(r, "curveon-relay-tunnel").apply { isDaemon = true } }

    override fun open(url: String, listener: TransportListener): Transport {
        val id = next.getAndIncrement()
        sockets[id] = listener
        out.execute { if (!sink(Message.RelayOpen(id, url))) closed(id, TransportListener.FAILED) }
        return object : Transport {
            override fun send(text: String): Boolean {
                if (!sockets.containsKey(id)) return false
                out.execute { if (!sink(Message.RelaySend(id, text))) closed(id, TransportListener.FAILED) }
                return true
            }

            override fun close(code: Int) {
                if (sockets.remove(id) == null) return
                out.execute { sink(Message.RelayClose(id, code)) }
            }
        }
    }

    /** What the phone says about the sockets it opened for us. */
    fun onMessage(m: Message) {
        when (m) {
            is Message.RelayOpened -> sockets[m.id]?.onOpen()
            is Message.RelayText -> sockets[m.id]?.onMessage(m.text)
            is Message.RelayClosed -> closed(m.id, m.code)
            else -> Unit
        }
    }

    /** The link to the phone went away: nothing sent through it will arrive. */
    fun linkLost() = sockets.keys.toList().forEach { closed(it, TransportListener.FAILED) }

    private fun closed(id: Int, code: Int) {
        sockets.remove(id)?.onClosed(code)
    }
}

/**
 * The phone's end of [RelayTunnelClient]: opens the WebSockets the tablet asks for, on the phone's own internet, and passes
 * what happens on them back. It only opens ws/wss and only a few at a time (a rider has one group and a few friends).
 */
class RelayTunnelServer(private val transports: TransportFactory, private val sink: (Message) -> Unit) {
    private val sockets = ConcurrentHashMap<Int, Transport>()

    fun handle(m: Message) {
        when (m) {
            is Message.RelayOpen -> open(m)
            is Message.RelaySend -> {
                val t = sockets[m.id]
                if (t == null || !t.send(m.text)) sink(Message.RelayClosed(m.id, TransportListener.FAILED))
            }
            is Message.RelayClose -> sockets.remove(m.id)?.close(m.code)
            else -> Unit
        }
    }

    private fun open(m: Message.RelayOpen) {
        val allowed = (m.url.startsWith("wss://") || m.url.startsWith("ws://")) && sockets.size < MAX_SOCKETS
        if (!allowed) return sink(Message.RelayClosed(m.id, TransportListener.FAILED))
        val opened = AtomicReference<Transport?>()
        val failedEarly = AtomicBoolean(false)
        val t = transports.open(m.url, object : TransportListener {
            override fun onOpen() = sink(Message.RelayOpened(m.id))
            override fun onMessage(text: String) = sink(Message.RelayText(m.id, text))
            override fun onClosed(code: Int) {
                // Only tell the tablet if it is still waiting: a socket it closed itself needs no answer.
                val self = opened.get()
                if (self == null) { failedEarly.set(true); sink(Message.RelayClosed(m.id, code)) }
                else if (sockets.remove(m.id, self)) sink(Message.RelayClosed(m.id, code))
            }
        })
        opened.set(t)
        if (!failedEarly.get()) sockets[m.id] = t
    }

    /** The tablet went away: nobody is listening to these sockets any more. */
    fun closeAll() {
        sockets.values.toList().forEach { runCatching { it.close(1001) } }
        sockets.clear()
    }

    private companion object {
        const val MAX_SOCKETS = 8
    }
}
