package com.curveon.webplanner

import com.curveon.routing.BRouterEngine
import com.curveon.routing.ProfileInstaller
import com.curveon.routing.SurfaceReader
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serves `/api/route`, `/api/adventure`, `/api/roundtrip` and `/api/pois` (and, for trying it on a PC, the page itself from WEB_DIR). On the VPS Caddy is in front: it
 * serves the page and the map from disk and hands only `/plan/api/…` to this. Nothing about riders is logged or kept.
 *
 * Environment: SEGMENTS (BRouter .rd5 files), PORT (8788), HOST (127.0.0.1), TRUST_PROXY=1 behind Caddy, WEB_DIR,
 * MAP_DIR (pmtiles, fonts and sprites; local trying only), POIS (the places' TSV files).
 */
fun main() {
  val env = System.getenv()
  val segments = File(env["SEGMENTS"] ?: error("SEGMENTS is not set"))
  require(segments.isDirectory) { "no segments in $segments" }
  val profiles = ProfileInstaller.install(Files.createTempDirectory("curveon-profiles").toFile())
  // Places for the map's layers and the fuel along routes (tools/webplanner_pois.py); without them, neither.
  val pois = env["POIS"]?.let(::File)?.takeIf { it.isDirectory }?.let { dir ->
    Pois.load(dir).also { println("places: ${it.size} from $dir") }
  }
  // 128 MB of decoded map data per search: two at once stay well inside the service's memory limit.
  // The roads, read from the segments, put an adventure's anchors on them (as in the app).
  val planner = Planner(BRouterEngine(segments, profiles, timeoutMillis = 45_000, memoryClassMb = 128), SurfaceReader(segments, File(profiles, "lookups.dat")), pois)
  val limiter = RateLimiter(perWindow = 120, windowMillis = 10 * 60_000)
  // An adventure or a roundtrip is dozens of routes: fewer of them.
  val adventures = RateLimiter(perWindow = 12, windowMillis = 10 * 60_000)
  // Places are a look in memory: many, as the rider moves the map about.
  val places = RateLimiter(perWindow = 1200, windowMillis = 10 * 60_000)
  // Every hour, how the planner kept up (counts only) goes to the journal.
  Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "stats").apply { isDaemon = true } }
    .scheduleAtFixedRate({ println("planner hour: ${planner.stats.roll()}") }, 1, 1, TimeUnit.HOURS)
  val trustProxy = env["TRUST_PROXY"] == "1"
  val web = env["WEB_DIR"]?.let(::File)
  val map = env["MAP_DIR"]?.let(::File)

  val server = HttpServer.create(InetSocketAddress(env["HOST"] ?: "127.0.0.1", (env["PORT"] ?: "8788").toInt()), 64)
  server.executor = Executors.newFixedThreadPool(16)
  server.createContext("/") { ex ->
    try {
      val path = ex.requestURI.path.removePrefix("/plan")
      val forwarded = ex.requestHeaders.getFirst("X-Forwarded-For")
      val ip = (if (trustProxy) forwarded?.split(',')?.last()?.trim() else null) ?: ex.remoteAddress.address.hostAddress
      fun allow(l: RateLimiter) { if (!l.allow(ip)) { planner.stats.slowDown.incrementAndGet(); throw PlanRefused(429, "slow_down") } }
      when {
        path == "/api/health" -> send(ex, 200, "text/plain", "ok\n".toByteArray())
        // On the VPS itself only (Caddy always says who it forwards for): curl 127.0.0.1:8788/plan/api/stats
        path == "/api/stats" && forwarded == null && ex.remoteAddress.address.isLoopbackAddress -> sendJson(ex, 200, planner.stats.json())
        path in setOf("/api/route", "/api/adventure", "/api/roundtrip") && ex.requestMethod == "POST" -> {
          allow(if (path == "/api/route") limiter else adventures)
          val body = ex.requestBody.use { it.readNBytes(256 * 1024) }
          val json = try { JSONObject(String(body)) } catch (_: Exception) { throw PlanRefused(400, "bad_request") }
          sendJson(ex, 200, when (path) { "/api/adventure" -> planner.adventure(json); "/api/roundtrip" -> planner.roundtrip(json); else -> planner.plan(json) })
        }
        path == "/api/pois" && ex.requestMethod == "GET" -> {
          allow(places)
          planner.stats.poiLookups.incrementAndGet()
          val q = ex.requestURI.rawQuery.orEmpty().split('&').associate { kv -> kv.substringBefore('=') to java.net.URLDecoder.decode(kv.substringAfter('=', ""), Charsets.UTF_8) }
          sendJson(ex, 200, planner.pois(q["b"], q["k"]))
        }
        map != null && path.startsWith("/map/") -> sendFile(ex, File(map, path.removePrefix("/map/")), map)
        web != null && ex.requestMethod == "GET" -> sendFile(ex, File(web, path.removePrefix("/").ifEmpty { "index.html" }), web)
        else -> send(ex, 404, "text/plain", ByteArray(0))
      }
    } catch (e: PlanRefused) {
      sendJson(ex, e.status, JSONObject().put("error", e.code).also { j -> e.minutes?.let { j.put("minutes", it) } })
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
  // Normalised, not canonical: no way out of [root] with "..", but the map may be a link to the data folder.
  val f = file.toPath().toAbsolutePath().normalize().toFile()
  if (!f.toPath().startsWith(root.toPath().toAbsolutePath().normalize()) || !f.isFile) return send(ex, 404, "text/plain", ByteArray(0))
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
