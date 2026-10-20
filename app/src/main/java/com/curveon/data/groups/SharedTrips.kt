package com.curveon.data.groups

import android.content.Context
import com.curveon.groups.TripDoc
import com.curveon.protocol.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A trip shared with a group, as this device has it: the newest revision it has seen. [accepted] is false for a trip a
 * friend has just shared and the rider has not answered yet (see [SharedTripsController.offer]).
 */
class SharedTrip(
    val tripId: String, val groupId: String, val groupName: String?, val rev: Long, val from: String, val fromNickname: String?,
    val doc: TripDoc, val accepted: Boolean, val receivedAt: Long
)

/**
 * The shared trips kept on this device, in a file of their own (no database change): the trips, which stops this rider
 * has passed on each (a passed stop is dropped from their own route but stays in the shared one), and the trips the
 * rider chose to ignore, which are not offered again.
 */
class SharedTrips(context: Context) {
    private val file = File(context.filesDir, "shared_trips.json")
    private val passed = HashMap<String, MutableSet<String>>()
    private val ignored = HashSet<String>()
    private val _trips = MutableStateFlow(load())
    val trips: StateFlow<List<SharedTrip>> = _trips.asStateFlow()

    enum class Merged { NEW, UPDATED, STALE, IGNORED }

    fun latest(tripId: String): SharedTrip? = _trips.value.firstOrNull { it.tripId == tripId }

    /**
     * What came from the group: kept if it is newer than what is held (the highest revision wins), so every device ends on
     * the same one however the changes arrive. A trip someone else shared is NEW until the rider answers.
     */
    @Synchronized
    fun merge(m: Message.GroupTrip, groupName: String?, now: Long = System.currentTimeMillis()): Merged {
        if (m.tripId in ignored) return Merged.IGNORED
        val doc = TripDoc.fromJson(m.doc) ?: return Merged.STALE
        val current = latest(m.tripId)
        if (current != null && !TripDoc.isNewer(m.rev, m.from, current.rev, current.from)) return Merged.STALE
        val trip = SharedTrip(
            m.tripId, m.groupId, groupName ?: current?.groupName, m.rev, m.from, m.fromNickname ?: current?.fromNickname, doc,
            accepted = current?.accepted ?: m.mine, receivedAt = current?.receivedAt ?: now
        )
        put(trip)
        return if (current == null && !m.mine) Merged.NEW else Merged.UPDATED
    }

    /** This rider's own change (or a trip they share): kept as accepted. */
    @Synchronized
    fun putOwn(trip: SharedTrip) = put(trip)

    @Synchronized
    fun accept(tripId: String) = latest(tripId)?.let { put(SharedTrip(it.tripId, it.groupId, it.groupName, it.rev, it.from, it.fromNickname, it.doc, true, it.receivedAt)) }

    /** Not offered again, and removed from the list. */
    @Synchronized
    fun ignore(tripId: String) {
        ignored += tripId
        remove(tripId)
    }

    @Synchronized
    fun remove(tripId: String) {
        passed.remove(tripId)
        _trips.value = _trips.value.filterNot { it.tripId == tripId }
        save()
    }

    fun passed(tripId: String): Set<String> = passed[tripId].orEmpty()

    @Synchronized
    fun markPassed(tripId: String, keys: Collection<String>) {
        passed.getOrPut(tripId) { HashSet() }.addAll(keys)
        save()
    }

    private fun put(trip: SharedTrip) {
        _trips.value = (listOf(trip) + _trips.value.filterNot { it.tripId == trip.tripId }).sortedByDescending { it.receivedAt }
        save()
    }

    private fun save() {
        runCatching {
            val o = JSONObject()
                .put("trips", JSONArray(_trips.value.map {
                    JSONObject().put("id", it.tripId).put("group", it.groupId).put("gname", it.groupName ?: JSONObject.NULL).put("rev", it.rev)
                        .put("from", it.from).put("nick", it.fromNickname ?: JSONObject.NULL).put("doc", it.doc.toJson())
                        .put("accepted", it.accepted).put("at", it.receivedAt)
                }))
                .put("passed", JSONObject().apply { passed.forEach { (id, keys) -> put(id, JSONArray(keys.toList())) } })
                .put("ignored", JSONArray(ignored.toList()))
            file.writeText(o.toString())
        }
    }

    private fun load(): List<SharedTrip> = runCatching {
        val o = JSONObject(file.readText())
        o.optJSONObject("passed")?.let { p -> p.keys().forEach { id -> passed[id] = p.getJSONArray(id).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } } }
        o.optJSONArray("ignored")?.let { a -> (0 until a.length()).forEach { ignored += a.getString(it) } }
        val a = o.getJSONArray("trips")
        (0 until a.length()).mapNotNull { i ->
            val t = a.getJSONObject(i)
            val doc = TripDoc.fromJson(t.getString("doc")) ?: return@mapNotNull null
            SharedTrip(
                t.getString("id"), t.getString("group"), if (t.isNull("gname")) null else t.getString("gname"), t.getLong("rev"), t.getString("from"),
                if (t.isNull("nick")) null else t.getString("nick"), doc, t.optBoolean("accepted"), t.optLong("at")
            )
        }
    }.getOrDefault(emptyList())
}
