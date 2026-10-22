package com.curveon.groups

import org.json.JSONArray
import org.json.JSONObject

/** Frames as the JSON text the relay sees (docs/groups-protocol.md). */
object Wire {
  const val VERSION = 1

  /**
   * What this client understands, sent to the relay as `pv` when connecting: 2 knows the relay's `relay` frame, any
   * frame type and keeping in slots. Raised when a client learns something the relay may want to know about.
   */
  const val REVISION = 2

  fun typeOf(text: String): String? = try {
    JSONObject(text).optString("t").takeIf { it.isNotEmpty() }
  } catch (_: Exception) {
    null
  }

  fun encode(f: Frame, keep: Keep? = null): String = JSONObject().put("v", VERSION).put("t", f.type.wire).put("r", B64.encode(f.riderId))
    .put("e", f.epoch).put("n", B64.encode(f.nonce)).put("c", B64.encode(f.ciphertext)).apply {
      if (keep != null) { put("k", keep.slot).put("x", keep.seconds); if (keep.memory) put("m", true) }
    }.toString()

  /** Null for a rekey, the relay's own `relay` frame, an unknown type or anything malformed. */
  fun decode(text: String): Frame? = try {
    val o = JSONObject(text)
    val type = FrameType.of(o.getString("t"))
    if (o.optInt("v") != VERSION || type == null || type == FrameType.REKEY) null
    else Frame(type, B64.decode(o.getString("r")), o.getInt("e"), B64.decode(o.getString("n")), B64.decode(o.getString("c")))
  } catch (_: Exception) {
    null
  }

  fun encode(rk: Rekey, adminRiderId: ByteArray): String = JSONObject().put("v", VERSION).put("t", "rekey")
    .put("r", B64.encode(adminRiderId)).put("e", rk.newEpoch).put("p", rk.newProof)
    .put("keys", JSONArray(rk.keys.map {
      JSONObject().put("r", B64.encode(it.riderId)).put("ep", B64.encode(it.ephemeralPublic))
        .put("n", B64.encode(it.nonce)).put("c", B64.encode(it.ciphertext))
    })).put("s", B64.encode(rk.signature)).toString()

  fun decodeRekey(text: String, groupId: ByteArray): Rekey? = try {
    val o = JSONObject(text)
    if (o.optInt("v") != VERSION || o.getString("t") != "rekey") null
    else {
      val arr = o.getJSONArray("keys")
      val keys = (0 until arr.length()).map {
        val k = arr.getJSONObject(it)
        SealedKey(B64.decode(k.getString("r")), B64.decode(k.getString("ep")), B64.decode(k.getString("n")), B64.decode(k.getString("c")))
      }
      Rekey(groupId, o.getInt("e"), o.getString("p"), keys, B64.decode(o.getString("s")))
    }
  } catch (_: Exception) {
    null
  }
}
