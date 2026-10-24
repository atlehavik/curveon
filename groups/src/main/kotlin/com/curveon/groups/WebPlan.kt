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

/** A trip on one of the rider's devices: [rider] is that app's id on the channel (base64url), [id] its own number for the trip. */
data class WebPlanRef(val rider: String, val id: Long) {
  fun toJson(): JSONObject = JSONObject().put("r", rider).put("id", id)

  companion object {
    fun of(o: JSONObject?): WebPlanRef? = o?.let { runCatching { WebPlanRef(it.getString("r"), it.getLong("id")) }.getOrNull() }
  }
}

/**
 * A trip planned on the web, as the planner worked it out: the stops, how to ride, and [line], the road itself
 * (`lat,lon;lat,lon;…`, the app's saved-route format), so the app rides exactly what was planned.
 * The same [tripId] with a higher [rev] is the same trip changed. [ref]: the trip on a device it is a change of
 * (opened on the page from that device's list); that device changes its trip, the others get it as a new one.
 * Without a [destination] it is a track (`track: true`, e.g. a GPX file opened on the page): the line is ridden as it
 * is, with no stops. An app older than that drops it unread.
 */
class WebPlanTrip(
  val tripId: String, val rev: Long, val name: String, val start: TripStop?, val destination: TripStop?, val stops: List<TripStop>,
  /** Road style, surface and what to avoid: the JSON the app keeps with a saved route. */
  val options: String, val line: String, val distanceMeters: Double, val durationSeconds: Double, val ascentMeters: Double,
  val curviness: Int, val sentAt: Long, val ref: WebPlanRef? = null,
) {
  fun toJson(): JSONObject = JSONObject().put("v", 1).put("name", name)
    .apply { start?.let { put("start", stop(it)) } }
    .apply { if (destination != null) put("destination", stop(destination)) else put("track", true) }
    .put("stops", JSONArray(stops.map(::stop)))
    .put("options", JSONObject(options)).put("line", line)
    .put("m", distanceMeters).put("s", durationSeconds).put("up", ascentMeters).put("curvy", curviness)
    .apply { ref?.let { put("ref", it.toJson()) } }

  companion object {
    internal fun stop(s: TripStop) = JSONObject().put("name", s.name).put("lat", s.lat).put("lon", s.lon).apply { if (s.via) put("via", true) }
    internal fun stopOf(o: JSONObject) = TripStop(o.optString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optBoolean("via"))

    fun fromJson(tripId: String, rev: Long, sentAt: Long, o: JSONObject): WebPlanTrip? {
      if (o.optInt("v") != 1) return null
      val line = o.getString("line")
      if (line.count { it == ';' } < 1) return null
      val stops = o.optJSONArray("stops")?.let { a -> (0 until a.length()).map { stopOf(a.getJSONObject(it)) } }.orEmpty()
      return WebPlanTrip(
        tripId, rev, o.optString("name"), o.optJSONObject("start")?.let(::stopOf),
        // A track has none; anything else without one is not a trip.
        if (o.optBoolean("track")) null else stopOf(o.getJSONObject("destination")), if (o.optBoolean("track")) emptyList() else stops,
        (o.optJSONObject("options") ?: JSONObject()).toString(), line, o.optDouble("m", 0.0), o.optDouble("s", 0.0),
        o.optDouble("up", 0.0), o.optInt("curvy", 0), sentAt, WebPlanRef.of(o.optJSONObject("ref")),
      )
    }
  }
}

/**
 * One of the device's planned trips as the planner page shows it, to open and work on there. [rev] changes whenever
 * the trip does. [destination] is null for a line without stops (a ride kept as a trip, a loop): shown, not changed.
 */
class WebPlanHave(
  val id: Long, val rev: String, val name: String, val group: String?, val roundtrip: Boolean,
  val start: TripStop?, val destination: TripStop?, val stops: List<TripStop>, val options: String,
  /** The road, as an encoded polyline ([Polyline]): a few bytes a point, so many trips fit what the relay keeps. */
  val poly: String, val distanceMeters: Double, val durationSeconds: Double, val ascentMeters: Double, val curviness: Int, val savedAt: Long,
) {
  fun toJson(): JSONObject = JSONObject().put("id", id).put("rev", rev).put("name", name)
    .apply { group?.let { put("group", it) }; if (roundtrip) put("loop", true) }
    .apply { start?.let { put("start", WebPlanTrip.stop(it)) }; destination?.let { put("destination", WebPlanTrip.stop(it)) } }
    .put("stops", JSONArray(stops.map(WebPlanTrip::stop))).put("options", JSONObject(options)).put("poly", poly)
    .put("m", distanceMeters).put("s", durationSeconds).put("up", ascentMeters).put("curvy", curviness).put("at", savedAt)
}

/** What came in on a planner channel: trips sent (new or changed) and trips the page asked a device to delete. */
class WebPlanInbound(val trips: List<WebPlanTrip>, val deletes: List<WebPlanRef>) {
  val isEmpty get() = trips.isEmpty() && deletes.isEmpty()
}

