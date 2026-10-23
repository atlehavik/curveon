package com.curveon.data.routing

import android.content.Context
import com.curveon.routing.LatLon
import com.curveon.routing.RoadStyle
import com.curveon.routing.RouteOptions
import com.curveon.routing.SurfacePreference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The trip being planned, kept on disk while its preview is open. Out of sight Curveon sleeps, and
 * Android may end it then (or the device restart): the plan is still there when it is opened again.
 * Gone once the trip is started or the preview closed.
 */
class PlanDraft(context: Context) {

    private val file = File(context.filesDir, "plan-draft.json")

    /** The previewed trip and the road style picked, or null when nothing is being planned. */
    fun save(draft: Pair<NavigationGoal, Int>?) {
        if (draft == null) { file.delete(); return }
        val json = JSONObject().put("goal", goalJson(draft.first)).put("selected", draft.second).toString()
        val tmp = File(file.path + ".tmp")
        runCatching { tmp.writeText(json); tmp.renameTo(file) }
    }

    fun load(): Pair<NavigationGoal, Int>? = runCatching {
        val json = JSONObject(file.readText())
        parseGoal(json.getJSONObject("goal")) to json.optInt("selected")
    }.getOrNull()

    internal fun goalJson(goal: NavigationGoal): JSONObject = when (goal) {
        is NavigationGoal.Destination -> JSONObject().put("type", "destination")
            .put("name", goal.name).put("destination", pointJson(goal.destination))
            .put("stops", JSONArray(goal.stops.map(::stopJson)))
            .putOpt("start", goal.start?.let(::stopJson))
            .putOpt("fixedLine", goal.fixedLine?.let(SavedRoutes::encode))
            .putOpt("saved", goal.saved?.let { JSONObject().put("id", it.id).put("title", it.title) })
            .putOpt("loop", goal.loop?.let(::goalJson))
            .putOpt("adventure", goal.adventure?.let(::goalJson))
        is NavigationGoal.Roundtrip -> JSONObject().put("type", "roundtrip")
            .put("name", goal.name).put("distanceKm", goal.distanceKm).put("directionDeg", goal.directionDeg)
            .put("variant", goal.variant)
            .putOpt("area", goal.area?.let(SavedRoutes::encode))
            .putOpt("start", goal.start?.let(::stopJson))
            .putOpt("maxKm", goal.maxKm)
        is NavigationGoal.Adventure -> JSONObject().put("type", "adventure")
            .put("name", goal.name).put("destination", pointJson(goal.destination))
            .put("area", SavedRoutes.encode(goal.area)).putOpt("minutes", goal.minutes).put("variant", goal.variant)
            .putOpt("start", goal.start?.let(::stopJson))
        is NavigationGoal.Track -> JSONObject().put("type", "track")
            .put("name", goal.name).put("points", SavedRoutes.encode(goal.points))
    }.put("options", optionsJson(goal.options))

    internal fun parseGoal(j: JSONObject): NavigationGoal {
        val options = parseOptions(j.getJSONObject("options"))
        val start = j.optJSONObject("start")?.let(::parseStop)
        return when (j.getString("type")) {
            "destination" -> NavigationGoal.Destination(
                j.getString("name"), parsePoint(j.getJSONObject("destination")), options,
                stops = j.getJSONArray("stops").let { a -> (0 until a.length()).map { parseStop(a.getJSONObject(it)) } },
                start = start,
                fixedLine = j.optString("fixedLine").takeIf { it.isNotEmpty() }?.let(SavedRoutes::decode),
                saved = j.optJSONObject("saved")?.let { SavedRef(it.getLong("id"), it.getString("title")) },
                loop = j.optJSONObject("loop")?.let(::parseGoal) as? NavigationGoal.Roundtrip,
                adventure = j.optJSONObject("adventure")?.let(::parseGoal) as? NavigationGoal.Adventure
            )
            "roundtrip" -> NavigationGoal.Roundtrip(
                j.getDouble("distanceKm"), j.getDouble("directionDeg"), options, j.optInt("variant"), j.optString("name"),
                area = j.optString("area").takeIf { it.isNotEmpty() }?.let(SavedRoutes::decode),
                start = start,
                maxKm = if (j.has("maxKm")) j.getInt("maxKm") else null
            )
            "adventure" -> NavigationGoal.Adventure(
                j.getString("name"), parsePoint(j.getJSONObject("destination")), SavedRoutes.decode(j.getString("area")),
                if (j.has("minutes")) j.getInt("minutes") else null, options, j.optInt("variant"), start
            )
            else -> NavigationGoal.Track(j.getString("name"), SavedRoutes.decode(j.getString("points")), options)
        }
    }

    private fun optionsJson(o: RouteOptions) = JSONObject()
        .put("style", o.roadStyle.name).put("surface", o.surface.name)
        .put("ferries", o.avoidFerries).put("motorways", o.avoidMotorways).put("trunk", o.avoidTrunk).put("toll", o.avoidToll)
        .putOpt("gravelReachKm", o.gravelReachKm).put("gravelPull", o.gravelPull)

    private fun parseOptions(j: JSONObject) = RouteOptions(
        RoadStyle.valueOf(j.getString("style")), SurfacePreference.valueOf(j.getString("surface")),
        avoidFerries = j.optBoolean("ferries"), avoidMotorways = j.optBoolean("motorways"),
        avoidTrunk = j.optBoolean("trunk"), avoidToll = j.optBoolean("toll"),
        gravelReachKm = if (j.has("gravelReachKm")) j.getInt("gravelReachKm") else null,
        gravelPull = j.optInt("gravelPull", 100)
    )

    private fun pointJson(p: LatLon) = JSONObject().put("lat", p.lat).put("lon", p.lon)
    private fun parsePoint(j: JSONObject) = LatLon(j.getDouble("lat"), j.getDouble("lon"))
    private fun stopJson(s: Stop) = pointJson(s.point).put("name", s.name).put("via", s.via)
    private fun parseStop(j: JSONObject) = Stop(j.getString("name"), parsePoint(j), j.optBoolean("via"))
}
