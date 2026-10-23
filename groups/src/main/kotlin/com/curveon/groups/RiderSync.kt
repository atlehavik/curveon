package com.curveon.groups

import org.json.JSONArray
import org.json.JSONObject

/** One thing a rider's devices keep in step: a friend or ride group (with this rider's identity in it) or the profile. */
class SyncEntry(val key: String, val ts: Long, val deleted: Boolean, val payload: String?) {
  /** True if this entry should replace [other] (the same key): the later one; at the same time the deletion, then the larger payload. */
  fun beats(other: SyncEntry): Boolean = when {
    ts != other.ts -> ts > other.ts
    deleted != other.deleted -> deleted
    else -> (payload ?: "") > (other.payload ?: "")
  }

  fun sameAs(other: SyncEntry) = ts == other.ts && deleted == other.deleted && payload == other.payload
}

/**
 * What a rider's devices share: every friend and ride group, and the profile, each as an entry stamped with when it last
 * changed. Two devices that swap their documents and take the newer entry of each key end up identical, in whatever order
 * and however often they swap, so a message that arrives twice or late does no harm. A deleted entry stays as a tombstone, so
 * a group left on one device is not brought back by the other.
 *
 * The document travels as a [Payload.Trip] on the rider's private [GroupKind.SELF] channel, end-to-end encrypted like the rest.
 */
class SyncDoc(val entries: Map<String, SyncEntry> = emptyMap()) {

  /** The entries of [other] that win over ours: new keys and newer ones. */
  fun newerIn(other: SyncDoc): List<SyncEntry> = other.entries.values.filter { r -> entries[r.key]?.let { r.beats(it) } ?: true }

  /** True if we have something [other] lacks or has older: it should be sent the result. */
  fun hasNewerThan(other: SyncDoc): Boolean = entries.values.any { l -> other.entries[l.key]?.let { l.beats(it) } ?: true }

  fun merge(other: SyncDoc): SyncDoc = with(newerIn(other))

  fun with(changes: List<SyncEntry>): SyncDoc = if (changes.isEmpty()) this else SyncDoc(entries + changes.associateBy { it.key })

  fun with(change: SyncEntry): SyncDoc = with(listOf(change))

  fun toJson(): String = JSONObject().put("v", 1).put("items", JSONArray(entries.values.map {
    JSONObject().put("k", it.key).put("t", it.ts).put("d", it.deleted).put("p", it.payload ?: JSONObject.NULL)
  })).toString()

  companion object {
    const val PROFILE = "profile"
    fun groupKey(groupIdHex: String) = "g:$groupIdHex"

    /** Null for a document that is not valid or is from a newer version. */
    fun fromJson(text: String): SyncDoc? = try {
      val o = JSONObject(text)
      if (o.optInt("v", 1) != 1) null
      else {
        val a = o.getJSONArray("items")
        SyncDoc((0 until a.length()).map { a.getJSONObject(it) }.map { e ->
          SyncEntry(e.getString("k"), e.getLong("t"), e.optBoolean("d"), if (e.isNull("p")) null else e.getString("p"))
        }.associateBy { it.key })
      }
    } catch (_: Exception) {
      null
    }

    /**
     * What another device needs to be in [state]: everything but this device's own id (each device has one) and the friend's
     * picture (large, and heard again from the friend at once).
     */
    fun payload(state: GroupState): String {
      val o = JSONObject(state.toJson())
      o.remove("device")
      o.remove("trust") // what this device has seen of the others; each device learns it for itself
      o.remove("sosEnd")
      o.optJSONObject("peer")?.put("photo", JSONObject.NULL)
      return o.toString()
    }

    /**
     * The group a payload describes, on this device (with [deviceId] as its id, and what [existing], its copy here, had
     * learnt of the other riders), or null if it is not valid.
     */
    fun restore(payload: String, deviceId: String, existing: GroupState? = null): GroupState? = try {
      GroupState.fromJson(JSONObject(payload).put("device", deviceId).toString())?.also { s -> existing?.let(s::keepLearntFrom) }
    } catch (_: Exception) {
      null
    }
  }
}
