package com.curveon.webplanner

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How much the planner is asked and how it keeps up, to see when the VPS gets too small before riders notice: counts
 * and times only, never where or who. [roll] closes the hour (a line in the journal) and keeps the last day of hours.
 */
class Stats(private val clock: () -> Long = System::currentTimeMillis) {
  private val asked = ConcurrentHashMap<String, AtomicLong>()
  val legs = AtomicLong()
  val busy = AtomicLong()
  val superseded = AtomicLong()
  val slowDown = AtomicLong()
  val poiLookups = AtomicLong()
  val waitedMillis = AtomicLong()
  val workedMillis = AtomicLong()
  private val maxQueue = AtomicLong()
  @Volatile private var since = clock()
  private val hours = ArrayDeque<JSONObject>()

  fun asked(kind: String) { asked.computeIfAbsent(kind) { AtomicLong() }.incrementAndGet() }
  fun queued(depth: Int) { maxQueue.accumulateAndGet(depth.toLong(), ::maxOf) }

  /** This hour so far. */
  fun now(): JSONObject = JSONObject()
    .put("since", since)
    .put("asked", JSONObject(asked.mapValues { it.value.get() }))
    .put("legs", legs.get()).put("busy", busy.get()).put("superseded", superseded.get()).put("slow_down", slowDown.get())
    .put("pois", poiLookups.get()).put("waited_ms", waitedMillis.get()).put("worked_ms", workedMillis.get()).put("max_queue", maxQueue.get())

  /** Ends the hour: its numbers go to the journal and the list, and counting starts again. */
  @Synchronized fun roll(): JSONObject {
    val hour = now()
    asked.values.forEach { it.set(0) }
    listOf(legs, busy, superseded, slowDown, poiLookups, waitedMillis, workedMillis, maxQueue).forEach { it.set(0) }
    since = clock()
    hours.addLast(hour)
    while (hours.size > 24) hours.removeFirst()
    return hour
  }

  @Synchronized fun json(): JSONObject = JSONObject().put("now", now()).put("hours", JSONArray(hours.toList()))
}
