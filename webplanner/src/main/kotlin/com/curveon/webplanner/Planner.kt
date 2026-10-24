package com.curveon.webplanner

import com.curveon.routing.BRouterEngine
import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.RoadStyle
import com.curveon.routing.Route
import com.curveon.routing.RouteOptions
import com.curveon.routing.RoutingException
import com.curveon.routing.SurfacePreference
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A request the planner turns down, with the HTTP status and a code the page shows in the rider's language. */
class PlanRefused(val status: Int, val code: String) : Exception(code)

/**
 * Routes for the web page, as the app routes them: the start, the stops (each one visited) and the destination, with
 * the rider's road style, surface and what to avoid ([routeOptions], the app's saved-route JSON).
 *
 * The VPS has two cores and also runs the relay, so at most [parallel] searches run at once; up to [queue] more wait
 * [waitSeconds] for their turn, and the rest are told the planner is busy.
 */
class Planner(
  private val route: (List<LatLon>, RouteOptions) -> Route,
  private val parallel: Int = 2,
  private val queue: Int = 8,
  private val waitSeconds: Long = 25,
) {
  private val running = Semaphore(parallel, true)
  private val waiting = AtomicInteger(0)

  constructor(engine: BRouterEngine) : this({ points, options -> engine.route(points, options, visitEveryVia = true, maxMillis = 45_000) })

  fun plan(body: JSONObject): JSONObject {
    val points = points(body.optJSONArray("points") ?: throw PlanRefused(400, "bad_request"))
    val options = routeOptions(body.optJSONObject("options") ?: JSONObject())
    if (waiting.incrementAndGet() > queue + parallel) {
      waiting.decrementAndGet()
      throw PlanRefused(503, "busy")
    }
    try {
      if (!running.tryAcquire(waitSeconds, TimeUnit.SECONDS)) throw PlanRefused(503, "busy")
      try {
        val r = try {
          route(points, options)
        } catch (e: RoutingException) {
          throw PlanRefused(422, "no_route")
        }
        return result(r)
      } finally {
        running.release()
      }
    } finally {
      waiting.decrementAndGet()
    }
  }

  companion object {
    const val MAX_POINTS = 27
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
