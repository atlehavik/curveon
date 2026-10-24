package com.curveon.data.webplan

import android.content.Context
import android.os.Build
import com.curveon.data.repository.CurveonRepository
import com.curveon.data.routing.SavedRoutes
import com.curveon.groups.GroupKeys
import com.curveon.groups.TransportFactory
import com.curveon.groups.WebPlan
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
 * Trips planned on the web planner (relay.curveon.no/plan), fetched into the planned trips. The rider connects a
 * browser once by scanning the code the page shows ([add]); after that [sync] fetches what was sent there, on one short
 * connection per browser, when the app starts and when the planned trips are opened. Nothing stays connected.
 *
 * A trip sent again after a change on the page replaces the one fetched before; one the rider has deleted here comes
 * back only if it was changed on the page after that.
 */
class WebPlans(
    context: Context,
    private val repository: CurveonRepository,
    private val transports: TransportFactory = OkHttpTransportFactory(),
) {
    private val prefs = context.getSharedPreferences("webplan", Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** What a fetch found: trips new or changed here, or null when no browser could be reached. */
    data class Result(val added: Int, val changed: Int)

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

    /** Stops fetching from every browser; trips already fetched stay. */
    fun forgetAll() = prefs.edit().remove(KEY_LINKS).apply()

    /** When the last fetch reached the relay (ms), or 0. */
    val lastFetch: Long get() = prefs.getLong(KEY_LAST, 0)

    suspend fun sync(): Result? = lock.withLock {
        withContext(Dispatchers.IO) {
            val links = links()
            if (links.isEmpty()) return@withContext Result(0, 0)
            var reached = false
            var added = 0
            var changed = 0
            for (link in links) {
                val inbox = WebPlanInbox(transports)
                val got = inbox.fetch(link) { trips ->
                    // On the inbox's thread, before it closes: keep each trip, then tell the page which arrived.
                    trips.mapNotNull { trip ->
                        when (runBlocking { keep(trip) }) {
                            Kept.NEW -> added++
                            Kept.CHANGED -> changed++
                            Kept.SAME -> Unit
                        }
                        WebPlan.gotFrame(link, riderId(), nextSeq(), trip.tripId, trip.rev, deviceName(), System.currentTimeMillis())
                    }
                }
                if (got != null) reached = true
            }
            if (reached) prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            if (reached) Result(added, changed) else null
        }
    }

    private enum class Kept { NEW, CHANGED, SAME }

    private suspend fun keep(trip: WebPlanTrip): Kept {
        val seen = seen()
        val before = seen.optJSONObject(trip.tripId)
        if (before != null && before.optLong("rev") >= trip.rev) return Kept.SAME
        val old = before?.optLong("id")?.let { repository.route(it) }
        val item = SavedRoutes.webItem(trip).let { fresh ->
            // Changed on the page: the trip here takes the new version, staying where the rider put it.
            if (old == null) fresh
            else fresh.copy(
                id = old.id, groupId = old.groupId, inWhereTo = old.inWhereTo,
                // Renamed here since it was fetched: the rider's name stays.
                title = if (old.title != before?.optString("title")) old.title else fresh.title
            )
        }
        val id = repository.saveRoute(item)
        seen.put(trip.tripId, JSONObject().put("rev", trip.rev).put("id", id).put("title", item.title))
        prefs.edit().putString(KEY_SEEN, seen.toString()).apply()
        return if (old != null) Kept.CHANGED else Kept.NEW
    }

    private fun seen(): JSONObject = runCatching { JSONObject(prefs.getString(KEY_SEEN, "{}")!!) }.getOrDefault(JSONObject())

    /** This app's id on the planner channels: random, made once. */
    private fun riderId(): ByteArray {
        prefs.getString(KEY_RIDER, null)?.let { return Base64.getUrlDecoder().decode(it) }
        return GroupKeys.newRiderId().also { prefs.edit().putString(KEY_RIDER, Base64.getUrlEncoder().withoutPadding().encodeToString(it)).apply() }
    }

    private fun nextSeq(): Long = (prefs.getLong(KEY_SEQ, 0) + 1).also { prefs.edit().putLong(KEY_SEQ, it).apply() }

    /** Shown on the planner page next to the trip ("In Curveon on Pixel 9a"). */
    private fun deviceName() = Build.MODEL.takeIf { it.isNotBlank() } ?: "Android"

    private companion object {
        const val KEY_LINKS = "links"
        const val KEY_SEEN = "seen"
        const val KEY_RIDER = "rider"
        const val KEY_SEQ = "seq"
        const val KEY_LAST = "last"
    }
}
