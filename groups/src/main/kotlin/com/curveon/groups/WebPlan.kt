package com.curveon.groups

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The web planner's own channel to a rider's apps (docs/groups-protocol.md, "Web planner"). The planner page makes the
 * keys and shows them as a QR code ([toUri]); every app that scans it reads the trips sent there. It is a channel of its
 * own, never a group: scanning it can not change the group the rider rides with. Nobody holds an admin key, so it is
 * never rekeyed; [adminPublicKey] is only what the relay fixes the channel to.
 */
class WebPlanLink(val groupId: ByteArray, val groupKey: ByteArray, val adminPublicKey: ByteArray, val relayHost: String) {
  init {
    require(groupId.size == Invite.GROUP_ID_SIZE && groupKey.size == Invite.GROUP_KEY_SIZE && adminPublicKey.size == 32) { "bad key sizes" }
    require(relayHost.isNotBlank() && relayHost.none { it.isWhitespace() || it == '/' || it == '#' }) { "bad relay host" }
  }

  val keys: GroupKeys get() = GroupKeys(groupId, groupKey)
  val groupIdHex: String get() = groupId.joinToString("") { "%02x".format(it) }
  /** Short and stable, to tell two planner links apart. */
  val id: String get() = groupIdHex.take(12)

  fun relayUrl(scheme: String = "wss") =
    "$scheme://$relayHost/g/$groupIdHex?p=${keys.accessProof()}&a=${B64.encode(adminPublicKey)}&pv=${Wire.REVISION}"

  fun toUri(): String = "curveon://webplan#v=$VERSION&g=${B64.encode(groupId)}&k=${B64.encode(groupKey)}" +
    "&a=${B64.encode(adminPublicKey)}&r=${URLEncoder.encode(relayHost, "UTF-8")}"

  override fun equals(other: Any?) = other is WebPlanLink && other.groupId.contentEquals(groupId) && other.groupKey.contentEquals(groupKey)
  override fun hashCode() = groupId.contentHashCode()

  companion object {
    const val VERSION = 1

    fun isLink(text: String) = text.startsWith("curveon://webplan#")

    /** Null for anything that is not a planner link of a version this build understands. */
    fun parse(uri: String): WebPlanLink? = try {
      if (!isLink(uri)) null
      else {
        val p = uri.substringAfter('#').split('&').filter { it.isNotEmpty() }
          .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        if (p["v"]?.toIntOrNull() != VERSION) null
        else WebPlanLink(B64.decode(p.getValue("g")), B64.decode(p.getValue("k")), B64.decode(p.getValue("a")), p.getValue("r"))
      }
    } catch (_: Exception) {
      null
    }
  }
}

/**
 * A trip planned on the web, as the planner worked it out: the stops, how to ride, and [line], the road itself
 * (`lat,lon;lat,lon;…`, the app's saved-route format), so the app rides exactly what was planned.
 * The same [tripId] with a higher [rev] is the same trip changed.
 */
class WebPlanTrip(
  val tripId: String, val rev: Long, val name: String, val start: TripStop?, val destination: TripStop, val stops: List<TripStop>,
  /** Road style, surface and what to avoid: the JSON the app keeps with a saved route. */
  val options: String, val line: String, val distanceMeters: Double, val durationSeconds: Double, val ascentMeters: Double,
  val curviness: Int, val sentAt: Long,
) {
  fun toJson(): JSONObject = JSONObject().put("v", 1).put("name", name)
    .apply { start?.let { put("start", stop(it)) } }
    .put("destination", stop(destination)).put("stops", JSONArray(stops.map(::stop)))
    .put("options", JSONObject(options)).put("line", line)
    .put("m", distanceMeters).put("s", durationSeconds).put("up", ascentMeters).put("curvy", curviness)

  companion object {
    private fun stop(s: TripStop) = JSONObject().put("name", s.name).put("lat", s.lat).put("lon", s.lon).apply { if (s.via) put("via", true) }
    private fun stopOf(o: JSONObject) = TripStop(o.optString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optBoolean("via"))

    fun fromJson(tripId: String, rev: Long, sentAt: Long, o: JSONObject): WebPlanTrip? {
      if (o.optInt("v") != 1) return null
      val line = o.getString("line")
      if (line.count { it == ';' } < 1) return null
      val stops = o.optJSONArray("stops")?.let { a -> (0 until a.length()).map { stopOf(a.getJSONObject(it)) } }.orEmpty()
      return WebPlanTrip(
        tripId, rev, o.optString("name"), o.optJSONObject("start")?.let(::stopOf), stopOf(o.getJSONObject("destination")), stops,
        (o.optJSONObject("options") ?: JSONObject()).toString(), line, o.optDouble("m", 0.0), o.optDouble("s", 0.0),
        o.optDouble("up", 0.0), o.optInt("curvy", 0), sentAt,
      )
    }
  }
}

