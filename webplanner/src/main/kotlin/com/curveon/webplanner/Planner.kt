package com.curveon.webplanner

import com.curveon.routing.AdventurePlanner
import com.curveon.routing.BRouterEngine
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.NoAdventureException
import com.curveon.routing.RoadStyle
import com.curveon.routing.Route
import com.curveon.routing.RouteOptions
import com.curveon.routing.RoutingException
import com.curveon.routing.SurfacePreference
import com.curveon.routing.SurfaceReader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A request the planner turns down, with the HTTP status and a code the page shows in the rider's language ([minutes]:
 * how long the quickest way takes, when an adventure was given too little time).
 */
class PlanRefused(val status: Int, val code: String, val minutes: Int? = null) : Exception(code)

/** An adventure as the page asks for it: start to destination through the fun roads of [area], in at most [minutes]. */
class AdventureAsk(val start: LatLon, val destination: LatLon, val area: List<LatLon>, val minutes: Int?, val options: RouteOptions, val variant: Int)

/**
 * Routes for the web page, as the app routes them: the start, the stops (each one visited) and the destination, with
 * the rider's road style, surface and what to avoid ([routeOptions], the app's saved-route JSON). And adventures, as the
 * app plans them ([AdventurePlanner]).
 *
 * The VPS has two cores and also runs the relay, so at most [parallel] searches run at once; up to [queue] more wait
 * [waitSeconds] for their turn, and the rest are told the planner is busy. An adventure works out dozens of roads on
 * every core, so it takes all [parallel] places: one at a time, and nothing else meanwhile.
 */
class Planner(
  private val route: (List<LatLon>, RouteOptions) -> Route,
  private val adventure: (AdventureAsk) -> Route = { throw PlanRefused(503, "busy") },
  private val parallel: Int = 2,
  private val queue: Int = 8,
  private val waitSeconds: Long = 25,
) {
  private val running = Semaphore(parallel, true)
  private val waiting = AtomicInteger(0)

  constructor(engine: BRouterEngine, roads: SurfaceReader? = null) : this(
    { points, options -> engine.route(points, options, visitEveryVia = true, maxMillis = 45_000) },
    { a -> AdventurePlanner(engine, roads = roads).plan(a.start, a.destination, a.area, a.minutes, a.options, a.variant) },
  )

  fun plan(body: JSONObject): JSONObject {
    val points = points(body.optJSONArray("points") ?: throw PlanRefused(400, "bad_request"))
    val options = routeOptions(body.optJSONObject("options") ?: JSONObject())
    return inTurn(1, waitSeconds) {
      val r = try {
        route(points, options)
      } catch (e: RoutingException) {
        throw refusal(e, "no_route")
      }
      result(r)
    }
  }

  /**
   * An adventure: `points` [start, destination], `area` the outline drawn, `minutes` at most (or none), `variant` for
   * another suggestion. The answer is the route as [plan]'s, and `vias`: points along it that keep it on these roads
   * when the trip is changed or routed again (as the app makes an adventure an ordinary trip).
   */
  fun adventure(body: JSONObject): JSONObject {
    val ends = points(body.optJSONArray("points") ?: throw PlanRefused(400, "bad_request"))
    if (ends.size != 2) throw PlanRefused(400, "bad_request")
    val area = area(body.optJSONArray("area") ?: throw PlanRefused(400, "bad_request"))
    val minutes = if (body.has("minutes") && !body.isNull("minutes")) body.getInt("minutes").also { if (it !in 10..24 * 60) throw PlanRefused(400, "bad_request") } else null
    val ask = AdventureAsk(ends[0], ends[1], area, minutes, routeOptions(body.optJSONObject("options") ?: JSONObject()), body.optInt("variant").coerceIn(0, 1000))
    // Waits longer for its turn: the one before may be an adventure too.
    return inTurn(parallel, waitSeconds * 3) {
      val r = try {
        adventure(ask)
      } catch (e: NoAdventureException) {
        throw if (e.directMinutes != null) PlanRefused(422, "adventure_too_short", e.directMinutes) else PlanRefused(422, "no_adventure")
      } catch (e: RoutingException) {
        throw refusal(e, "no_adventure")
      }
      result(r).put("vias", JSONArray(vias(r).map { JSONArray().put(round5(it.lat)).put(round5(it.lon)) }))
    }
  }

  /** Runs [work] holding [places] of the [parallel] ones, after waiting at most [wait] seconds in the queue. */
  private fun inTurn(places: Int, wait: Long, work: () -> JSONObject): JSONObject {
    if (waiting.incrementAndGet() > queue + parallel) {
      waiting.decrementAndGet()
      throw PlanRefused(503, "busy")
    }
    try {
      if (!running.tryAcquire(places, wait, TimeUnit.SECONDS)) throw PlanRefused(503, "busy")
      try {
        return work()
      } finally {
        running.release(places)
      }
    } finally {
      waiting.decrementAndGet()
    }
  }

  companion object {
    const val MAX_POINTS = 27
    /** An outline drawn by hand: enough points for any shape, and no bigger than a long day's ride across. */
    const val MAX_AREA_POINTS = 2000
    const val MAX_AREA_KM = 2000.0
    /** About this far apart, the points that keep an adventure on its roads (as in the app). */
    const val VIA_SPACING_M = 15_000.0

    /** "datafile E10_N50.rd5 not found": a place outside the routing data the server has. */
    private fun refusal(e: RoutingException, otherwise: String) =
      PlanRefused(422, if (e.message.orEmpty().startsWith("datafile")) "outside" else otherwise)

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

    /** 4 to 16 points evenly along [r], never on a ferry (a stop out at sea is a ride out and back). */
    fun vias(r: Route): List<LatLon> {
      val count = (r.distanceMeters / VIA_SPACING_M).toInt().coerceIn(4, 16)
      var next = 0
      return buildList {
        for (pt in r.points) if (next < count && !pt.ferry && pt.distanceFromStart >= r.distanceMeters * (next + 1) / (count + 1)) {
          add(LatLon(pt.lat, pt.lon)); next++
        }
      }
    }
    /** As the crow flies, summed over the legs; longer trips are better split into days. */
    const val MAX_KM = 1500.0

    fun points(a: JSONArray): List<LatLon> {
      if (a.length() < 2 || a.length() > MAX_POINTS) throw PlanRefused(400, "bad_request")
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

    /** The line thinned to what stays within 5 m of the road, the way a trip shared with a group is. */
    fun result(r: Route): JSONObject {
      val line = Geo.simplify(r.points.map { LatLon(it.lat, it.lon) }, 5.0)
      return JSONObject()
        .put("line", JSONArray(line.map { JSONArray().put(round5(it.lat)).put(round5(it.lon)) }))
        .put("m", Math.round(r.distanceMeters)).put("s", Math.round(r.durationSeconds)).put("up", Math.round(r.ascentMeters))
        .put("curvy", r.curviness).put("ferries", r.ferryCount).put("unpaved", Math.round(r.unpavedMeters))
    }

    private fun round5(x: Double) = Math.round(x * 1e5) / 1e5
  }
}
