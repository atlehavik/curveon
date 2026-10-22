package com.curveon.groups

import com.google.crypto.tink.subtle.Hkdf
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Keys derived from the one secret every rider holds. */
class GroupKeys(val groupId: ByteArray, val groupKey: ByteArray, val epoch: Int = 0) {
  /** AES-256-GCM key for frame payloads. */
  val encryptionKey: ByteArray = derive("curveon/1 enc")
  private val accessKey: ByteArray = derive("curveon/1 access")
  private val slotKey: ByteArray = derive("curveon/1 slot")

  /**
   * Proof that a client knows the group key, sent to the relay when connecting. The relay cannot
   * derive anything from it. It changes with every rekey, which locks a removed rider out.
   */
  fun accessProof(): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(accessKey, "HmacSHA256"))
    mac.update("curveon/1 proof".toByteArray())
    mac.update(groupId)
    mac.update(epochBytes(epoch))
    return B64.encode(mac.doFinal())
  }

  /**
   * The slot the relay keeps a frame in ([Keep]) for [name]: the same for every rider of the group, meaningless to
   * the relay, and new with every rekey (when the relay drops what it kept anyway).
   */
  fun slot(name: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(slotKey, "HmacSHA256"))
    mac.update("curveon/1 slot".toByteArray())
    return B64.encode(mac.doFinal(name.toByteArray()).copyOf(16))
  }

  private fun derive(info: String): ByteArray =
    Hkdf.computeHkdf("HmacSha256", groupKey, groupId, info.toByteArray(), 32)

  companion object {
    private val rng = SecureRandom()
    fun randomBytes(n: Int) = ByteArray(n).also(rng::nextBytes)
    fun newGroupId() = randomBytes(Invite.GROUP_ID_SIZE)
    fun newGroupKey() = randomBytes(Invite.GROUP_KEY_SIZE)
    fun newRiderId() = randomBytes(16)
  }
}

internal fun epochBytes(e: Int) =
  byteArrayOf((e ushr 24).toByte(), (e ushr 16).toByte(), (e ushr 8).toByte(), e.toByte())
