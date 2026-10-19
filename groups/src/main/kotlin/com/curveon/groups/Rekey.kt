package com.curveon.groups

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import javax.crypto.Cipher

class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

object Keys {
  fun newSigningKeyPair(): KeyPair = Ed25519Sign.KeyPair.newKeyPair().let { KeyPair(it.publicKey, it.privateKey) }

  fun newAgreementKeyPair(): KeyPair {
    val priv = X25519.generatePrivateKey()
    return KeyPair(X25519.publicFromPrivate(priv), priv)
  }

  fun agreementKeyPairFrom(privateKey: ByteArray) = KeyPair(X25519.publicFromPrivate(privateKey), privateKey)
}

/** One rider's copy of the new group key, readable only with that rider's X25519 private key. */
class SealedKey(val riderId: ByteArray, val ephemeralPublic: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)

/**
 * Removing a rider: an admin makes a new group key and sends it, separately, to everyone who stays.
 * The message is signed with the admin key from the invite, so a member cannot kick anyone.
 */
class Rekey(
  val groupId: ByteArray,
  val newEpoch: Int,
  /** The relay access proof for the new key, signed too so a member cannot swap it and lock everyone out. */
  val newProof: String,
  val keys: List<SealedKey>,
  val signature: ByteArray,
) {
  fun verify(adminPublicKey: ByteArray): Boolean = try {
    Ed25519Verify(adminPublicKey).verify(signature, signedBytes(groupId, newEpoch, newProof, keys))
    true
  } catch (_: Exception) {
    false
  }

  /** The new group key for [riderId], or null if the signature is wrong or there is no copy for this rider. */
  fun open(adminPublicKey: ByteArray, riderId: ByteArray, agreementPrivateKey: ByteArray): ByteArray? {
    if (!verify(adminPublicKey)) return null
    val mine = keys.firstOrNull { it.riderId.contentEquals(riderId) } ?: return null
    return try {
      val secret = X25519.computeSharedSecret(agreementPrivateKey, mine.ephemeralPublic)
      val key = FrameCrypto.cipher(Cipher.DECRYPT_MODE, wrapKey(secret, groupId), mine.nonce, aad(groupId, riderId, newEpoch))
        .doFinal(mine.ciphertext)
      if (GroupKeys(groupId, key, newEpoch).accessProof() == newProof) key else null
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    /** [recipients] pairs each remaining rider's id with their X25519 public key (from `hello`). */
    fun create(
      groupId: ByteArray, newEpoch: Int, newGroupKey: ByteArray, adminSigningPrivateKey: ByteArray,
      recipients: List<Pair<ByteArray, ByteArray>>,
    ): Rekey {
      val sealed = recipients.map { (riderId, pub) ->
        val eph = Keys.newAgreementKeyPair()
        val secret = X25519.computeSharedSecret(eph.privateKey, pub)
        val nonce = GroupKeys.randomBytes(12)
        val c = FrameCrypto.cipher(Cipher.ENCRYPT_MODE, wrapKey(secret, groupId), nonce, aad(groupId, riderId, newEpoch))
          .doFinal(newGroupKey)
        SealedKey(riderId, eph.publicKey, nonce, c)
      }
      val proof = GroupKeys(groupId, newGroupKey, newEpoch).accessProof()
      val sig = Ed25519Sign(adminSigningPrivateKey).sign(signedBytes(groupId, newEpoch, proof, sealed))
      return Rekey(groupId, newEpoch, proof, sealed, sig)
    }

    private fun wrapKey(secret: ByteArray, groupId: ByteArray) =
      Hkdf.computeHkdf("HmacSha256", secret, groupId, "curveon/1 rekey".toByteArray(), 32)

    private fun aad(groupId: ByteArray, riderId: ByteArray, epoch: Int) =
      "curveon/1 rekey".toByteArray() + groupId + riderId + epochBytes(epoch)

    private fun signedBytes(groupId: ByteArray, epoch: Int, proof: String, keys: List<SealedKey>): ByteArray {
      var out = "curveon/1 rekey-sig".toByteArray() + groupId + epochBytes(epoch) + proof.toByteArray()
      for (k in keys) out += k.riderId + k.ephemeralPublic + k.nonce + k.ciphertext
      return out
    }
  }
}
