package com.curveon.groups

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One GPX track as it is shared. [points] is the app's own text: "lat,lon,ele[,time];…". */
data class SharedGpxTrack(
  val name: String, val filename: String, val km: Float, val gain: Int, val maxEle: Int, val pointCount: Int,
  val points: String, val waypoints: String, val favorite: Boolean, val category: String,
  val showOnMap: Boolean, val color: Int, val showPois: Boolean, val reversed: Boolean,
)

/** A GPX group with all its tracks and settings ([isGroup]), or one loose track, just as the sender has it. */
data class SharedGpx(
  val name: String, val isGroup: Boolean, val tracks: List<SharedGpxTrack>,
  val visibility: Int = 0, val direction: Int = 0, val sameColor: Boolean = false, val color: Int = 0,
)

/**
 * What goes in the group channel when GPX is shared: [SharedGpx] as gzipped JSON, cut into [Payload.Trip]s. So it is
 * encrypted and kept for riders who look in later like a trip, needs nothing new from the relay or a phone in between,
 * and an older app, for which the document is not a trip ([TripDoc.fromJson] gives null), ignores it. Each part carries
 * what is needed to offer the whole, and parts may come in any order.
 */
object GpxShare {
  private const val PREFIX = "gpxshare/"
  private const val KIND = "gpx"
  /** After the encodings on the way (base64 twice) a part is about 455 KiB: under the relay's 512 KiB for a frame. */
  internal const val PART_BYTES = 256 * 1024
  /** The relay keeps 2 MiB per rider, so more parts would push out the first ones. About 6900 km of track. */
  const val MAX_PARTS = 4
  /** Far more than [MAX_PARTS] can hold of real tracks: what a broken or hostile share may unpack to. */
  private const val MAX_UNPACKED_BYTES = 128 * 1024 * 1024
  private const val MAX_NAME = 200
  private val SHARE_ID = Regex("[A-Za-z0-9_-]{1,64}")

  class Part(val shareId: String, val index: Int, val count: Int, val name: String, val isGroup: Boolean, val tracks: Int, val km: Int, val data: ByteArray)

  fun isPart(tripId: String) = tripId.startsWith(PREFIX)

  /** [share] as parts to send with [shareId]; null when it is too big to share. */
  fun pack(share: SharedGpx, shareId: String): List<Payload.Trip>? {
    val packed = gzip(toJson(share).toString().toByteArray())
    val pieces = (0 until packed.size step PART_BYTES).map { packed.copyOfRange(it, minOf(it + PART_BYTES, packed.size)) }
    if (pieces.size > MAX_PARTS) return null
    val name = share.name.take(MAX_NAME)
    val km = share.tracks.sumOf { it.km.toDouble() }.toInt()
    return pieces.mapIndexed { i, piece ->
      val doc = JSONObject().put("kind", KIND).put("id", shareId).put("i", i).put("n", pieces.size).put("name", name)
        .put("group", share.isGroup).put("tracks", share.tracks.size).put("km", km).put("data", B64.encode(piece))
      Payload.Trip("$PREFIX$shareId/$i", 1, name, doc.toString())
    }
  }

  /** Null when [doc] is not a part of a share, or one that could not have come from [pack]. */
  fun parsePart(doc: String): Part? = runCatching {
    val o = JSONObject(doc)
    if (o.optString("kind") != KIND) return null
    val id = o.getString("id")
    val i = o.getInt("i")
    val n = o.getInt("n")
    if (!SHARE_ID.matches(id) || n !in 1..MAX_PARTS || i !in 0 until n) return null
    Part(id, i, n, o.optString("name").take(MAX_NAME), o.optBoolean("group"), o.optInt("tracks"), o.optInt("km"), B64.decode(o.getString("data")))
  }.getOrNull()

