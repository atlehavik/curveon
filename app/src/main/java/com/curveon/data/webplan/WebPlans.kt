package com.curveon.data.webplan

import android.content.Context
import android.os.Build
import com.curveon.data.model.GeoPoint
import com.curveon.data.model.GpxTrack
import com.curveon.data.osm.GpxParser
import com.curveon.data.repository.CurveonRepository
import com.curveon.data.routing.SavedRoutes
import com.curveon.groups.GroupKeys
import com.curveon.groups.TransportFactory
import com.curveon.groups.WebPlan
import com.curveon.groups.WebPlanInbound
import com.curveon.groups.WebPlanInbox
import com.curveon.groups.WebPlanLink
import com.curveon.groups.WebPlanTrip
import com.curveon.phone.OkHttpTransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * A planning session with the web planner (relay.curveon.no/plan). Scanning the code the page shows ([add]) starts it:
 * from then on the page sees the planned trips on this device ([publish]: each trip, and their list) and can open and
 * change them, and what the page sends comes in among the planned trips: a new trip, a change of one of these (it
 * replaces the trip, which stays in its group under the rider's name for it), or a delete. A GPX track from the page
 * (a GPX file opened there) goes among the GPX tracks instead, as a file opened here would.
 *
 * Nothing stays connected in the background: [sync] is one short connection (app start, the planned trips opened, a
 * planned trip changed), and [live] keeps one open only while the rider has the planned trips on screen.
 */
class WebPlans(
    context: Context,
    private val repository: CurveonRepository,
    private val transports: TransportFactory = OkHttpTransportFactory(),
) {
    private val prefs = context.getSharedPreferences("webplan", Context.MODE_PRIVATE)
    private val lock = Mutex()

    /**
     * What a fetch did here: trips new, changed or deleted from the page, and GPX tracks kept among the GPX tracks.
     * Null from [sync]: no browser could be reached.
     */
    data class Result(val added: Int, val changed: Int, val deleted: Int = 0, val tracks: Int = 0) {
        val any get() = added + changed + deleted + tracks > 0
    }

    fun links(): List<WebPlanLink> = runCatching {
        val a = JSONArray(prefs.getString(KEY_LINKS, "[]"))
        (0 until a.length()).mapNotNull { WebPlanLink.parse(a.getString(it)) }
    }.getOrDefault(emptyList())

    /** Keeps the planner link [uri] (a scanned code); false when it is not one. Scanning the same code again is fine. */
    fun add(uri: String): Boolean {
        val link = WebPlanLink.parse(uri) ?: return false
        val all = links().filterNot { it == link } + link
        prefs.edit().putString(KEY_LINKS, JSONArray(all.map { it.toUri() }).toString()).apply()
        return true
    }

    /** Ends the sessions: this device stops talking to every browser; its trips stay as they are. */
    fun forgetAll() {
        val edit = prefs.edit().remove(KEY_LINKS)
        links().forEach { edit.remove(KEY_PUBLISHED + it.id) }
        edit.apply()
    }

    /** Fetches what every browser sent, and shows them this device's trips; null when no browser could be reached. */
    suspend fun sync(): Result? = withContext(Dispatchers.IO) {
        val links = links()
        if (links.isEmpty()) return@withContext Result(0, 0)
        var total: Result? = null
        for (link in links) {
            var r = Result(0, 0)
            val got = WebPlanInbox(transports).fetch(link) { inbound -> runBlocking { lock.withLock { handle(link, inbound).let { (res, frames) -> r = res; frames + publish(link) } } } }
            if (got != null) total = (total ?: Result(0, 0)).plus(r)
        }
        total
    }

    /** True when the page has not seen the planned trips as they are now (one changed since): worth a [sync]. */
    suspend fun needsPublish(): Boolean = lock.withLock {
        val planned = shown()
        links().any { link -> published(link) != planned.associate { it.id to it.rev } }
    }

    /**
     * Stays connected to every browser while the rider has the planned trips open: a trip from the page comes in at
     * once ([onResult]), and [WebPlanSession.publish] shows the page a change made here. Closed with [WebPlanSession.close].
     */
    fun live(onResult: (Result) -> Unit, onDropped: () -> Unit): WebPlanSession {
        val conns = links().map { link ->
            link to WebPlanInbox(transports).live(link, onInbound = { inbound ->
                runBlocking { lock.withLock { handle(link, inbound).let { (res, frames) -> onResult(res); frames + publish(link) } } }
            }, onClosed = onDropped)
        }
        return object : WebPlanSession {
            override suspend fun publish() = withContext(Dispatchers.IO) {
                conns.forEach { (link, live) -> lock.withLock { publish(link) }.forEach { live.send(it) } }
            }
            override fun close() = conns.forEach { it.second.close() }
        }
    }

    // ---------------------------------------------------------------- Coming in

    private operator fun Result.plus(o: Result) = Result(added + o.added, changed + o.changed, deleted + o.deleted, tracks + o.tracks)

    /** Keeps what the page sent and deletes what it asked to; the answers for the page. Called under [lock]. */
    private suspend fun handle(link: WebPlanLink, inbound: WebPlanInbound): Pair<Result, List<String>> {
        var added = 0; var changed = 0; var deleted = 0; var tracks = 0
        val frames = mutableListOf<String>()
        for (trip in inbound.trips) {
            // A change of another device's trip (the page updates each copy on its own): not this one's, unless the page
            // sent it here before. Kept anyway, it came in as a new trip beside the copy already here.
            if (trip.ref != null && trip.ref?.rider != me() && seen().optJSONObject(trip.tripId) == null) continue
            val (kept, id) = if (trip.destination == null) keepTrack(trip) else keep(trip)
            when (kept) { Kept.NEW -> added++; Kept.CHANGED -> changed++; Kept.TRACK -> tracks++; Kept.SAME -> Unit }
            frames += WebPlan.gotFrame(link, riderId(), nextSeq(), trip.tripId, trip.rev, deviceName(), System.currentTimeMillis(), id)
        }
        for (ref in inbound.deletes) {
            if (ref.rider != me()) continue
            val route = repository.route(ref.id) ?: continue
            if (!route.inPlanned) continue
            repository.removeFromPlanned(route)
            deleted++
        }
        return Result(added, changed, deleted, tracks) to frames
    }

    private enum class Kept { NEW, CHANGED, TRACK, SAME }

    private suspend fun keep(trip: WebPlanTrip): Pair<Kept, Long?> {
        val seen = seen()
        val before = seen.optJSONObject(trip.tripId)
        if (before != null && before.optLong("rev") >= trip.rev) return Kept.SAME to before.optLong("id")
        // A change of one of this device's trips (opened on the page from its list), or of a trip the page sent before.
        val oldId = trip.ref?.takeIf { it.rider == me() }?.id ?: before?.optLong("id")
        val old = oldId?.let { repository.route(it) }
        val item = SavedRoutes.webItem(trip).let { fresh ->
            if (old == null) fresh
            else fresh.copy(
                id = old.id, groupId = old.groupId, inWhereTo = old.inWhereTo,
                // Renamed here since the page last saw it: the rider's name stays.
                title = if (before != null && old.title != before.optString("title")) old.title else fresh.title
            )
        }
        val id = repository.saveRoute(item)
        seen.put(trip.tripId, JSONObject().put("rev", trip.rev).put("id", id).put("title", item.title))
        prefs.edit().putString(KEY_SEEN, seen.toString()).apply()
        return (if (old != null) Kept.CHANGED else Kept.NEW) to id
    }

    /**
     * A GPX track from the page, among the GPX tracks: sent again (changed there), or one of the same name already here
     * (the same file opened again), it replaces that one and keeps how the rider shows it. The tracks of one file go in
     * its group. Not one of the planned trips, so the page gets no id for it.
     */
    private suspend fun keepTrack(trip: WebPlanTrip): Pair<Kept, Long?> {
        val seen = seen()
        val before = seen.optJSONObject(trip.tripId)
        if (before != null && before.optLong("rev") >= trip.rev) return Kept.SAME to null
        val points = SavedRoutes.decode(trip.line).map { GeoPoint(it.lat, it.lon) }
        if (points.size < 2) return Kept.SAME to null
        val name = trip.name.ifBlank { "GPX" }
        val old = before?.optLong("gpx")?.takeIf { it > 0 }?.let { repository.gpxTrack(it) } ?: repository.findGpxTrack(name)
        val id = repository.saveGpxTrack(
            GpxTrack(
                id = old?.id ?: 0,
                showOnMap = old?.showOnMap ?: false, color = old?.color ?: 0, showPois = old?.showPois ?: true, reversed = old?.reversed ?: false,
                groupId = old?.groupId ?: trip.group?.let { repository.gpxGroupNamed(it).id },
                // Renamed here since the page sent it: the rider's name stays.
                name = if (old != null && before != null && old.name != before.optString("title")) old.name else name,
                totalDistanceKm = (trip.distanceMeters / 1000).toFloat(),
                elevationGainMeters = trip.ascentMeters.toInt(),
                maxElevationMeters = 0,
                pointCount = points.size,
                trackPointsJson = GpxParser.encodePoints(points),
                category = "Track",
            )
        )
        seen.put(trip.tripId, JSONObject().put("rev", trip.rev).put("gpx", id).put("title", name))
        prefs.edit().putString(KEY_SEEN, seen.toString()).apply()
        return Kept.TRACK to null
    }

    private fun seen(): JSONObject = runCatching { JSONObject(prefs.getString(KEY_SEEN, "{}")!!) }.getOrDefault(JSONObject())

    // ---------------------------------------------------------------- Going out

    /** The planned trips the page may see: the newest, as many as the relay keeps for one device. */
    private suspend fun shown(): List<com.curveon.groups.WebPlanHave> {
        val groups = repository.routeGroupsNow().associate { it.id to it.name }
        return repository.plannedNow().take(MAX_SHOWN).map { SavedRoutes.webHave(it, it.groupId?.let(groups::get)) }
    }

    /** What changed since the page last saw this device's trips, as frames: each changed trip, gone ones, the list. */
    private suspend fun publish(link: WebPlanLink): List<String> {
        val now = System.currentTimeMillis()
        val trips = shown()
        val before = published(link)
        val after = trips.associate { it.id to it.rev }
        if (before == after) return emptyList()
        val frames = trips.filter { before[it.id] != it.rev }.map { WebPlan.haveFrame(link, riderId(), nextSeq(), it, now) } +
            (before.keys - after.keys).map { WebPlan.dropHaveFrame(link, riderId(), nextSeq(), it, now) } +
            WebPlan.indexFrame(link, riderId(), nextSeq(), deviceName(), trips.map { it.id }, now)
        prefs.edit().putString(KEY_PUBLISHED + link.id, JSONObject(after.mapKeys { it.key.toString() }).toString()).apply()
        return frames
    }

    private fun published(link: WebPlanLink): Map<Long, String> = runCatching {
        val o = JSONObject(prefs.getString(KEY_PUBLISHED + link.id, "{}")!!)
        o.keys().asSequence().associate { it.toLong() to o.getString(it) }
    }.getOrDefault(emptyMap())

    /** This app's id on the planner channels: random, made once. */
    private fun riderId(): ByteArray {
        prefs.getString(KEY_RIDER, null)?.let { return Base64.getUrlDecoder().decode(it) }
        return GroupKeys.newRiderId().also { prefs.edit().putString(KEY_RIDER, Base64.getUrlEncoder().withoutPadding().encodeToString(it)).apply() }
    }

    private fun me(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(riderId())

    private fun nextSeq(): Long = (prefs.getLong(KEY_SEQ, 0) + 1).also { prefs.edit().putLong(KEY_SEQ, it).apply() }

    /** Shown on the planner page over this device's trips ("Pixel 9a"). */
    private fun deviceName() = Build.MODEL.takeIf { it.isNotBlank() } ?: "Android"

    private companion object {
        const val KEY_LINKS = "links"
        const val KEY_SEEN = "seen"
        const val KEY_RIDER = "rider"
        const val KEY_SEQ = "seq"
        const val KEY_PUBLISHED = "published:"
        /** The relay keeps 64 slots and 2 MB a device: the list, and this many trips of some 10–30 kB each. */
        const val MAX_SHOWN = 60
    }
}

/** A session kept open while the planned trips are on screen ([WebPlans.live]). */
interface WebPlanSession {
    /** Shows the page what changed here (a trip saved, renamed, deleted). */
    suspend fun publish()
    fun close()
}
