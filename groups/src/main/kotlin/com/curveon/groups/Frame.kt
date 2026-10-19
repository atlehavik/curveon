package com.curveon.groups

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Frame types the relay forwards. Everything else is dropped. `hello`, `route`, `sos`, `claim` and `rekey` are also stored and replayed. */
enum class FrameType(val wire: String) {
  POS("pos"), HELLO("hello"), ROUTE("route"), SOS("sos"), SOS_ACK("sos_ack"), SOS_CANCEL("sos_cancel"),
  LEAVE("leave"), CLAIM("claim"), REKEY("rekey");

  companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } }
}

/** One message on the wire, the part the relay sees. */
class Frame(val type: FrameType, val riderId: ByteArray, val epoch: Int, val nonce: ByteArray, val ciphertext: ByteArray)

/** AES-256-GCM with a fresh random nonce. Group id, rider, epoch and type are authenticated, not hidden. */
object FrameCrypto {
  private val rng = SecureRandom()

  fun seal(keys: GroupKeys, type: FrameType, riderId: ByteArray, plaintext: ByteArray): Frame {
    val nonce = ByteArray(12).also(rng::nextBytes)
    val c = cipher(Cipher.ENCRYPT_MODE, keys.encryptionKey, nonce, aad(keys.groupId, riderId, keys.epoch, type))
    return Frame(type, riderId, keys.epoch, nonce, c.doFinal(plaintext))
  }

  /** Null when the frame was not made with this key, or was altered or moved to another group, rider or type. */
  fun open(keys: GroupKeys, frame: Frame): ByteArray? {
    if (frame.epoch != keys.epoch) return null
    return try {
      cipher(Cipher.DECRYPT_MODE, keys.encryptionKey, frame.nonce, aad(keys.groupId, frame.riderId, frame.epoch, frame.type))
        .doFinal(frame.ciphertext)
    } catch (_: AEADBadTagException) {
      null
    }
  }

  internal fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
      updateAAD(aad)
    }

  private fun aad(groupId: ByteArray, riderId: ByteArray, epoch: Int, type: FrameType) =
    "curveon/1".toByteArray() + groupId + riderId + epochBytes(epoch) + type.wire.toByteArray()
}
