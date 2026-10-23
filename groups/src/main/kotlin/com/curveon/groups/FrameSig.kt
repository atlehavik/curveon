package com.curveon.groups

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify

/**
 * The sender's signature on a payload. Everyone in a group holds the group key, so without it any rider could send in
 * another rider's name. It goes in as the last two fields of the payload JSON, `sk` (the rider's Ed25519 key, see
 * [Keys.riderSigningKeyPair]) and `sg` (the signature over the JSON before them), which older apps ignore
 * (docs/groups-protocol.md, "Who sent it").
 */
object FrameSig {
  private const val KEY_CHARS = 43
  private const val SIG_CHARS = 86
  private const val TAIL_CHARS = 7 + KEY_CHARS + 8 + SIG_CHARS + 2 // ,"sk":"…","sg":"…"}

  class Signature(val key: ByteArray, val valid: Boolean)

  fun sign(body: ByteArray, signing: KeyPair, groupId: ByteArray, riderId: ByteArray, epoch: Int, type: FrameType): ByteArray {
    val sg = Ed25519Sign(signing.privateKey).sign(signedBytes(body, groupId, riderId, epoch, type))
    return (String(body).dropLast(1) + ",\"sk\":\"${B64.encode(signing.publicKey)}\",\"sg\":\"${B64.encode(sg)}\"}").toByteArray()
  }

  /** The signature at the end of [plain], checked; null when there is none (sent by an app from before signing). */
  fun read(plain: ByteArray, groupId: ByteArray, riderId: ByteArray, epoch: Int, type: FrameType): Signature? {
    val text = String(plain)
    if (text.length < TAIL_CHARS + 2) return null
    val cut = text.length - TAIL_CHARS
    val tail = text.substring(cut)
    if (!tail.startsWith(",\"sk\":\"") || tail[7 + KEY_CHARS] != '"' || !tail.startsWith("\",\"sg\":\"", 7 + KEY_CHARS) || !tail.endsWith("\"}")) return null
    return try {
      val key = B64.decode(tail.substring(7, 7 + KEY_CHARS))
      val sg = B64.decode(tail.substring(7 + KEY_CHARS + 8, 7 + KEY_CHARS + 8 + SIG_CHARS))
      val body = (text.substring(0, cut) + "}").toByteArray()
      val valid = try {
        Ed25519Verify(key).verify(sg, signedBytes(body, groupId, riderId, epoch, type)); true
      } catch (_: Exception) {
        false
      }
      Signature(key, valid)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun signedBytes(body: ByteArray, groupId: ByteArray, riderId: ByteArray, epoch: Int, type: FrameType) =
    "curveon/1 frame-sig".toByteArray() + groupId + riderId + epochBytes(epoch) + type.wire.toByteArray() + byteArrayOf(0) + body
}
