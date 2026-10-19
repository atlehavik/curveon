package com.curveon.phone

import com.curveon.groups.Transport
import com.curveon.groups.TransportFactory
import com.curveon.groups.TransportListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The group library's WebSocket on OkHttp. */
class OkHttpTransportFactory(
    // A ping now and then notices a connection that died without telling (coverage lost in a tunnel);
    // each costs a few bytes and wakes the radio, so not often.
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(45, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).build()
) : TransportFactory {

    override fun open(url: String, listener: TransportListener): Transport {
        val closed = AtomicBoolean(false)
        fun closed(code: Int) { if (closed.compareAndSet(false, true)) listener.onClosed(code) }
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                closed(code)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(TransportListener.FAILED)
        })
        return object : Transport {
            override fun send(text: String) = ws.send(text)
            override fun close(code: Int) { ws.close(code, null) }
        }
    }
}
