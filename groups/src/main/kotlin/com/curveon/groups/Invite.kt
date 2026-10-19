package com.curveon.groups

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The secrets that make a device "the same rider" as another: handed over from one of the rider's devices to
 * another so that tablet and phone are one person in the group, not two. Whoever holds it can act as that rider.
 */
class DeviceIdentity(val riderId: ByteArray, val agreementPrivateKey: ByteArray, /** Only for an admin. */ val signingPrivateKey: ByteArray?) {
  init {
    require(riderId.size == 16 && agreementPrivateKey.size == 32 && (signingPrivateKey == null || signingPrivateKey.size == 32))
  }

  internal fun encode(): String = B64.encode(riderId + agreementPrivateKey + (signingPrivateKey ?: ByteArray(0)))

  internal companion object {
    fun decode(s: String): DeviceIdentity {
      val b = B64.decode(s)
      require(b.size == 48 || b.size == 80)
      return DeviceIdentity(b.copyOfRange(0, 16), b.copyOfRange(16, 48), if (b.size == 80) b.copyOfRange(48, 80) else null)
    }
  }
}

/**
 * What a rider needs to join a group. Everything sits in the URI fragment, so a web server never
 * sees it if the link is opened in a browser:
 * `https://<relay>/join#v=1&g=<groupId>&k=<groupKey>&a=<adminPub>&r=<relay>[&n=<name>][&x=<expiry>]`
 * or the same fragment after `curveon://join`.
 */
class Invite(
  val groupId: ByteArray,
  val groupKey: ByteArray,
  val adminPublicKey: ByteArray,
  val relayHost: String,
  val name: String? = null,
  /** Epoch seconds. Enforced by the app only, so it is a soft limit. */
  val expiresAt: Long? = null,
  /** The key epoch `groupKey` belongs to: a group that has had a rider removed is past 0. */
  val epoch: Int = 0,
  /** Set when the link is for the rider's own other device (see [DeviceIdentity]); such a link is secret and short-lived. */
  val identity: DeviceIdentity? = null,
) {
  init {
    require(groupId.size == GROUP_ID_SIZE) { "groupId must be 16 bytes" }
    require(groupKey.size == GROUP_KEY_SIZE) { "groupKey must be 32 bytes" }
    require(adminPublicKey.size == 32) { "adminPublicKey must be 32 bytes" }
    require(relayHost.isNotBlank() && relayHost.none { it.isWhitespace() || it == '/' || it == '#' }) { "bad relay host" }
  }

  fun isExpired(nowSeconds: Long): Boolean = expiresAt != null && nowSeconds > expiresAt

  fun toUri(scheme: String = "https"): String {
    val frag = buildString {
      append("v=$VERSION&g=${B64.encode(groupId)}&k=${B64.encode(groupKey)}&a=${B64.encode(adminPublicKey)}")
      append("&r=").append(enc(relayHost))
      if (name != null) append("&n=").append(enc(name))
      if (expiresAt != null) append("&x=").append(expiresAt)
      if (epoch > 0) append("&e=").append(epoch)
      if (identity != null) append("&me=").append(identity.encode())
    }
    val base = if (scheme == "https") "https://$relayHost/join" else "$scheme://join"
    return "$base#$frag"
  }

  companion object {
    const val VERSION = 1
    const val GROUP_ID_SIZE = 16
    const val GROUP_KEY_SIZE = 32

    /** Returns null for anything that is not a valid invite of a version this build understands. */
    fun parse(uri: String): Invite? = try {
      val frag = uri.substringAfter('#', "")
      val p = frag.split('&').filter { it.isNotEmpty() }.associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
      }
      if (p["v"]?.toIntOrNull() != VERSION) null
      else Invite(
        groupId = B64.decode(p.getValue("g")),
        groupKey = B64.decode(p.getValue("k")),
        adminPublicKey = B64.decode(p.getValue("a")),
        relayHost = p.getValue("r"),
        name = p["n"]?.takeIf { it.isNotEmpty() },
        expiresAt = p["x"]?.toLong(),
        epoch = p["e"]?.toInt() ?: 0,
        identity = p["me"]?.let { DeviceIdentity.decode(it) },
      )
    } catch (_: Exception) {
      null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
  }
}
