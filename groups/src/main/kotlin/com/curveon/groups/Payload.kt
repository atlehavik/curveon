package com.curveon.groups

import org.json.JSONObject

/** The picture on a rider's marker. No avatar means initials on the rider's colour. */
sealed interface Avatar {
  /** One of the icons bundled with the app; every device draws the same artwork. */
  data class Icon(val id: String) : Avatar

  /** A small square JPEG/WebP (about 96 px, at most ~8 KB). */
  class Photo(val jpeg: ByteArray) : Avatar
}

/** What goes inside the encrypted part of a frame (docs/groups-protocol.md). */
sealed interface Payload {
  val type: FrameType

  data class Pos(
    val lat: Double, val lon: Double,
    /** metres per second */
    val speed: Float,
    /** degrees from north */
    val heading: Float,
    val accuracy: Float,
    val battery: Int? = null,
    val paused: Boolean = false,
    /** The sender decides what counts as stopped, so everyone sees the same state. */
    val stopped: Boolean = false,
    val stoppedSince: Long? = null,
  ) : Payload { override val type get() = FrameType.POS }

  class Hello(
    val nickname: String,
    /** ARGB */
    val colour: Int,
    val agreementPublicKey: ByteArray,
    val avatar: Avatar? = null,
  ) : Payload { override val type get() = FrameType.HELLO }

  /** [data] is the gzipped GPX, or the route's own format. */
  class Route(val id: String, val name: String, val kind: String, val data: ByteArray) : Payload {
    override val type get() = FrameType.ROUTE
  }

  data class Sos(val lat: Double, val lon: Double) : Payload { override val type get() = FrameType.SOS }

  /** [sosRiderId] is the rider who asked for help (base64url). */
  data class SosAck(val sosRiderId: String, val coming: Boolean) : Payload { override val type get() = FrameType.SOS_ACK }

  data object SosCancel : Payload { override val type get() = FrameType.SOS_CANCEL }

  data object Leave : Payload { override val type get() = FrameType.LEAVE }
}

/** A payload with its sender's counter (see [ReplayGuard]) and clock. */
class Signed(val seq: Long, val tsMs: Long, val payload: Payload)

object PayloadCodec {
  fun encode(seq: Long, tsMs: Long, p: Payload): ByteArray {
    val o = JSONObject().put("seq", seq).put("ts", tsMs)
    when (p) {
      is Payload.Pos -> {
        o.put("lat", p.lat).put("lon", p.lon).put("speed", p.speed.toDouble()).put("heading", p.heading.toDouble())
          .put("acc", p.accuracy.toDouble())
        p.battery?.let { o.put("bat", it) }
        if (p.paused) o.put("paused", true)
        if (p.stopped) o.put("stopped", true)
        p.stoppedSince?.let { o.put("since", it) }
      }
      is Payload.Hello -> {
        o.put("nick", p.nickname).put("colour", p.colour).put("ak", B64.encode(p.agreementPublicKey))
        when (val a = p.avatar) {
          is Avatar.Icon -> o.put("icon", a.id)
          is Avatar.Photo -> o.put("photo", B64.encode(a.jpeg))
          null -> Unit
        }
      }
      is Payload.Route -> o.put("id", p.id).put("name", p.name).put("kind", p.kind).put("data", B64.encode(p.data))
      is Payload.Sos -> o.put("lat", p.lat).put("lon", p.lon)
      is Payload.SosAck -> o.put("sos", p.sosRiderId).put("coming", p.coming)
      Payload.SosCancel, Payload.Leave -> Unit
    }
    return o.toString().toByteArray()
  }

  /** Null for a payload that is not valid for [type]; a newer client's extra fields are ignored. */
  fun decode(type: FrameType, bytes: ByteArray): Signed? = try {
    val o = JSONObject(String(bytes))
    val payload: Payload = when (type) {
      FrameType.POS -> Payload.Pos(
        o.getDouble("lat"), o.getDouble("lon"), o.optDouble("speed", 0.0).toFloat(), o.optDouble("heading", 0.0).toFloat(),
        o.optDouble("acc", 0.0).toFloat(), if (o.has("bat")) o.getInt("bat") else null, o.optBoolean("paused"),
        o.optBoolean("stopped"), if (o.has("since")) o.getLong("since") else null,
      )
      FrameType.HELLO -> Payload.Hello(
        o.getString("nick"), o.getInt("colour"), B64.decode(o.getString("ak")),
        when {
          o.has("photo") -> Avatar.Photo(B64.decode(o.getString("photo")))
          o.has("icon") -> Avatar.Icon(o.getString("icon"))
          else -> null
        },
      )
      FrameType.ROUTE -> Payload.Route(o.getString("id"), o.getString("name"), o.optString("kind"), B64.decode(o.getString("data")))
      FrameType.SOS -> Payload.Sos(o.getDouble("lat"), o.getDouble("lon"))
      FrameType.SOS_ACK -> Payload.SosAck(o.getString("sos"), o.optBoolean("coming", true))
      FrameType.SOS_CANCEL -> Payload.SosCancel
      FrameType.LEAVE -> Payload.Leave
      FrameType.REKEY -> return null // not encrypted with the group key
    }
    Signed(o.getLong("seq"), o.getLong("ts"), payload)
  } catch (_: Exception) {
    null
  }
}
