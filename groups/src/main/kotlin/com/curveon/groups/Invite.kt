package com.curveon.groups

import java.net.URLDecoder
import java.net.URLEncoder

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
      )
    } catch (_: Exception) {
      null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
  }
}
