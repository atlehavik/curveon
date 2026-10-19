package com.curveon.groups

/**
 * Drops frames that arrive twice or out of order. Each rider numbers what it sends (`seq` inside the
 * encrypted payload, rising per rider and key epoch); a frame is accepted only if its number is
 * higher than the last accepted one from that rider. The relay cannot tell, because it never sees `seq`.
 */
class ReplayGuard {
  private val last = HashMap<String, Long>()

  @Synchronized
  fun accept(riderId: ByteArray, epoch: Int, seq: Long): Boolean {
    val key = B64.encode(riderId) + ":" + epoch
    val prev = last[key]
    if (prev != null && seq <= prev) return false
    last[key] = seq
    return true
  }
}
