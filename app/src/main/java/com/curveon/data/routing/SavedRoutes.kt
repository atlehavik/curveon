package com.curveon.data.routing

import android.content.Context
import com.curveon.data.model.RouteItem
import com.curveon.routing.LatLon
import com.curveon.routing.RoadStyle
import com.curveon.routing.Route
import com.curveon.routing.RouteOptions
import com.curveon.routing.SurfacePreference
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Converts between [NavigationGoal]s and the saved_routes table.
 * A→B trips are saved as their stops (re-routed from wherever you are when you ride them);
 * loops and tracks are saved as their exact line so the same ride can be repeated.
 */
object SavedRoutes {

    fun toItem(goal: NavigationGoal, route: Route, name: String): RouteItem {
        val o = goal.options
        val base = RouteItem(
            title = name,
            totalDistanceKm = (route.distanceMeters / 1000).toFloat(),
            estimatedMinutes = (route.durationSeconds / 60).toInt(),
            roadType = o.roadStyle.name,
            groundType = o.surface.name,
            curvinessScore = route.curviness,
            elevationGainMeters = route.ascentMeters.toInt(),
            isRoundtrip = goal is NavigationGoal.Roundtrip
        )
        return when (goal) {
            is NavigationGoal.Destination -> base.copy(
                subtitle = if (goal.stops.isEmpty()) "" else "via " + goal.stops.joinToString { it.name },
                waypointsJson = JSONObject()
                    .put("options", optionsJson(o))
                    .put("stops", JSONArray(goal.stops.map { stopJson(it.name, it.point) }))
                    .put("destination", stopJson(goal.name, goal.destination))
                    .toString()
            )
            else -> base.copy(
                subtitle = if (goal is NavigationGoal.Roundtrip) "Loop" else "Track",
                waypointsJson = JSONObject().put("options", optionsJson(o)).toString(),
                polylineJson = encode(route.points.map { LatLon(it.lat, it.lon) })
            )
        }
    }

    fun toGoal(item: RouteItem): NavigationGoal {
        val json = runCatching { JSONObject(item.waypointsJson) }.getOrNull() ?: JSONObject()
        val options = json.optJSONObject("options")?.let(::parseOptions)
            ?: RouteOptions(RoadStyle.valueOf(item.roadType), SurfacePreference.valueOf(item.groundType))
        val dest = json.optJSONObject("destination")
        return if (dest != null) {
            val stops = json.optJSONArray("stops")?.let { a -> (0 until a.length()).map { parseStop(a.getJSONObject(it)) } }.orEmpty()
            val d = parseStop(dest)
            NavigationGoal.Destination(d.name, d.point, options, stops)
        } else {
            NavigationGoal.Track(item.title, decode(item.polylineJson), options)
        }
    }

    private fun optionsJson(o: RouteOptions) = JSONObject()
        .put("style", o.roadStyle.name).put("surface", o.surface.name)
        .put("ferries", o.avoidFerries).put("motorways", o.avoidMotorways).put("toll", o.avoidToll)

    private fun parseOptions(j: JSONObject) = RouteOptions(
        RoadStyle.valueOf(j.optString("style", "CURVY")),
        SurfacePreference.valueOf(j.optString("surface", "PAVED")),
        j.optBoolean("ferries"), j.optBoolean("motorways"), j.optBoolean("toll")
    )

    private fun stopJson(name: String, p: LatLon) = JSONObject().put("name", name).put("lat", p.lat).put("lon", p.lon)

    private fun parseStop(j: JSONObject) = Stop(j.getString("name"), LatLon(j.getDouble("lat"), j.getDouble("lon")))

    /** Thinned to ~every 25 m so a 300 km loop stays a few hundred kB. */
    fun encode(points: List<LatLon>): String {
        val out = StringBuilder()
        var last: LatLon? = null
        for ((i, p) in points.withIndex()) {
            if (last != null && i != points.lastIndex && com.curveon.routing.Geo.distance(last, p) < 25) continue
            if (out.isNotEmpty()) out.append(';')
            out.append(String.format(Locale.ROOT, "%.5f,%.5f", p.lat, p.lon))
            last = p
        }
        return out.toString()
    }

    fun decode(s: String): List<LatLon> =
        s.split(';').mapNotNull { part -> part.split(',').takeIf { it.size == 2 }?.let { LatLon(it[0].toDouble(), it[1].toDouble()) } }
}

/** The last few destinations, shown when the search box is empty. */
class RecentDestinations(context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun all(): List<Stop> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Stop(it.getString("name"), LatLon(it.getDouble("lat"), it.getDouble("lon"))) }
    }.getOrDefault(emptyList())

    fun add(stop: Stop) {
        if (stop.name == MAP_POINT) return
        val list = listOf(stop) + all().filterNot { it.name == stop.name || com.curveon.routing.Geo.distance(it.point, stop.point) < 100 }
        val a = JSONArray(list.take(MAX).map { JSONObject().put("name", it.name).put("lat", it.point.lat).put("lon", it.point.lon) })
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    companion object {
        private const val KEY = "list"
        private const val MAX = 8
        const val MAP_POINT = "Map point"
    }
}
