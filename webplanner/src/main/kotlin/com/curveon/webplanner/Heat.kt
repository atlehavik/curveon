package com.curveon.webplanner

import com.curveon.routing.GravelHeat
import com.curveon.routing.SurfaceReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

/**
 * The gravel heat map for the page, the app's own ([GravelHeat]): how much gravel each 1/16° square has, counted out of
 * the routing data one segment file (5° × 5°) at a time. Counting a file takes seconds, so it is done on one thread of
 * its own, never while a rider waits: [warmAll] counts every file after a start, and each is kept in [cacheDir] until
 * its segment file changes, so after the first start they are all there at once. A file asked for before it is counted
 * goes first in the line.
 */
class Heat(private val segments: File, cacheDir: File, lookups: File) {
  private val heat = GravelHeat(segments, cacheDir, SurfaceReader(segments, lookups))
  private val line = LinkedBlockingDeque<String>()
  /** Each file's answer, made once: some tens of kB of JSON. */
  private val answers = ConcurrentHashMap<String, ByteArray>()

  init {
    Thread({
      var counted = 0
      var t0 = 0L
      while (true) {
        val tile = line.take()
        if (!heat.isReady(tile)) {
          if (counted++ == 0) t0 = System.nanoTime()
          runCatching { heat.cells(tile) }.onFailure { System.err.println("heat: $tile not counted: ${it.message}") }
        }
        if (line.isEmpty() && counted > 0) {
          println("heat: counted $counted segment files in %.0f s".format((System.nanoTime() - t0) / 1e9))
          counted = 0
        }
      }
    }, "heat").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
  }

  /** Every segment file not counted yet, in the line. */
  fun warmAll() {
    segments.listFiles { f -> f.name.endsWith(".rd5") }.orEmpty().map { it.nameWithoutExtension }.sorted()
      .filterNot { heat.isReady(it) }.forEach(line::addLast)
  }

  /**
   * One segment file's heat as `{lon, lat, n, c: [square, weight × 10, …]}` (square: `y × 5n + x` from its south-west
   * corner, n squares a degree), `{c: []}` where there is no routing data, or null while it is still being counted.
   */
  fun tile(name: String): ByteArray? {
    if (!TILE.matches(name)) throw PlanRefused(400, "bad_request")
    answers[name]?.let { return it }
    val (lon0, lat0) = GravelHeat.origin(name) ?: throw PlanRefused(400, "bad_request")
    if (!File(segments, "$name.rd5").exists()) return EMPTY
    if (!heat.isReady(name)) {
      if (line.peekFirst() != name) { line.remove(name); line.addFirst(name) }
      return null
    }
    val n = GravelHeat.CELLS_PER_DEGREE
    val c = JSONArray()
    for (cell in heat.cells(name)) {
      val x = ((cell.lon - lon0) * n).toInt().coerceIn(0, 5 * n - 1)
      val y = ((cell.lat - lat0) * n).toInt().coerceIn(0, 5 * n - 1)
      c.put(y * 5 * n + x).put(Math.round(cell.weight * 10))
    }
    return JSONObject().put("lon", lon0).put("lat", lat0).put("n", n).put("c", c).toString().toByteArray().also { answers[name] = it }
  }

  private companion object {
    val TILE = Regex("[EW]\\d{1,3}_[NS]\\d{1,2}")
    val EMPTY = """{"c":[]}""".toByteArray()
  }
}