  /** The share from all its [parts], in any order; null when some are missing or it cannot be read. */
  fun unpack(parts: List<Part>): SharedGpx? = runCatching {
    val count = parts.firstOrNull()?.count ?: return null
    val ordered = parts.sortedBy { it.index }
    if (ordered.map { it.index } != (0 until count).toList()) return null
    val packed = ByteArrayOutputStream().also { out -> ordered.forEach { out.write(it.data) } }.toByteArray()
    fromJson(JSONObject(String(gunzip(packed))))
  }.getOrNull()

  private fun toJson(s: SharedGpx) = JSONObject().put("v", 1).put("name", s.name).put("group", s.isGroup)
    .put("visibility", s.visibility).put("direction", s.direction).put("sameColor", s.sameColor).put("color", s.color)
    .put("tracks", JSONArray(s.tracks.map { t ->
      val o = JSONObject().put("name", t.name).put("file", t.filename).put("km", t.km.toDouble()).put("gain", t.gain).put("maxEle", t.maxEle)
        .put("count", t.pointCount).put("way", t.waypoints).put("fav", t.favorite).put("cat", t.category)
        .put("show", t.showOnMap).put("color", t.color).put("pois", t.showPois).put("rev", t.reversed)
      val delta = deltas(t.points)
      if (delta != null) o.put("d", delta) else o.put("p", t.points)
    }))

  private fun fromJson(o: JSONObject): SharedGpx? {
    if (o.optInt("v") != 1) return null
    val a = o.getJSONArray("tracks")
    return SharedGpx(
      o.getString("name"), o.optBoolean("group"),
      (0 until a.length()).map { i ->
        val t = a.getJSONObject(i)
        SharedGpxTrack(
          t.getString("name"), t.optString("file"), t.optDouble("km").toFloat(), t.optInt("gain"), t.optInt("maxEle"), t.optInt("count"),
          if (t.has("d")) fromDeltas(t.getString("d")) else t.getString("p"), t.optString("way"), t.optBoolean("fav"), t.optString("cat"),
          t.optBoolean("show"), t.optInt("color"), t.optBoolean("pois", true), t.optBoolean("rev"),
        )
      },
      o.optInt("visibility"), o.optInt("direction"), o.optBoolean("sameColor"), o.optInt("color"),
    )
  }

  /**
   * [points] with each number as the difference from the point before: about half the size once gzipped (measured on a
   * real road, 152 against 281 bytes per km). Null when that would not bring back exactly the same text.
   */
  internal fun deltas(points: String): String? = runCatching {
    if (points.isEmpty()) return null
    var lat = 0L; var lon = 0L; var ele = 0L; var time = 0L
    val out = StringBuilder(points.length / 2)
    for (p in points.splitToSequence(';')) {
      val f = p.split(',')
      val la = Math.round(f[0].toDouble() * 1e6); val lo = Math.round(f[1].toDouble() * 1e6); val e = f[2].toLong()
      if (out.isNotEmpty()) out.append(';')
      out.append(la - lat).append(',').append(lo - lon).append(',').append(e - ele)
      lat = la; lon = lo; ele = e
      if (f.size > 3) { val t = f[3].toLong(); out.append(',').append(t - time); time = t }
    }
    out.toString().takeIf { fromDeltas(it) == points }
  }.getOrNull()

  internal fun fromDeltas(deltas: String): String {
    var lat = 0L; var lon = 0L; var ele = 0L; var time = 0L
    val out = StringBuilder(deltas.length * 2)
    for (p in deltas.splitToSequence(';')) {
      val f = p.split(',')
      lat += f[0].toLong(); lon += f[1].toLong(); ele += f[2].toLong()
      if (out.isNotEmpty()) out.append(';')
      out.append(String.format(Locale.ROOT, "%.6f,%.6f,%d", lat / 1e6, lon / 1e6, ele))
      if (f.size > 3) { time += f[3].toLong(); out.append(',').append(time) }
    }
    return out.toString()
  }

  private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

  private fun gunzip(b: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPInputStream(b.inputStream()).use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        check(out.size() <= MAX_UNPACKED_BYTES) { "too big" }
      }
    }
    return out.toByteArray()
  }
}
