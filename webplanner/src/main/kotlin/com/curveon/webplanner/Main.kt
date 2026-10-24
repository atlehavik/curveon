package com.curveon.webplanner

import com.curveon.routing.BRouterEngine
import com.curveon.routing.ProfileInstaller
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Serves `/api/route` (and, for trying it on a PC, the page itself from WEB_DIR). On the VPS Caddy is in front: it
 * serves the page and the map from disk and hands only `/plan/api/…` to this. Nothing about riders is logged or kept.
 *
 * Environment: SEGMENTS (BRouter .rd5 files), PORT (8788), HOST (127.0.0.1), TRUST_PROXY=1 behind Caddy, WEB_DIR,
 * MAP_DIR (pmtiles, fonts and sprites; local trying only).
 */
fun main() {
  val env = System.getenv()
  val segments = File(env["SEGMENTS"] ?: error("SEGMENTS is not set"))
  require(segments.isDirectory) { "no segments in $segments" }
  val profiles = ProfileInstaller.install(Files.createTempDirectory("curveon-profiles").toFile())
  // 128 MB of decoded map data per search: two at once stay well inside the service's memory limit.
  val planner = Planner(BRouterEngine(segments, profiles, timeoutMillis = 45_000, memoryClassMb = 128))
  val limiter = RateLimiter(perWindow = 120, windowMillis = 10 * 60_000)
  val trustProxy = env["TRUST_PROXY"] == "1"
  val web = env["WEB_DIR"]?.let(::File)
  val map = env["MAP_DIR"]?.let(::File)

  val server = HttpServer.create(InetSocketAddress(env["HOST"] ?: "127.0.0.1", (env["PORT"] ?: "8788").toInt()), 64)
  server.executor = Executors.newFixedThreadPool(16)
  server.createContext("/") { ex ->
    try {
      val path = ex.requestURI.path.removePrefix("/plan")
      when {
        path == "/api/health" -> send(ex, 200, "text/plain", "ok\n".toByteArray())
        path == "/api/route" && ex.requestMethod == "POST" -> {
          val ip = if (trustProxy) ex.requestHeaders.getFirst("X-Forwarded-For")?.split(',')?.last()?.trim() else null
          if (!limiter.allow(ip ?: ex.remoteAddress.address.hostAddress)) throw PlanRefused(429, "slow_down")
          val body = ex.requestBody.use { it.readNBytes(64 * 1024) }
          val json = try { JSONObject(String(body)) } catch (_: Exception) { throw PlanRefused(400, "bad_request") }
          sendJson(ex, 200, planner.plan(json))
        }
        map != null && path.startsWith("/map/") -> sendFile(ex, File(map, path.removePrefix("/map/")), map)
        web != null && ex.requestMethod == "GET" -> sendFile(ex, File(web, path.removePrefix("/").ifEmpty { "index.html" }), web)
        else -> send(ex, 404, "text/plain", ByteArray(0))
      }
    } catch (e: PlanRefused) {
      sendJson(ex, e.status, JSONObject().put("error", e.code))
    } catch (e: Exception) {
      System.err.println("planner: ${e.javaClass.simpleName}: ${e.message}")
      runCatching { sendJson(ex, 500, JSONObject().put("error", "failed")) }
    } finally {
      ex.close()
    }
  }
  server.start()
  println("web planner on ${server.address}")
}

/** At most [perWindow] routes per address in [windowMillis]. Addresses are kept in memory only, never logged. */
class RateLimiter(private val perWindow: Int, private val windowMillis: Long, private val clock: () -> Long = System::currentTimeMillis) {
  private val seen = ConcurrentHashMap<String, ArrayDeque<Long>>()

  fun allow(key: String): Boolean {
    val now = clock()
    if (seen.size > 10_000) seen.entries.removeIf { e -> synchronized(e.value) { e.value.lastOrNull()?.let { now - it > windowMillis } ?: true } }
    val times = seen.getOrPut(key) { ArrayDeque() }
    synchronized(times) {
      while (times.isNotEmpty() && now - times.first() > windowMillis) times.removeFirst()
      if (times.size >= perWindow) return false
      times.addLast(now)
      return true
    }
  }
}

private fun sendJson(ex: HttpExchange, status: Int, json: JSONObject) =
  send(ex, status, "application/json", json.toString().toByteArray())

private fun send(ex: HttpExchange, status: Int, type: String, body: ByteArray) {
  ex.responseHeaders.set("Content-Type", type)
  ex.responseHeaders.set("Cache-Control", "no-store")
  ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
  if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
}

/** A file under [root] (local trying only; Caddy does this on the VPS), with Range for the pmtiles map. */
private fun sendFile(ex: HttpExchange, file: File, root: File) {
  val f = file.canonicalFile
  if (!f.path.startsWith(root.canonicalPath) || !f.isFile) return send(ex, 404, "text/plain", ByteArray(0))
  val type = when (f.extension) {
    "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
    "png" -> "image/png"; "svg" -> "image/svg+xml"; "pbf" -> "application/x-protobuf"; else -> "application/octet-stream"
  }
  ex.responseHeaders.set("Content-Type", type)
  ex.responseHeaders.set("Accept-Ranges", "bytes")
  val range = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
  java.io.RandomAccessFile(f, "r").use { raf ->
    val len = raf.length()
    val from = range?.groupValues?.get(1)?.toLong() ?: 0L
    val to = range?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(len - 1) ?: (len - 1)
    if (range != null) ex.responseHeaders.set("Content-Range", "bytes $from-$to/$len")
    val n = to - from + 1
    ex.sendResponseHeaders(if (range != null) 206 else 200, n)
    ex.responseBody.use { out ->
      raf.seek(from)
      val buf = ByteArray(64 * 1024)
      var left = n
      while (left > 0) {
        val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (r < 0) break
        out.write(buf, 0, r); left -= r
      }
    }
  }
}
