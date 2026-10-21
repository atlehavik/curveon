package com.curveon.groups

import java.util.Base64

/** Unpadded URL-safe base64, as used everywhere in the group wire format. */
internal object B64 {
  private val enc = Base64.getUrlEncoder().withoutPadding()
  private val dec = Base64.getUrlDecoder()
  fun encode(b: ByteArray): String = enc.encodeToString(b)
  fun decode(s: String): ByteArray = dec.decode(s)
}
