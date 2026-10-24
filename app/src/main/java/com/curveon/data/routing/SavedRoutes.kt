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
 * Everything is saved as its exact line, so riding it later takes the same roads even if the map
 * data or the router changes. A→B trips also keep their stops, start and destination.
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
            isRoundtrip = goal is NavigationGoal.Roundtrip || (goal as? NavigationGoal.Destination)?.loop != null
        )
        return when (goal) {
            is NavigationGoal.Destination -> base.copy(
                subtitle = goal.stops.filterNot { it.via }.let { real -> if (real.isEmpty()) "" else "via " + real.joinToString { it.name } },
                waypointsJson = JSONObject()
                    .put("options", optionsJson(o))
                    .put("stops", JSONArray(goal.stops.map { stopJson(it.name, it.point).apply { if (it.via) put("via", true) } }))
                    .put("destination", stopJson(goal.name, goal.destination))
                    .apply { goal.start?.let { put("start", stopJson(it.name, it.point)) } }
                    .toString(),
                polylineJson = encode(route.points.map { LatLon(it.lat, it.lon) })
            )
            else -> base.copy(
                subtitle = if (goal is NavigationGoal.Roundtrip) "Loop" else "Track", // shown translated in Where to?
                waypointsJson = JSONObject().put("options", optionsJson(o)).toString(),
                polylineJson = encode(route.points.map { LatLon(it.lat, it.lon) })
            )
        }
    }

    /**
     * A trip planned on the web (groups' WebPlanTrip), kept among the planned trips like one saved from the planner:
     * its stops, start and destination, and the line the web planner worked out, ridden as it is.
     */
    fun webItem(trip: com.curveon.groups.WebPlanTrip): RouteItem {
        val options = optionsFromJson(trip.options)
        fun stop(s: com.curveon.groups.TripStop) = stopJson(s.name, LatLon(s.lat, s.lon)).apply { if (s.via) put("via", true) }
        return RouteItem(
            title = trip.name.ifBlank { trip.destination.name },
            subtitle = trip.stops.filterNot { it.via }.let { real -> if (real.isEmpty()) "" else "via " + real.joinToString { it.name } },
            totalDistanceKm = (trip.distanceMeters / 1000).toFloat(),
            estimatedMinutes = (trip.durationSeconds / 60).toInt(),
            roadType = options.roadStyle.name,
            groundType = options.surface.name,
            curvinessScore = trip.curviness,
            elevationGainMeters = trip.ascentMeters.toInt(),
            polylineJson = trip.line,
            waypointsJson = JSONObject()
                .put("options", optionsJson(options))
                .put("stops", JSONArray(trip.stops.map(::stop)))
                .put("destination", stop(trip.destination))
                .apply { trip.start?.let { put("start", stop(it)) } }
                .toString(),
            inWhereTo = false,
            inPlanned = true
        )
    }

    /**
     * A planned trip as the web planner shows it ([group]: its group's name): stops and destination to work on there,
     * and the line thinned to 5 m. A trip without a destination (a ride kept as a trip, a loop) is shown, not changed.
     */
    fun webHave(item: RouteItem, group: String?): com.curveon.groups.WebPlanHave {
        val json = runCatching { JSONObject(item.waypointsJson) }.getOrNull() ?: JSONObject()
        val options = json.optJSONObject("options")?.let(::parseOptions)
            ?: RouteOptions(runCatching { RoadStyle.valueOf(item.roadType) }.getOrDefault(RoadStyle.CURVY),
                runCatching { SurfacePreference.valueOf(item.groundType) }.getOrDefault(SurfacePreference.PAVED))
        fun trip(s: Stop) = com.curveon.groups.TripStop(s.name, s.point.lat, s.point.lon, s.via)
        val line = com.curveon.routing.Geo.simplify(decode(item.polylineJson), 5.0)
        return com.curveon.groups.WebPlanHave(
            id = item.id,
            rev = listOf(item.title, item.polylineJson, item.waypointsJson, group, item.totalDistanceKm).hashCode().toUInt().toString(36),
            name = item.title, group = group, roundtrip = item.isRoundtrip,
            start = json.optJSONObject("start")?.let { trip(parseStop(it)) },
            destination = json.optJSONObject("destination")?.let { trip(parseStop(it)) },
            stops = json.optJSONArray("stops")?.let { a -> (0 until a.length()).map { trip(parseStop(a.getJSONObject(it))) } }.orEmpty(),
            options = optionsToJson(options),
            poly = com.curveon.groups.Polyline.encode(line.map { it.lat to it.lon }),
            distanceMeters = item.totalDistanceKm * 1000.0, durationSeconds = item.estimatedMinutes * 60.0,
            ascentMeters = item.elevationGainMeters.toDouble(), curviness = item.curvinessScore, savedAt = item.timestamp
        )
    }

    /** A planned trip made of a line ridden or drawn earlier (a ride from the log), to ride again as it is. */
    fun trackItem(name: String, points: List<LatLon>, distanceMeters: Double, minutes: Int, options: RouteOptions, groupId: Long?,
        inWhereTo: Boolean = false, inPlanned: Boolean = true) = RouteItem(
        title = name, subtitle = "Track", totalDistanceKm = (distanceMeters / 1000).toFloat(), estimatedMinutes = minutes,
        roadType = options.roadStyle.name, groundType = options.surface.name, curvinessScore = 0, elevationGainMeters = 0,
        polylineJson = encode(points), waypointsJson = JSONObject().put("options", optionsJson(options)).toString(), groupId = groupId,
        inWhereTo = inWhereTo, inPlanned = inPlanned
    )

    fun toGoal(item: RouteItem): NavigationGoal {
        val json = runCatching { JSONObject(item.waypointsJson) }.getOrNull() ?: JSONObject()
        val options = json.optJSONObject("options")?.let(::parseOptions)
            ?: RouteOptions(RoadStyle.valueOf(item.roadType), SurfacePreference.valueOf(item.groundType))
        val dest = json.optJSONObject("destination")
        // A→B trips come back with their stops, start and destination, so they can be changed;
        // until they are, the saved line is ridden as it is.
        return if (dest != null) {
            val stops = json.optJSONArray("stops")?.let { a -> (0 until a.length()).map { parseStop(a.getJSONObject(it)) } }.orEmpty()
            val d = parseStop(dest)
            NavigationGoal.Destination(
                d.name, d.point, options, stops,
                start = json.optJSONObject("start")?.let(::parseStop),
                fixedLine = item.polylineJson.takeIf { it.isNotEmpty() }?.let(::decode)?.takeIf { it.size >= 2 },
                saved = SavedRef(item.id, item.title)
            )
        } else {
            NavigationGoal.Track(item.title, decode(item.polylineJson), options)
        }
    }

    /** The road style, surface and what to avoid, as JSON text (for a trip shared with a group). */
    fun optionsToJson(o: RouteOptions): String = optionsJson(o).toString()

    fun optionsFromJson(s: String): RouteOptions = runCatching { parseOptions(JSONObject(s)) }.getOrDefault(RouteOptions(RoadStyle.CURVY, SurfacePreference.PAVED))

    private fun optionsJson(o: RouteOptions) = JSONObject()
        .put("style", o.roadStyle.name).put("surface", o.surface.name)
        .put("ferries", o.avoidFerries).put("motorways", o.avoidMotorways).put("trunk", o.avoidTrunk).put("toll", o.avoidToll)

    private fun parseOptions(j: JSONObject) = RouteOptions(
        RoadStyle.valueOf(j.optString("style", "CURVY")),
        SurfacePreference.valueOf(j.optString("surface", "PAVED")),
        avoidFerries = j.optBoolean("ferries"), avoidMotorways = j.optBoolean("motorways"),
        avoidTrunk = j.optBoolean("trunk"), avoidToll = j.optBoolean("toll")
    )

    private fun stopJson(name: String, p: LatLon) = JSONObject().put("name", name).put("lat", p.lat).put("lon", p.lon)

    private fun parseStop(j: JSONObject) = Stop(j.getString("name"), LatLon(j.getDouble("lat"), j.getDouble("lon")), j.optBoolean("via"))

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
class RecentDestinations(private val context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun all(): List<Stop> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Stop(it.getString("name"), LatLon(it.getDouble("lat"), it.getDouble("lon"))) }
    }.getOrDefault(emptyList())

    fun add(stop: Stop) {
        if (stop.name == context.getString(com.curveon.R.string.map_point)) return
        val list = listOf(stop) + all().filterNot { it.name == stop.name || com.curveon.routing.Geo.distance(it.point, stop.point) < 100 }
        val a = JSONArray(list.take(MAX).map { JSONObject().put("name", it.name).put("lat", it.point.lat).put("lon", it.point.lon) })
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    companion object {
        private const val KEY = "list"
        private const val MAX = 8
    }
}

/** A place kept to go to again: Home (one at most), or one the rider named. */
data class Favorite(val name: String, val point: LatLon, val home: Boolean = false)

/** Favourite places, shown first in Where to?; Home leads. */
class FavoritePlaces(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): List<Favorite> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Favorite(it.getString("name"), LatLon(it.getDouble("lat"), it.getDouble("lon")), it.optBoolean("home")) }
    }.getOrDefault(emptyList()).sortedByDescending { it.home }

    /** Adds [fav]; a new Home replaces the old one, and the same name or spot is kept once. */
    fun save(fav: Favorite) = write(listOf(fav) + all().filterNot {
        (fav.home && it.home) || (!it.home && it.name == fav.name) || com.curveon.routing.Geo.distance(it.point, fav.point) < 50
    })

    fun remove(fav: Favorite) = write(all().filterNot { it == fav })

    private fun write(list: List<Favorite>) {
        val a = JSONArray(list.sortedByDescending { it.home }.map {
            JSONObject().put("name", it.name).put("lat", it.point.lat).put("lon", it.point.lon).put("home", it.home)
        })
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}
