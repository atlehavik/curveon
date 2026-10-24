package com.curveon.webplanner

import com.curveon.routing.AdventurePlanner
import com.curveon.routing.BRouterEngine
import com.curveon.routing.Curviness
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.NoAdventureException
import com.curveon.routing.RoadStyle
import com.curveon.routing.RoundtripPlanner
import com.curveon.routing.Route
import com.curveon.routing.RouteCorridor
import com.curveon.routing.RouteOptions
import com.curveon.routing.RoutingException
import com.curveon.routing.SurfacePreference
import com.curveon.routing.SurfaceReader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * A request the planner turns down, with the HTTP status and a code the page shows in the rider's language ([minutes]:
 * how long the quickest way takes, when an adventure was given too little time).
 */
class PlanRefused(val status: Int, val code: String, val minutes: Int? = null) : Exception(code)

/** An adventure as the page asks for it: start to destination through the fun roads of [area], in at most [minutes]. */
class AdventureAsk(val start: LatLon, val destination: LatLon, val area: List<LatLon>, val minutes: Int?, val options: RouteOptions, val variant: Int)

/** A roundtrip as the page asks for it: from [start] round the [area] drawn and back, at most [maxKm] long. */
class LoopAsk(val start: LatLon, val area: List<LatLon>, val maxKm: Int?, val options: RouteOptions, val variant: Int)

/**
 * Routes for the web page, as the app routes them: the start, the stops (each one visited) and the destination, with
 * the rider's road style, surface and what to avoid ([routeOptions], the app's saved-route JSON). Adventures and
 * roundtrips, as the app plans them.
 *
 * A route comes leg by leg ([leg]: from one point to the next, leaving it the way the leg before came in), so the page
 * keeps the legs it has and asks only for those that changed: dragging one point of a long trip is two short searches,
 * not the whole trip again. Nothing is kept here between requests.
 *
 * The VPS has two cores and also runs the relay, so at most [parallel] searches run at once; up to [queue] more wait
 * [waitSeconds] for their turn, and the rest are told the planner is busy. An adventure or a roundtrip works out dozens
 * of roads on every core, so it takes all [parallel] places: one at a time, and nothing else meanwhile. A page that asks
 * again before its last answer came (the rider dragged on) gives up its last search, waiting or running ([tab]).
 */