/**
 * Google's encoded polyline (precision 1e-5), the form the page and the app send lines in when size matters:
 * about 5 bytes a point instead of 18. https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
object Polyline {
  fun encode(points: List<Pair<Double, Double>>): String {
    val out = StringBuilder()
    var lastLat = 0L; var lastLon = 0L
    for ((lat, lon) in points) {
      val la = Math.round(lat * 1e5); val lo = Math.round(lon * 1e5)
      put(out, la - lastLat); put(out, lo - lastLon)
      lastLat = la; lastLon = lo
    }
    return out.toString()
  }

  fun decode(s: String): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var i = 0; var lat = 0L; var lon = 0L
    fun next(): Long {
      var shift = 0; var result = 0L
      while (true) {
        val b = s[i++].code - 63
        result = result or ((b and 0x1f).toLong() shl shift)
        shift += 5
        if (b < 0x20) break
      }
      return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    while (i < s.length) {
      lat += next(); lon += next()
      out.add(lat / 1e5 to lon / 1e5)
    }
    return out
  }

  private fun put(out: StringBuilder, v: Long) {
    var x = if (v < 0) (v shl 1).inv() else v shl 1
    while (x >= 0x20) { out.append(((0x20L or (x and 0x1f)) + 63).toInt().toChar()); x = x shr 5 }
    out.append((x + 63).toInt().toChar())
  }
}

/**
 * Sealing and opening the frames of a planner channel. Everything is kept by the relay for 30 days under a slot of
 * its own, so whoever connects later (the app, or the page opened again) gets it: a trip under its id, the device's
 * trips under `h<id>` and their list under `index`.
 */
object WebPlan {
  /** As long as the relay keeps a frame (its MAX_KEEP_S); a trip not fetched by then is gone. */
  const val KEEP_SECONDS = 30 * 86_400
  const val INDEX_SLOT = "index"

  fun planFrame(link: WebPlanLink, riderId: ByteArray, seq: Long, trip: WebPlanTrip): String {
    val body = JSONObject().put("seq", seq).put("ts", trip.sentAt).put("tid", trip.tripId).put("rev", trip.rev).put("trip", trip.toJson())
    return sealed(link, FrameType.PLAN, riderId, body, trip.tripId)
  }

  /** The app's word that it has [tripId] at [rev] (as its trip [id]), shown on the planner page next to the trip. */
  fun gotFrame(link: WebPlanLink, riderId: ByteArray, seq: Long, tripId: String, rev: Long, device: String, now: Long, id: Long? = null): String {
    val body = JSONObject().put("seq", seq).put("ts", now).put("tid", tripId).put("rev", rev).put("dev", device).apply { id?.let { put("id", it) } }
    return sealed(link, FrameType.PLAN_GOT, riderId, body, tripId)
  }

  /** One of this device's planned trips, for the page's list of what is on the device. */
  fun haveFrame(link: WebPlanLink, riderId: ByteArray, seq: Long, have: WebPlanHave, now: Long): String =
    sealed(link, FrameType.PLAN_HAVE, riderId, JSONObject().put("seq", seq).put("ts", now).put("trip", have.toJson()), haveSlot(have.id))

  /** Which trips this device has now ([ids], newest first) and what it is called: the page lists those and drops the rest. */
  fun indexFrame(link: WebPlanLink, riderId: ByteArray, seq: Long, device: String, ids: List<Long>, now: Long): String =
    sealed(link, FrameType.PLAN_INDEX, riderId, JSONObject().put("seq", seq).put("ts", now).put("dev", device).put("ids", JSONArray(ids)), INDEX_SLOT)

  /** Asks the relay to forget this device's [have] for trip [id] (deleted, or no longer among the planned trips). */
  fun dropHaveFrame(link: WebPlanLink, riderId: ByteArray, seq: Long, id: Long, now: Long): String =
    JSONObject(sealed(link, FrameType.PLAN_HAVE, riderId, JSONObject().put("seq", seq).put("ts", now).put("gone", id), haveSlot(id))).put("x", 0).toString()

  fun haveSlot(id: Long) = "h$id"

  /** A trip from [text], or null for anything else (another type, another key, altered, malformed). */
  fun openPlan(link: WebPlanLink, text: String): WebPlanTrip? = open(link, text, FrameType.PLAN)?.let {
    // A trip the page took back comes as `gone` without one: nothing to fetch.
    runCatching { WebPlanTrip.fromJson(it.getString("tid"), it.getLong("rev"), it.optLong("ts"), it.getJSONObject("trip")) }.getOrNull()
  }

  /** The device's trip the page asked to delete, or null. */
  fun openDelete(link: WebPlanLink, text: String): WebPlanRef? = open(link, text, FrameType.PLAN_DEL)?.let { WebPlanRef.of(it.optJSONObject("ref")) }

