package com.curveon.groups

/**
 * Drops frames that arrive twice or out of order. Each rider numbers what it sends (`seq` inside the
 * encrypted payload, rising per rider, device, key epoch and stream); a frame is accepted only if its number is
 * higher than the last accepted one on that stream. Counting streams apart (matching what the relay keeps per
 * type or slot) ensures a newer `hello` sent on reconnect does not cause older kept frames (`trip`, `route`,
 * `invite`, `sos`, etc.) replayed after it to be dropped. The relay cannot tell, because it never sees `seq`.
 * A rider with two devices (tablet and phone) numbers on each from its own clock, so the devices count apart.
 */
class ReplayGuard {
  private val last = HashMap<String, Long>()

  @Synchronized
  fun accept(riderId: ByteArray, epoch: Int, seq: Long, device: String = "", stream: String = ""): Boolean {
    val key = B64.encode(riderId) + ":" + epoch + ":" + device + ":" + stream
    val prev = last[key]
    if (prev != null && seq <= prev) return false
    last[key] = seq
    return true
  }
}
