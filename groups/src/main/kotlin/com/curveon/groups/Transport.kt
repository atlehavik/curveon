package com.curveon.groups

/** A WebSocket, however the platform provides one (OkHttp on Android, `java.net.http` in tests). */
interface Transport {
  /** False if the message could not be queued (closed or congested). */
  fun send(text: String): Boolean
  fun close(code: Int = 1000)
}

interface TransportListener {
  fun onOpen()
  fun onMessage(text: String)
  /** [code] is the WebSocket close code, or [FAILED] when the connection could not be made or broke. */
  fun onClosed(code: Int)

  companion object { const val FAILED = -1 }
}

fun interface TransportFactory {
  fun open(url: String, listener: TransportListener): Transport
}

/** Close codes the relay uses (docs/groups-protocol.md). */
object RelayClose {
  const val REKEYED = 4001
  const val CATCHUP_DONE = 4002
}
