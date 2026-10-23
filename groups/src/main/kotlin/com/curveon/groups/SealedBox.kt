package com.curveon.groups

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import javax.crypto.Cipher

/**
 * Something only one rider can read. Everyone in a group holds the group key, so a secret meant for one rider (a private
 * friend channel) is sealed to that rider's X25519 key from `hello`, as [Rekey] does with the new group key. [context]
 * (who sends to whom) is authenticated, so a box cannot be lifted into another message.
 */
class SealedBox(val ephemeralPublic: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray) {

  /** The plain bytes, or null when the box was not sealed to this key or [groupId], [context] or the box was altered. */
  fun open(recipientPrivateKey: ByteArray, groupId: ByteArray, context: ByteArray): ByteArray? = try {
    val secret = X25519.computeSharedSecret(recipientPrivateKey, ephemeralPublic)
    FrameCrypto.cipher(Cipher.DECRYPT_MODE, wrapKey(secret, groupId), nonce, aad(groupId, context)).doFinal(ciphertext)
  } catch (_: Exception) {
    null
  }

  companion object {
    fun seal(plaintext: ByteArray, recipientPublicKey: ByteArray, groupId: ByteArray, context: ByteArray): SealedBox {
      val eph = Keys.newAgreementKeyPair()
      val secret = X25519.computeSharedSecret(eph.privateKey, recipientPublicKey)
      val nonce = GroupKeys.randomBytes(12)
      val c = FrameCrypto.cipher(Cipher.ENCRYPT_MODE, wrapKey(secret, groupId), nonce, aad(groupId, context)).doFinal(plaintext)
      return SealedBox(eph.publicKey, nonce, c)
    }

    private fun wrapKey(secret: ByteArray, groupId: ByteArray) =
      Hkdf.computeHkdf("HmacSha256", secret, groupId, "curveon/1 sealed".toByteArray(), 32)

    private fun aad(groupId: ByteArray, context: ByteArray) = "curveon/1 sealed".toByteArray() + groupId + context
  }
}
