package com.curveon.groups

/** One position from the rider's device. */
class Fix(
  val lat: Double, val lon: Double,
  /** metres per second */
  val speedMs: Float,
  val heading: Float,
  val accuracy: Float,
)

/**
 * Decides what of the rider's position stream goes to the group. Sends often while moving, rarely
 * while stopped (the radio staying awake costs more battery than the data), and says "stopped" only after
 * a minute below walking speed, so a red light does not count. The sender decides, so everyone sees the same.
 */
class PositionSharer(
  private val clock: () -> Long = System::currentTimeMillis,
  private val movingIntervalMs: Long = 5_000,
  private val stoppedIntervalMs: Long = 30_000,
  private val dataSaverIntervalMs: Long = 15_000,
  private val stoppedBelowMs: Float = 3f / 3.6f,
  private val stoppedAfterMs: Long = 60_000,
) {
  /** Fewer updates for riders with little data or a foreign subscription. */
  var dataSaver = false

  private var lastSentAt: Long? = null
  private var slowSince: Long? = null
  private var sentStopped = false

  /** The payload to send now, or null if it is not time yet. */
  fun offer(fix: Fix, battery: Int? = null): Payload.Pos? {
    val now = clock()
    if (fix.speedMs < stoppedBelowMs) {
      if (slowSince == null) slowSince = now
    } else {
      slowSince = null
    }
    val stopped = slowSince?.let { now - it >= stoppedAfterMs } ?: false
    val interval = when {
      stopped -> stoppedIntervalMs
      dataSaver -> maxOf(movingIntervalMs, dataSaverIntervalMs)
      else -> movingIntervalMs
    }
    val last = lastSentAt
    if (last != null && now - last < interval && stopped == sentStopped) return null
    lastSentAt = now
    sentStopped = stopped
    return Payload.Pos(
      fix.lat, fix.lon, fix.speedMs, fix.heading, fix.accuracy, battery,
      stopped = stopped, stoppedSince = if (stopped) slowSince else null,
    )
  }

  /** Sharing was paused or the group changed: the next fix goes out at once. */
  fun reset() {
    lastSentAt = null
    slowSince = null
    sentStopped = false
  }
}