/** Sealing and opening the frames of a planner channel. Each kind is kept by the relay under the trip's id, 30 days. */
object WebPlan {
  /** As long as the relay keeps a frame (its MAX_KEEP_S); a trip not fetched by then is gone. */
  const val KEEP_SECONDS = 30 * 86_400

  fun planFrame(link: WebPlanLink, riderId: ByteArray, seq: Long, trip: WebPlanTrip): String {
    val body = JSONObject().put("seq", seq).put("ts", trip.sentAt).put("tid", trip.tripId).put("rev", trip.rev).put("trip", trip.toJson())
    return sealed(link, FrameType.PLAN, riderId, body, trip.tripId)
  }

  /** The app's word that it has [tripId] at [rev], shown on the planner page next to the trip. */
  fun gotFrame(link: WebPlanLink, riderId: ByteArray, seq: Long, tripId: String, rev: Long, device: String, now: Long): String {
    val body = JSONObject().put("seq", seq).put("ts", now).put("tid", tripId).put("rev", rev).put("dev", device)
    return sealed(link, FrameType.PLAN_GOT, riderId, body, tripId)
  }

  /** A trip from [text], or null for anything else (another type, another key, altered, malformed). */
  fun openPlan(link: WebPlanLink, text: String): WebPlanTrip? = open(link, text, FrameType.PLAN)?.let {
    // A trip the page took back comes as `gone` without one: nothing to fetch.
    runCatching { WebPlanTrip.fromJson(it.getString("tid"), it.getLong("rev"), it.optLong("ts"), it.getJSONObject("trip")) }.getOrNull()
  }

  private fun sealed(link: WebPlanLink, type: FrameType, riderId: ByteArray, body: JSONObject, tripId: String): String {
    val f = FrameCrypto.seal(link.keys, type, riderId, body.toString().toByteArray())
    // Slot: the trip id, so a changed trip replaces the one kept before it.
    return JSONObject(Wire.encode(f)).put("k", slot(tripId)).put("x", KEEP_SECONDS).toString()
  }

  private fun open(link: WebPlanLink, text: String, type: FrameType): JSONObject? = try {
    val f = Wire.decode(text)
    if (f == null || f.type != type) null
    else FrameCrypto.open(link.keys, f)?.let { JSONObject(String(it)) }
  } catch (_: Exception) {
    null
  }

  /** Relay slots are base64url; trip ids from the page are already, but keep them in shape. */
  internal fun slot(tripId: String) = tripId.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }.take(43).ifEmpty { "t" }
}

/**
 * Fetches what the planner sent: one short connection, read until the relay has replayed what it keeps (nothing more
 * comes for [quietMs]), then closed. Nothing stays connected, so it costs the radio a moment, not a socket.
 */
class WebPlanInbox(private val transports: TransportFactory, private val scheme: String = "wss") {

  /**
   * Every trip kept on [link]'s channel, newest revision of each; null when the relay could not be reached. [answer]
   * gets them before the connection closes and returns frames to send back (e.g. [WebPlan.gotFrame]).
   */
  fun fetch(link: WebPlanLink, quietMs: Long = 1500, maxMs: Long = 10_000, answer: (List<WebPlanTrip>) -> List<String> = { emptyList() }): List<WebPlanTrip>? {
    val trips = LinkedHashMap<String, WebPlanTrip>()
    val lock = Object()
    var opened = false
    var last = System.currentTimeMillis()
    val closed = CountDownLatch(1)
    val t = transports.open(link.relayUrl(scheme), object : TransportListener {
      override fun onOpen() { synchronized(lock) { opened = true; last = System.currentTimeMillis() } }
      override fun onMessage(text: String) {
        val trip = WebPlan.openPlan(link, text)
        synchronized(lock) {
          last = System.currentTimeMillis()
          val had = trip?.let { trips[it.tripId] }
          if (trip != null && (had == null || trip.rev > had.rev)) trips[trip.tripId] = trip
        }
      }
      override fun onClosed(code: Int) = closed.countDown()
    })
    val end = System.currentTimeMillis() + maxMs
    while (System.currentTimeMillis() < end) {
      if (closed.await(100, TimeUnit.MILLISECONDS)) break
      if (synchronized(lock) { opened && System.currentTimeMillis() - last >= quietMs }) break
    }
    val got = synchronized(lock) { if (opened) trips.values.toList() else null }
    if (got != null && closed.count > 0) answer(got).forEach { t.send(it) }
    t.close()
    return got
  }
}
