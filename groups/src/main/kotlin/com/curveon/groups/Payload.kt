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

  /**
   * In a friend channel: an invite to a ride group, from one friend to the other. [offerId] is unique, so the same
   * invite (the relay keeps it until it is fetched) is shown to the rider once.
   */
  data class GroupInvite(val offerId: String, val link: String, val groupName: String?) : Payload {
    override val type get() = FrameType.INVITE
  }

  /**
   * A trip (planned route) shared with the group, as one document that anyone in the group can change: the app
   * defines what is in [doc] (JSON), the library only carries it. The highest [rev] wins; [tripId] stays the same
   * through all the changes.
   */
  data class Trip(val tripId: String, val rev: Long, val name: String, val doc: String) : Payload {
    override val type get() = FrameType.TRIP
  }

  /**
   * "This device is the one sending my position now." A rider with two devices (tablet and phone) has one of them
   * send, so the group does not get two streams: the newest claim wins, and the other device keeps quiet.
   */
  data class Claim(val deviceId: String) : Payload { override val type get() = FrameType.CLAIM }

  /**
   * "I am trying to reach you": for the rider [to] (riderId, base64url) only, who may not hear the intercom. [id] is
   * unique, so the same nudge (the relay keeps it a while for a rider who looks in later) is shown once.
   */
  data class Nudge(val id: String, val to: String) : Payload { override val type get() = FrameType.NUDGE }

  /** The answer to [Nudge] [id]: the rider has seen it. [to] is the rider who sent the nudge. */
  data class NudgeSeen(val id: String, val to: String) : Payload { override val type get() = FrameType.NUDGE_SEEN }

  /**
   * "Be my friend": for the rider [to] (riderId, base64url) only. [box] holds the link to a new private friend channel,
   * sealed to [to]'s key so the others in the group, who hold the same group key, cannot read it. [id] is unique.
   */
  class FriendRequest(val id: String, val to: String, val box: SealedBox) : Payload { override val type get() = FrameType.FRIEND_REQ }
}

/** A payload with its sender's counter (see [ReplayGuard]), clock, and the device it came from (a rider's devices count separately). */
class Signed(val seq: Long, val tsMs: Long, val payload: Payload, val device: String = "")

object PayloadCodec {
  fun encode(seq: Long, tsMs: Long, p: Payload, device: String = ""): ByteArray {
    val o = JSONObject().put("seq", seq).put("ts", tsMs)
    if (device.isNotEmpty()) o.put("dev", device)
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
      is Payload.Claim -> o.put("dev", p.deviceId)
      is Payload.GroupInvite -> { o.put("oid", p.offerId).put("link", p.link); p.groupName?.let { o.put("gname", it) } }
      is Payload.Trip -> o.put("tid", p.tripId).put("rev", p.rev).put("name", p.name).put("doc", p.doc)
      is Payload.Nudge -> o.put("id", p.id).put("to", p.to)
      is Payload.NudgeSeen -> o.put("id", p.id).put("to", p.to)
      is Payload.FriendRequest -> o.put("id", p.id).put("to", p.to).put("eph", B64.encode(p.box.ephemeralPublic))
        .put("n", B64.encode(p.box.nonce)).put("c", B64.encode(p.box.ciphertext))
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
      FrameType.CLAIM -> Payload.Claim(o.getString("dev")) // sent with the device's own id, like everything else
      FrameType.INVITE -> Payload.GroupInvite(o.getString("oid"), o.getString("link"), if (o.has("gname")) o.getString("gname") else null)
      FrameType.TRIP -> Payload.Trip(o.getString("tid"), o.getLong("rev"), o.optString("name"), o.getString("doc"))
      FrameType.NUDGE -> Payload.Nudge(o.getString("id"), o.getString("to"))
      FrameType.NUDGE_SEEN -> Payload.NudgeSeen(o.getString("id"), o.getString("to"))
      FrameType.FRIEND_REQ -> Payload.FriendRequest(
        o.getString("id"), o.getString("to"),
        SealedBox(B64.decode(o.getString("eph")), B64.decode(o.getString("n")), B64.decode(o.getString("c"))),
      )
      FrameType.REKEY -> return null // not encrypted with the group key
    }
    Signed(o.getLong("seq"), o.getLong("ts"), payload, o.optString("dev"))
  } catch (_: Exception) {
    null
  }
}
