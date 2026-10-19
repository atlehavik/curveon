package com.curveon.groups

/**
 * An SOS as the rider sees it. [acks] maps each rider who has answered to whether they are on the way
 * (true) or have only seen it (false), which is what lets the sender see "received by 2 of 3".
 */
class SosView(
  val from: String, val lat: Double, val lon: Double, val tsMs: Long,
  val active: Boolean, val mine: Boolean, val acks: Map<String, Boolean>,
)

/** Keeps the active SOS of each rider in the group and who has answered it. */
class SosTracker(private val myRiderId: String) {
  private class Entry(val from: String, val lat: Double, val lon: Double, val tsMs: Long, val mine: Boolean) {
    val acks = LinkedHashMap<String, Boolean>()
    fun view(active: Boolean = true) = SosView(from, lat, lon, tsMs, active, mine, LinkedHashMap(acks))
  }

  private val entries = LinkedHashMap<String, Entry>()

  /** Someone else's SOS (or the same one sent again after a reconnect). */
  fun received(from: String, sos: Payload.Sos, tsMs: Long): SosView {
    val e = entries[from]?.takeIf { it.tsMs == tsMs } ?: Entry(from, sos.lat, sos.lon, tsMs, from == myRiderId).also { entries[from] = it }
    return e.view()
  }

  fun started(sos: Payload.Sos, tsMs: Long): SosView = received(myRiderId, sos, tsMs)

  /** An answer to [sosRider]'s SOS. "On the way" is never turned back into "seen". Null if that SOS is not known. */
  fun answered(from: String, sosRider: String, coming: Boolean): SosView? {
    val e = entries[sosRider] ?: return null
    e.acks[from] = (e.acks[from] ?: false) || coming
    return e.view()
  }

  /** The sender ended it. Null if it was not known. */
  fun cancelled(from: String): SosView? = entries.remove(from)?.view(active = false)

  /** The rider left or was removed: their SOS goes with them. */
  fun forget(from: String) = cancelled(from)

  fun active(): List<SosView> = entries.values.map { it.view() }

  fun clear() = entries.clear()
}
