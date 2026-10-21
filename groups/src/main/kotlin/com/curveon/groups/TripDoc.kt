package com.curveon.groups

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A place on a shared trip. Two stops are the same stop when they are in the same spot (to about a metre). */
class TripStop(val name: String, val lat: Double, val lon: Double, val via: Boolean = false) {
  val key: String get() = String.format(Locale.ROOT, "%.5f,%.5f", lat, lon)
  override fun equals(other: Any?) = other is TripStop && key == other.key && via == other.via
  override fun hashCode() = key.hashCode() * 31 + via.hashCode()
}

/**
 * A trip shared with a group, as everyone sees it: where to, the stops on the way, and how to ride ([options], JSON the
 * app understands and the library only carries). It is the content of a [Payload.Trip]; whoever changes it sends the whole
 * new document with a higher revision, and the highest revision wins.
 *
 * Riders pass stops at different times, and a passed stop is dropped from that rider's own route. So [stops] is always
 * the whole list, and what a rider still has to ride is [remaining] without the stops they have passed.
 */
class TripDoc(val name: String, val destination: TripStop, val stops: List<TripStop>, val options: String = "{}") {

  /** The stops this rider has not passed yet, in order. */
  fun remaining(passed: Set<String>): List<TripStop> = stops.filter { it.key !in passed }

  /** True if what this rider rides (the stops left, and where to) is exactly this document. */
  fun matches(passed: Set<String>, remaining: List<TripStop>, destination: TripStop): Boolean =
    this.destination.key == destination.key && remaining(passed) == remaining

  /**
   * The document after this rider changed their route to [remaining] stops and [destination]: the stops they have
   * passed stay in it, first and in their old order, so the others (who may not have passed them yet) still have them.
   */
  fun edited(passed: Set<String>, remaining: List<TripStop>, destination: TripStop): TripDoc =
    TripDoc(name, destination, stops.filter { it.key in passed } + remaining, options)

  fun toJson(): String = JSONObject().put("v", 1).put("name", name).put("destination", stopJson(destination))
    .put("stops", JSONArray(stops.map(::stopJson))).put("options", JSONObject(options)).toString()

  companion object {
    /** Null for a document that is not valid or is from a newer version. */
    fun fromJson(text: String): TripDoc? = try {
      val o = JSONObject(text)
      if (o.optInt("v", 1) != 1) null
      else TripDoc(
        o.optString("name"), parseStop(o.getJSONObject("destination")),
        o.optJSONArray("stops")?.let { a -> (0 until a.length()).map { parseStop(a.getJSONObject(it)) } }.orEmpty(),
        o.optJSONObject("options")?.toString() ?: "{}",
      )
    } catch (_: Exception) {
      null
    }

    private fun stopJson(s: TripStop) = JSONObject().put("name", s.name).put("lat", s.lat).put("lon", s.lon).apply { if (s.via) put("via", true) }

    private fun parseStop(j: JSONObject) = TripStop(j.optString("name"), j.getDouble("lat"), j.getDouble("lon"), j.optBoolean("via"))

    /**
     * Which of two revisions of one trip wins: the higher revision; the same revision from two riders at once goes to the
     * larger rider id, so every device picks the same one.
     */
    fun isNewer(rev: Long, from: String, currentRev: Long, currentFrom: String): Boolean =
      rev > currentRev || (rev == currentRev && from > currentFrom)
  }
}