class Planner(
  private val leg: (LatLon, LatLon, RouteOptions, Double?) -> Route,
  private val adventure: (AdventureAsk) -> Route = { throw PlanRefused(503, "busy") },
  private val roundtrip: (LoopAsk) -> Route = { throw PlanRefused(503, "busy") },
  private val pois: Pois? = null,
  private val parallel: Int = 2,
  private val queue: Int = 8,
  private val waitSeconds: Long = 25,
  val stats: Stats = Stats(),
) {
  private val running = Semaphore(parallel, true)
  private val waiting = AtomicInteger(0)
  /** The search each page (its `tab`) has going, to give up when the same page asks for something newer. */
  private val tabs = ConcurrentHashMap<String, BRouterEngine.Cancel>()

  constructor(engine: BRouterEngine, roads: SurfaceReader? = null, pois: Pois? = null) : this(
    { a, b, options, heading -> engine.routeLegs(listOf(a, b), options, heading, maxMillis = 45_000).single() },
    { a -> AdventurePlanner(engine, roads = roads).plan(a.start, a.destination, a.area, a.minutes, a.options, a.variant) },
    { l -> RoundtripPlanner(engine).planArea(l.start, l.area, l.options, l.variant, l.maxKm?.toDouble()) },
    pois,
  )

  /**
   * The legs between `points` (2 to [MAX_POINTS]), the first one leaving its start the way `heading` says (the bearing
   * a leg before these arrived with, as the answer gives it in `arrive`). The answer has every leg on its own (`legs`)
   * and the whole trip's numbers; a page that does not ask for `legs` gets the whole line too, as before.
   */
  fun plan(body: JSONObject): JSONObject {
    val points = points(body.optJSONArray("points") ?: throw PlanRefused(400, "bad_request"))
    val options = routeOptions(body.optJSONObject("options") ?: JSONObject())
    val heading = if (body.has("heading") && !body.isNull("heading")) body.getDouble("heading").also { if (it !in 0.0..360.0) throw PlanRefused(400, "bad_request") } else null
    return inTurn(1, waitSeconds, tab(body), "route") { cancel ->
      val legs = ArrayList<Route>(points.size - 1)
      for ((i, ab) in points.zipWithNext().withIndex()) {
        val h = if (i == 0) heading else arrival(legs.last())
        legs += try {
          BRouterEngine.cancellable(cancel) { leg(ab.first, ab.second, options, h) }
        } catch (e: RoutingException) {
          throw refusal(e, "no_route")
        }
        stats.legs.incrementAndGet()
      }
      val out = totals(legs).put("legs", JSONArray(legs.map(::legJson)))
      if (!body.optBoolean("legs")) out.put("line", JSONArray(legs.flatMapIndexed { i, r -> thin(r).first.drop(if (i == 0) 0 else 1) }.map(::pair)))
      out
    }
  }

  /**
   * An adventure: `points` [start, destination], `area` the outline drawn, `minutes` at most (or none), `variant` for
   * another suggestion. The answer is the route as [plan]'s, and `vias`: points along it that keep it on these roads
   * when the trip is changed or routed again (as the app makes an adventure an ordinary trip), with the legs between them.
   */
  fun adventure(body: JSONObject): JSONObject {
    val ends = points(body.optJSONArray("points") ?: throw PlanRefused(400, "bad_request"))
    if (ends.size != 2) throw PlanRefused(400, "bad_request")
    val area = area(body.optJSONArray("area") ?: throw PlanRefused(400, "bad_request"))
    val minutes = if (body.has("minutes") && !body.isNull("minutes")) body.getInt("minutes").also { if (it !in 10..24 * 60) throw PlanRefused(400, "bad_request") } else null
    val ask = AdventureAsk(ends[0], ends[1], area, minutes, routeOptions(body.optJSONObject("options") ?: JSONObject()), body.optInt("variant").coerceIn(0, 1000))
    // Waits longer for its turn: the one before may be an adventure too.
    return inTurn(parallel, waitSeconds * 3, tab(body), "adventure") {
      val r = try {
        adventure(ask)
      } catch (e: NoAdventureException) {
        throw if (e.directMinutes != null) PlanRefused(422, "adventure_too_short", e.directMinutes) else PlanRefused(422, "no_adventure")
      } catch (e: RoutingException) {
        throw refusal(e, "no_adventure")
      }
      withVias(r)
    }
  }

  /** A roundtrip: `start`, `area` the outline drawn round where to ride, `km` at most (or none), `variant` for another. */
  fun roundtrip(body: JSONObject): JSONObject {
    val start = points(JSONArray().put(body.optJSONArray("start") ?: throw PlanRefused(400, "bad_request")), min = 1).single()
    val area = area(body.optJSONArray("area") ?: throw PlanRefused(400, "bad_request"))
    val km = if (body.has("km") && !body.isNull("km")) body.getInt("km").also { if (it !in 10..1000) throw PlanRefused(400, "bad_request") } else null
    val ask = LoopAsk(start, area, km, routeOptions(body.optJSONObject("options") ?: JSONObject()), body.optInt("variant").coerceIn(0, 1000))
    return inTurn(parallel, waitSeconds * 3, tab(body), "roundtrip") {
      val r = try {
        roundtrip(ask)
      } catch (e: RoutingException) {
        throw refusal(e, "no_roundtrip")
      }
      withVias(r)
    }
  }

  /** Places of [kinds] in a box (`b`: west,south,east,north), at most [MAX_POIS]: for the map's place layers. */
  fun pois(box: String?, kinds: String?): JSONObject {
    val b = box?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 } ?: throw PlanRefused(400, "bad_request")
    val ks = kinds?.split(',')?.filter { it in Pois.KINDS }?.toSet()?.takeIf { it.isNotEmpty() } ?: throw PlanRefused(400, "bad_request")
    // A view of half a continent is not asked for: the page shows each kind only from close enough.
    if (b[2] - b[0] > 20 || b[3] - b[1] > 12 || b[2] < b[0] || b[3] < b[1]) throw PlanRefused(400, "too_long")
    val list = pois?.inBox(b[1], b[3], b[0], b[2], ks, MAX_POIS + 1).orEmpty()
    return JSONObject().put("pois", JSONArray(list.take(MAX_POIS).map { JSONArray().put(it.kind).put(round5(it.lat)).put(round5(it.lon)).put(it.name) }))
      .put("more", list.size > MAX_POIS)
  }

  /** The route, its legs between the points along it ([vias]) as [plan] gives them, and those points. */
  private fun withVias(r: Route): JSONObject {
    val at = vias(r)
    val cuts = listOf(0) + at + r.points.lastIndex
    val legs = cuts.zipWithNext().map { (a, b) -> part(r, a, b) }
    return totals(legs).put("legs", JSONArray(legs.map(::legJson)))
      .put("vias", JSONArray(at.map { JSONArray().put(round5(r.points[it].lat)).put(round5(r.points[it].lon)) }))
  }

  /** The whole trip's numbers from its legs; how curvy from the heading change over all of them, as the app scores it. */
  private fun totals(legs: List<Route>): JSONObject {
    val m = legs.sumOf { it.distanceMeters }
    val dpk = if (m > 0) legs.sumOf { Curviness.degreesPerKm(line(it)) * it.distanceMeters } / m else 0.0
    return JSONObject().put("m", Math.round(m)).put("s", Math.round(legs.sumOf { it.durationSeconds }))
      .put("up", Math.round(legs.sumOf { it.ascentMeters })).put("curvy", (dpk / 3.0).coerceIn(0.0, 100.0).toInt())
      .put("ferries", legs.sumOf { it.ferryCount }).put("unpaved", Math.round(legs.sumOf { it.unpavedMeters }))
  }

  /**
   * One leg as the page keeps it: the line thinned to what stays within 5 m of the road, the height at each of its
   * points, its numbers, which way it arrives (for the leg after it), and the fuel stations along it.
   */
  private fun legJson(r: Route): JSONObject {
    val (line, ele) = thin(r)
    return JSONObject()
      .put("line", JSONArray(line.map(::pair))).put("ele", JSONArray(ele))
      .put("m", Math.round(r.distanceMeters)).put("s", Math.round(r.durationSeconds)).put("up", Math.round(r.ascentMeters))
      .put("dpk", Math.round(Curviness.degreesPerKm(line(r)))).put("ferries", r.ferryCount).put("unpaved", Math.round(r.unpavedMeters))
      .put("arrive", arrival(r)?.toInt() ?: JSONObject.NULL)
      .put("surf", JSONArray(surfaces(r).map { (from, to, kind) -> JSONArray().put(from).put(to).put(kind) }))
      .apply {
        if (pois == null) return@apply
        val corridor = RouteCorridor(r)
        val pad = 0.02
        put("fuel", JSONArray(pois.inBox(corridor.minLat - pad, corridor.maxLat + pad, corridor.minLon - pad * 2, corridor.maxLon + pad * 2, setOf("fuel"), 20_000)
          .mapNotNull { p -> corridor.alongDistance(p.lat, p.lon, FUEL_MAX_OFFSET_M)?.let { it to p } }
          .sortedBy { it.first }
          .map { (along, p) -> JSONArray().put(Math.round(along)).put(round5(p.lat)).put(round5(p.lon)).put(p.name) }))
      }
  }

  /**
   * Runs [work] holding [places] of the [parallel] ones, after waiting at most [wait] seconds in the queue. A newer
   * request from the same [tab] (the same page, the same kind of search) makes this one give up, waiting or running.
   */
  private fun inTurn(places: Int, wait: Long, tab: String?, kind: String, work: (BRouterEngine.Cancel) -> JSONObject): JSONObject {
    stats.asked(kind)
    val cancel = BRouterEngine.Cancel()
    val key = tab?.let { "$kind:$it" }
    if (key != null) tabs.put(key, cancel)?.cancel()
    try {
      if (waiting.incrementAndGet() > queue + parallel) {
        waiting.decrementAndGet()
        stats.busy.incrementAndGet()
        throw PlanRefused(503, "busy")
      }
      stats.queued(waiting.get())
      try {
        val t0 = System.nanoTime()
        // In short steps, so that a page that moved on frees its place in the queue at once.
        var got = false
        val until = t0 + TimeUnit.SECONDS.toNanos(wait)
        while (!got && !cancel.cancelled && System.nanoTime() < until) got = running.tryAcquire(places, 200, TimeUnit.MILLISECONDS)
        if (!got) {
          if (cancel.cancelled) { stats.superseded.incrementAndGet(); throw PlanRefused(409, "superseded") }
          stats.busy.incrementAndGet()
          throw PlanRefused(503, "busy")
        }
        val t1 = System.nanoTime()
        stats.waitedMillis.addAndGet((t1 - t0) / 1_000_000)
        try {
          return work(cancel)
        } catch (e: PlanRefused) {
          if (e.code == "superseded") stats.superseded.incrementAndGet()
          throw e
        } finally {
          running.release(places)
          stats.workedMillis.addAndGet((System.nanoTime() - t1) / 1_000_000)
        }
      } finally {
        waiting.decrementAndGet()
      }
    } finally {
      if (key != null) tabs.remove(key, cancel)
    }
  }

  companion object {
    const val MAX_POINTS = 27
    /** An outline drawn by hand: enough points for any shape, and no bigger than a long day's ride across. */
    const val MAX_AREA_POINTS = 2000
    const val MAX_AREA_KM = 2000.0
    /** About this far apart, the points that keep an adventure or a roundtrip on its roads (as in the app). */
    const val VIA_SPACING_M = 15_000.0
    /** A fuel station counts as on the route this close to it, as in the app. */
    const val FUEL_MAX_OFFSET_M = 400.0
    const val MAX_POIS = 1500

    /** A page's name for itself: random, made when the page opens, kept nowhere. */
    private fun tab(body: JSONObject) = body.optString("tab").takeIf { it.length in 8..64 }

    /** "datafile E10_N50.rd5 not found": a place outside the routing data the server has. */
    private fun refusal(e: RoutingException, otherwise: String): PlanRefused {
      val m = e.message.orEmpty()
      if (m == BRouterEngine.CANCELLED) return PlanRefused(409, "superseded")
      // Only what BRouter said, never where: to see why riders get no road, without keeping their trips.
      System.err.println("planner: no route: ${m.take(160)}")
      return PlanRefused(422, when {
        m.startsWith("datafile") -> "outside"
        // Out of time (a very long, very curvy trip): not the same as no road at all.
        m.contains("timeout", ignoreCase = true) || m.contains("killed", ignoreCase = true) -> "too_slow"
        else -> otherwise
      })
    }

    fun area(a: JSONArray): List<LatLon> {
      if (a.length() < 3 || a.length() > MAX_AREA_POINTS) throw PlanRefused(400, "bad_request")
      val pts = (0 until a.length()).map {
        val p = a.getJSONArray(it)
        val lat = p.getDouble(0); val lon = p.getDouble(1)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw PlanRefused(400, "bad_request")
        LatLon(lat, lon)
      }
      if (pts.zipWithNext().sumOf { (a, b) -> Geo.distance(a, b) } > MAX_AREA_KM * 1000) throw PlanRefused(400, "too_long")
      return pts
    }

    /** 4 to 16 points evenly along [r] (their index in it), never on a ferry (a stop out at sea is a ride out and back). */
    fun vias(r: Route): List<Int> {
      val count = (r.distanceMeters / VIA_SPACING_M).toInt().coerceIn(4, 16)
      var next = 0
      return buildList {
        for ((i, pt) in r.points.withIndex()) if (next < count && i in 1 until r.points.lastIndex && !pt.ferry && pt.distanceFromStart >= r.distanceMeters * (next + 1) / (count + 1)) {
          add(i); next++
        }
      }
    }

    /** Points [from] to [to] of [r] as a route of their own (a leg between two of its vias). */
    fun part(r: Route, from: Int, to: Int): Route {
      val pts = r.points.subList(from, to + 1)
      val d0 = pts.first().distanceFromStart; val s0 = pts.first().secondsFromStart
      var up = 0.0; var unpaved = 0.0; var ferries = 0
      for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]
        if (b.elevation > a.elevation) up += b.elevation - a.elevation
        if (b.unpaved) unpaved += b.distanceFromStart - a.distanceFromStart
        if (b.ferry && !a.ferry) ferries++
      }
      val rebased = pts.map { it.copy(distanceFromStart = it.distanceFromStart - d0, secondsFromStart = it.secondsFromStart - s0) }
      return Route(rebased, emptyList(), pts.last().distanceFromStart - d0, pts.last().secondsFromStart - s0, up,
        Curviness.score(rebased.map { LatLon(it.lat, it.lon) }), unpaved, ferries)
    }

    /**
     * The unpaved stretches of [r] as the app colours them on the route: [from, to] metres along it and the kind, 1 for
     * gravel (orange), 2 for a track (purple). A point's kind is that of the road leading to it.
     */
    fun surfaces(r: Route): List<Triple<Long, Long, Int>> {
      val out = ArrayList<Triple<Long, Long, Int>>()
      val pts = r.points
      var i = 1
      while (i < pts.size) {
        val kind = surfaceKind(pts[i])
        var j = i
        while (j + 1 < pts.size && surfaceKind(pts[j + 1]) == kind) j++
        if (kind != 0) out += Triple(Math.round(pts[i - 1].distanceFromStart), Math.round(pts[j].distanceFromStart), kind)
        i = j + 1
      }
      return out
    }

    private fun surfaceKind(p: com.curveon.routing.RoutePoint) = if (p.highway == "track") 2 else if (p.unpaved) 1 else 0

    /** Which way [leg] arrives, in whole degrees: the leg after it is asked to leave that way, here and from the page alike. */
    private fun arrival(leg: Route): Double? = BRouterEngine.arrivalHeading(leg)?.roundToInt()?.toDouble()?.rem(360.0)

    /** As the crow flies, summed over the legs; longer trips are better split into days. */
    const val MAX_KM = 1500.0

    fun points(a: JSONArray, min: Int = 2): List<LatLon> {
      if (a.length() < min || a.length() > MAX_POINTS) throw PlanRefused(400, "bad_request")
      val pts = (0 until a.length()).map {
        val p = a.getJSONArray(it)
        val lat = p.getDouble(0); val lon = p.getDouble(1)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw PlanRefused(400, "bad_request")
        LatLon(lat, lon)
      }
      if (pts.zipWithNext().sumOf { (a, b) -> Geo.distance(a, b) } > MAX_KM * 1000) throw PlanRefused(400, "too_long")
      return pts
    }

    /** The app's options JSON (SavedRoutes.optionsJson): style, surface, ferries, motorways, trunk, toll. */
    fun routeOptions(j: JSONObject) = RouteOptions(
      runCatching { RoadStyle.valueOf(j.optString("style", "CURVY")) }.getOrDefault(RoadStyle.CURVY),
      runCatching { SurfacePreference.valueOf(j.optString("surface", "PAVED")) }.getOrDefault(SurfacePreference.PAVED),
      avoidFerries = j.optBoolean("ferries"), avoidMotorways = j.optBoolean("motorways"),
      avoidTrunk = j.optBoolean("trunk"), avoidToll = j.optBoolean("toll"),
    )

    private fun line(r: Route) = r.points.map { LatLon(it.lat, it.lon) }

    /** The line thinned to what stays within 5 m of the road (the way a trip shared with a group is), and the heights there. */
    private fun thin(r: Route): Pair<List<LatLon>, List<Int>> {
      val pts = line(r)
      val keep = Geo.simplifyKeep(pts, 5.0)
      var last = 0
      val ele = ArrayList<Int>()
      val out = ArrayList<LatLon>()
      for (i in pts.indices) if (keep[i]) {
        out += pts[i]
        val e = r.points[i].elevation
        if (!e.isNaN()) last = e.roundToInt()
        ele += last
      }
      return out to ele
    }

    private fun pair(p: LatLon) = JSONArray().put(round5(p.lat)).put(round5(p.lon))

    private fun round5(x: Double) = Math.round(x * 1e5) / 1e5
  }
}