  private fun sealed(link: WebPlanLink, type: FrameType, riderId: ByteArray, body: JSONObject, slot: String): String {
    val f = FrameCrypto.seal(link.keys, type, riderId, body.toString().toByteArray())
    // Kept under [slot], so a changed trip replaces the one kept before it.
    return JSONObject(Wire.encode(f)).put("k", slot(slot)).put("x", KEEP_SECONDS).toString()
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

  /** Adds what [text] holds to [trips] (newest revision per trip) and [deletes]; false when it held neither. */
  internal fun collect(link: WebPlanLink, text: String, trips: MutableMap<String, WebPlanTrip>, deletes: MutableSet<WebPlanRef>): Boolean {
    openPlan(link, text)?.let { trip ->
      val had = trips[trip.tripId]
      if (had == null || trip.rev > had.rev) trips[trip.tripId] = trip
      return true
    }
    openDelete(link, text)?.let { deletes.add(it); return true }
    return false
  }
}

/** A planner channel kept open ([WebPlanInbox.live]): what comes in is handled at once. */
interface WebPlanLive {
  fun send(text: String): Boolean
  fun close()
}

/**
 * Talks to the planner page through the relay. [fetch] is one short connection: read until the relay has replayed what
 * it keeps (nothing more comes for `quietMs`), answer, close; it costs the radio a moment, not a socket. [live] stays
 * connected (while the rider has the planned trips open), so a trip sent from the page arrives at once.
 */
class WebPlanInbox(private val transports: TransportFactory, private val scheme: String = "wss") {

  /**
   * What is kept on [link]'s channel; null when the relay could not be reached. [answer] gets it before the connection
   * closes and returns frames to send back ([WebPlan.gotFrame], the device's own trips).
   */
  fun fetch(link: WebPlanLink, quietMs: Long = 1500, maxMs: Long = 10_000, answer: (WebPlanInbound) -> List<String> = { emptyList() }): WebPlanInbound? {
    val trips = LinkedHashMap<String, WebPlanTrip>()
    val deletes = LinkedHashSet<WebPlanRef>()
    val lock = Object()
    var opened = false
    var last = System.currentTimeMillis()
    val closed = CountDownLatch(1)
    val t = transports.open(link.relayUrl(scheme), object : TransportListener {
      override fun onOpen() { synchronized(lock) { opened = true; last = System.currentTimeMillis() } }
      override fun onMessage(text: String) {
        synchronized(lock) {
          last = System.currentTimeMillis()
          WebPlan.collect(link, text, trips, deletes)
        }
      }
      override fun onClosed(code: Int) = closed.countDown()
    })
    val end = System.currentTimeMillis() + maxMs
    while (System.currentTimeMillis() < end) {
      if (closed.await(100, TimeUnit.MILLISECONDS)) break
      if (synchronized(lock) { opened && System.currentTimeMillis() - last >= quietMs }) break
    }
    val got = synchronized(lock) { if (opened) WebPlanInbound(trips.values.toList(), deletes.toList()) else null }
    if (got != null && closed.count > 0) answer(got).forEach { t.send(it) }
    t.close()
    return got
  }

  /**
   * Stays connected to [link]'s channel until closed. [onInbound] gets, on the transport's thread, first everything the
   * relay replays (in one go, once it goes quiet for [quietMs]) and then each trip or delete as it comes, and returns
   * frames to send back. [onClosed] when the connection ends by itself (the caller may connect again later).
   */
  fun live(link: WebPlanLink, quietMs: Long = 1500, onInbound: (WebPlanInbound) -> List<String>, onClosed: () -> Unit = {}): WebPlanLive {
    val lock = Object()
    val trips = LinkedHashMap<String, WebPlanTrip>()
    val deletes = LinkedHashSet<WebPlanRef>()
    var replayed = false
    var closedByUs = false
    lateinit var transport: Transport
    val timer = java.util.Timer("webplan-live", true)

    fun flush() {
      val inbound = synchronized(lock) {
        if (trips.isEmpty() && deletes.isEmpty()) return
        WebPlanInbound(trips.values.toList(), deletes.toList()).also { trips.clear(); deletes.clear() }
      }
      onInbound(inbound).forEach { transport.send(it) }
    }

    var quietTask: java.util.TimerTask? = null
    transport = transports.open(link.relayUrl(scheme), object : TransportListener {
      override fun onOpen() {
        // An empty channel replays nothing: the first batch is then simply empty.
        synchronized(lock) { quietTask = timer.schedule(quietMs) { synchronized(lock) { replayed = true }; flush() } }
      }
      override fun onMessage(text: String) {
        val now = synchronized(lock) {
          if (!WebPlan.collect(link, text, trips, deletes)) return
          if (!replayed) {
            quietTask?.cancel()
            quietTask = timer.schedule(quietMs) { synchronized(lock) { replayed = true }; flush() }
          }
          replayed
        }
        if (now) flush()
      }
      override fun onClosed(code: Int) {
        timer.cancel()
        if (!synchronized(lock) { closedByUs }) onClosed()
      }
    })
    return object : WebPlanLive {
      override fun send(text: String) = transport.send(text)
      override fun close() {
        synchronized(lock) { closedByUs = true }
        timer.cancel()
        transport.close()
      }
    }
  }

  private fun java.util.Timer.schedule(delayMs: Long, block: () -> Unit): java.util.TimerTask =
    object : java.util.TimerTask() { override fun run() = block() }.also { schedule(it, delayMs) }
}
