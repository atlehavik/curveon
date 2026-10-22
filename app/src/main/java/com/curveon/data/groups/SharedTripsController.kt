package com.curveon.data.groups

import android.content.Context
import android.util.Log
import com.curveon.data.model.RouteItem
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.Navigator
import com.curveon.data.routing.SavedRoutes
import com.curveon.data.routing.Stop
import com.curveon.groups.TripDoc
import com.curveon.groups.TripStop
import com.curveon.routing.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * Shared trips between the riders of a group. A trip shared with the group is one document that anyone can change: a
 * stop added by one rider is added to everyone's route at once, and the highest revision wins, so all end on the same
 * trip. Here that is tied to the navigator: this rider's changes to a shared trip are sent, and the group's are ridden.
 */
@OptIn(FlowPreview::class)
class SharedTripsController(
    context: Context,
    private val scope: CoroutineScope,
    private val navigator: Navigator,
    private val manager: GroupManager
) {
    val store = SharedTrips(context)

    /** The shared trips the rider has accepted: the list under "Shared routes". */
    val shared: StateFlow<List<SharedTrip>> = store.trips.map { list -> list.filter { it.accepted } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** A trip someone has just shared and the rider has not answered: Ride now, Save in shared, or Ignore. */
    val offer: StateFlow<SharedTrip?> = store.trips.map { list -> list.firstOrNull { !it.accepted } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _justChanged = MutableStateFlow<SharedTrip?>(null)

    /** A shared trip the rider is riding or planning was changed by someone else just now (for a short message). */
    val justChanged: StateFlow<SharedTrip?> = _justChanged.asStateFlow()

    /** What a change applied from the group should look like here, until the navigator has taken it (so it is not sent back). */
    private val applying = HashMap<String, Pair<String, Long>>()

    /** The line of each trip this rider rides now (theirs or the group's), so the same line is not taken twice. */
    private val lineTaken = HashMap<String, String?>()

    init {
        // What the group sends.
        scope.launch {
            manager.trips.collect { m ->
                val groupName = manager.groups.value.firstOrNull { it.id == m.groupId }?.name
                when (store.merge(m, groupName)) {
                    SharedTrips.Merged.UPDATED -> store.latest(m.tripId)?.let { if (it.accepted) applyToNavigator(it, byOthers = !m.mine) }
                    else -> Unit
                }
            }
        }
        // What this rider changes: a shared trip whose stops are no longer what the group has.
        scope.launch {
            navigator.state.map { s ->
                val g = (s.goal as? NavigationGoal.Destination) ?: (s.preview?.goal as? NavigationGoal.Destination)
                g?.takeIf { it.shared != null }?.let { Triple(it, routeOf(s, it), styleOf(s, it)) }
            }.distinctUntilChanged { a, b ->
                a?.first?.shared == b?.first?.shared && a?.first?.destination == b?.first?.destination &&
                    stopKeys(a?.first) == stopKeys(b?.first) && a?.second === b?.second && a?.third == b?.third
            }.debounce(800).collect { t -> if (t != null) published(t.first) }
        }
        navigator.onStopsPassed = { goal, stops ->
            goal.shared?.let { store.markPassed(it, stops.map { s -> s.toTrip().key }) }
        }
    }

    private fun stopKeys(g: NavigationGoal.Destination?) = g?.stops?.map { it.toTrip() }

    /** The route planned for [g]: being ridden, or picked in the preview. */
    private fun routeOf(s: com.curveon.data.routing.NavigationState, g: NavigationGoal.Destination): com.curveon.routing.Route? =
        if (s.goal === g) s.route else s.preview?.takeIf { it.goal === g }?.selectedRoute

    /** The road style [g] is ridden in: the one picked in the preview, or the trip's own. */
    private fun styleOf(s: com.curveon.data.routing.NavigationState, g: NavigationGoal.Destination): com.curveon.routing.RoadStyle {
        val p = s.preview?.takeIf { it.goal === g } ?: return g.options.roadStyle
        return p.candidates.getOrNull(p.selected)?.style ?: g.options.roadStyle
    }

    /** [g]'s options with the style it is ridden in, as the trip's JSON. */
    private fun optionsOf(s: com.curveon.data.routing.NavigationState, g: NavigationGoal.Destination) =
        SavedRoutes.optionsToJson(g.options.copy(roadStyle = styleOf(s, g)))

    /**
     * The road [g] takes, for the others to ride too: only when it is planned and goes by every stop left (just after a
     * change the old route is still there). Smoothed so a long trip stays small.
     */
    private fun lineOf(s: com.curveon.data.routing.NavigationState, g: NavigationGoal.Destination): String? {
        val pts = routeOf(s, g)?.points?.map { LatLon(it.lat, it.lon) }?.takeIf { it.size >= 2 } ?: return null
        val places = g.stops.map { it.point } + g.destination
        if (places.any { p -> distanceTo(p, pts) > 120 }) return null
        return SavedRoutes.encode(simplify(pts, 5.0))
    }

    /** The rider's own route to a shared trip changed: if it is not what the group has, send the new trip. */
    private fun published(g: NavigationGoal.Destination) {
        val id = g.shared ?: return
        val trip = store.latest(id) ?: return
        val remaining = g.stops.map { it.toTrip() }
        val dest = TripStop(g.name, g.destination.lat, g.destination.lon)
        val passed = store.passed(id)
        applying[id]?.let { (_, until) ->
            // A change from the group is on its way into the route: until it is there, the route is not the rider's own change.
            if (System.currentTimeMillis() < until && !trip.doc.matches(passed, remaining, dest)) return
            applying.remove(id)
        }
        val s = navigator.state.value
        val options = optionsOf(s, g)
        val mine = manager.myId.value ?: "me"
        val sameStops = trip.doc.matches(passed, remaining, dest)
        val newStyle = SavedRoutes.optionsFromJson(options).roadStyle != SavedRoutes.optionsFromJson(trip.doc.options).roadStyle
        val line = lineOf(s, g)
        // Riding it as it is: nothing to send, unless the rider picked another style, or their own change gets its road.
        if (sameStops && !newStyle && !(trip.from == mine && trip.doc.line == null && line != null)) return
        if (!sameStops || newStyle) applying.remove(id)
        val edited = trip.doc.edited(passed, remaining, dest, options, line)
        lineTaken[id] = edited.line
        val rev = trip.rev + 1
        Log.i("Curveon", "Shared trip $id changed here: ${edited.stops.size} stops, revision $rev")
        store.putOwn(SharedTrip(id, trip.groupId, trip.groupName, rev, mine, null, edited, true, trip.receivedAt))
        manager.shareTrip(id, rev, trip.doc.name, edited.toJson())
    }

    /** The group changed a trip: if the rider is riding or planning it, their route takes the change. */
    private fun applyToNavigator(trip: SharedTrip, byOthers: Boolean) {
        val s = navigator.state.value
        val g = (s.goal as? NavigationGoal.Destination) ?: (s.preview?.goal as? NavigationGoal.Destination) ?: return
        if (g.shared != trip.tripId) return
        // With a line the trip's own style comes along too; without, the rider keeps their own.
        val next = goalFor(trip, if (trip.doc.line != null) null else g.options)
        val sameStops = trip.doc.matches(store.passed(trip.tripId), g.stops.map { it.toTrip() }, TripStop(g.name, g.destination.lat, g.destination.lon))
        if (sameStops && (trip.doc.line == null || lineTaken[trip.tripId] == trip.doc.line)) return
        lineTaken[trip.tripId] = trip.doc.line
        applying[trip.tripId] = trip.doc.name to System.currentTimeMillis() + 30_000
        if (byOthers) _justChanged.value = trip
        navigator.adoptSharedTrip(next)
    }

    /** The trip as this rider rides it: the stops they have not passed, to where it ends. */
    fun goalFor(trip: SharedTrip, options: com.curveon.routing.RouteOptions? = null): NavigationGoal.Destination {
        val d = trip.doc
        return NavigationGoal.Destination(
            d.destination.name, LatLon(d.destination.lat, d.destination.lon), options ?: SavedRoutes.optionsFromJson(d.options),
            d.remaining(store.passed(trip.tripId)).map { it.toStop() },
            // The road whoever planned it took, so everyone rides the same one (not each their own style).
            fixedLine = d.line?.let(SavedRoutes::decode)?.takeIf { it.size >= 2 },
            // One a friend sent is the rider's own copy: their changes stay with them (nor go to a group).
            shared = trip.tripId.takeIf { !fromFriend(trip) }
        )
    }

    /** Sent by a friend rather than shared in a group: it came through the channel between the two of you. */
    fun fromFriend(trip: SharedTrip) = manager.friends.value.any { it.id == trip.groupId }

    // ---- what the rider does ----

    /** Ride (or look at) a shared trip: it opens in the preview, and from then on its changes go both ways. */
    fun open(trip: SharedTrip) {
        store.accept(trip.tripId)
        navigator.previewPlanned(goalFor(trip))
    }

    fun accept(trip: SharedTrip) = store.accept(trip.tripId)

    /** Not wanted: gone, and not offered again. */
    fun ignore(trip: SharedTrip) = store.ignore(trip.tripId)

    /** Take it out of the list of shared routes (a new share of it would be offered again unless it was ignored). */
    fun remove(trip: SharedTrip) = store.remove(trip.tripId)

    fun clearJustChanged() { _justChanged.value = null }

    /** Share the trip in the preview or being ridden with the active group, and from now on keep it in step with the group's. */
    fun shareCurrent(): Boolean {
        val s = navigator.state.value
        val g = (s.goal as? NavigationGoal.Destination) ?: (s.preview?.goal as? NavigationGoal.Destination) ?: return false
        val groupId = manager.activeId.value ?: return false
        if (g.shared != null) return true
        val doc = TripDoc(g.name, TripStop(g.name, g.destination.lat, g.destination.lon), g.stops.map { it.toTrip() }, optionsOf(s, g), lineOf(s, g))
        share(doc, groupId)?.let { id -> lineTaken[id] = doc.line; navigator.tagShared(id) }
        return true
    }

    /**
     * Send a copy of the trip on screen to some friends ([friendIds], channel ids): the one in the preview when [preview],
     * else the one being ridden. Each friend gets it to ride or keep; nothing about it is kept in step afterwards.
     */
    fun sendToFriends(friendIds: List<String>, preview: Boolean): Boolean {
        if (friendIds.isEmpty()) return false
        val s = navigator.state.value
        val g = (if (preview) s.preview?.goal else s.goal) as? NavigationGoal.Destination ?: return false
        val doc = TripDoc(g.name, TripStop(g.name, g.destination.lat, g.destination.lon), g.stops.map { it.toTrip() }, optionsOf(s, g), lineOf(s, g))
        manager.sendTripToFriends(UUID.randomUUID().toString(), doc.name, doc.toJson(), friendIds)
        return true
    }

    /** Share a planned trip (one with stops, not a track) with the active group. */
    fun shareSaved(item: RouteItem): Boolean {
        val groupId = manager.activeId.value ?: return false
        val json = runCatching { JSONObject(item.waypointsJson) }.getOrNull() ?: return false
        val dest = json.optJSONObject("destination") ?: return false // a track or loop has no stops to change together
        fun stop(o: JSONObject) = TripStop(o.optString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optBoolean("via"))
        val stops = json.optJSONArray("stops")?.let { a -> (0 until a.length()).map { stop(a.getJSONObject(it)) } }.orEmpty()
        val doc = TripDoc(item.title, stop(dest), stops, json.optJSONObject("options")?.toString() ?: "{}", item.polylineJson.takeIf { it.isNotEmpty() })
        share(doc, groupId)
        return true
    }

    private fun share(doc: TripDoc, groupId: String): String? {
        val id = UUID.randomUUID().toString()
        val groupName = manager.groups.value.firstOrNull { it.id == groupId }?.name
        store.putOwn(SharedTrip(id, groupId, groupName, 1, manager.myId.value ?: "me", null, doc, true, System.currentTimeMillis()))
        manager.shareTrip(id, 1, doc.name, doc.toJson())
        return id
    }
}

private fun Stop.toTrip() = TripStop(name, point.lat, point.lon, via)
private fun TripStop.toStop() = Stop(name, LatLon(lat, lon), via)

/** How far [p] is from the nearest part of [line], in metres (flat, which is close enough over a few hundred metres). */
private fun distanceTo(p: LatLon, line: List<LatLon>): Double {
    var best = Double.MAX_VALUE
    for (i in 0 until line.size - 1) best = minOf(best, segment(p, line[i], line[i + 1]))
    return best
}

private fun segment(p: LatLon, a: LatLon, b: LatLon): Double {
    val kx = Math.cos(Math.toRadians(p.lat)) * 111_320.0
    val ky = 110_540.0
    val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
    val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
    val dx = bx - ax; val dy = by - ay
    val len = dx * dx + dy * dy
    val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
    return Math.hypot(ax + t * dx, ay + t * dy)
}

/** [pts] with the points left out that are within [tolerance] metres of a straight line between their neighbours kept. */
private fun simplify(pts: List<LatLon>, tolerance: Double): List<LatLon> {
    if (pts.size < 3) return pts
    val keep = BooleanArray(pts.size).also { it[0] = true; it[pts.lastIndex] = true }
    val todo = ArrayDeque<Pair<Int, Int>>().apply { add(0 to pts.lastIndex) }
    while (todo.isNotEmpty()) {
        val (a, b) = todo.removeLast()
        var worst = -1; var far = tolerance
        for (i in a + 1 until b) { val d = segment(pts[i], pts[a], pts[b]); if (d > far) { far = d; worst = i } }
        if (worst > 0) { keep[worst] = true; todo.add(a to worst); todo.add(worst to b) }
    }
    return pts.filterIndexed { i, _ -> keep[i] }
}
