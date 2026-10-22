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
                g?.takeIf { it.shared != null }
            }.distinctUntilChanged { a, b -> a?.shared == b?.shared && a?.destination == b?.destination && stopKeys(a) == stopKeys(b) }
                .debounce(800).collect { g -> if (g != null) published(g) }
        }
        navigator.onStopsPassed = { goal, stops ->
            goal.shared?.let { store.markPassed(it, stops.map { s -> s.toTrip().key }) }
        }
    }

    private fun stopKeys(g: NavigationGoal.Destination?) = g?.stops?.map { it.toTrip() }

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
        if (trip.doc.matches(passed, remaining, dest)) return
        val edited = trip.doc.edited(passed, remaining, dest)
        val rev = trip.rev + 1
        val mine = manager.myId.value ?: "me"
        Log.i("Curveon", "Shared trip $id changed here: ${edited.stops.size} stops, revision $rev")
        store.putOwn(SharedTrip(id, trip.groupId, trip.groupName, rev, mine, null, edited, true, trip.receivedAt))
        manager.shareTrip(id, rev, trip.doc.name, edited.toJson())
    }

    /** The group changed a trip: if the rider is riding or planning it, their route takes the change. */
    private fun applyToNavigator(trip: SharedTrip, byOthers: Boolean) {
        val s = navigator.state.value
        val g = (s.goal as? NavigationGoal.Destination) ?: (s.preview?.goal as? NavigationGoal.Destination) ?: return
        if (g.shared != trip.tripId) return
        val next = goalFor(trip, g.options)
        if (trip.doc.matches(store.passed(trip.tripId), g.stops.map { it.toTrip() }, TripStop(g.name, g.destination.lat, g.destination.lon))) return
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
        val doc = TripDoc(g.name, TripStop(g.name, g.destination.lat, g.destination.lon), g.stops.map { it.toTrip() }, SavedRoutes.optionsToJson(g.options))
        share(doc, groupId)?.let { navigator.tagShared(it) }
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
        val doc = TripDoc(g.name, TripStop(g.name, g.destination.lat, g.destination.lon), g.stops.map { it.toTrip() }, SavedRoutes.optionsToJson(g.options))
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
        val doc = TripDoc(item.title, stop(dest), stops, json.optJSONObject("options")?.toString() ?: "{}")
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
