package com.curveon.groups

import org.json.JSONObject

/**
 * What a group is for. A [RIDE] is a group of riders on the road; a [FRIEND] is a private channel between two people who
 * have scanned each other: it carries their profiles, and invites to ride groups from one to the other.
 */
enum class GroupKind { RIDE, FRIEND }

/** The other person in a [GroupKind.FRIEND] channel, as last heard (kept so the list of friends shows them offline). */
class PeerProfile(val nickname: String, val colour: Int, val icon: String?, val photoBase64: String?, val riderId: String? = null)

/** Someone in the group, as known from their `hello`. */
class Member(val riderId: ByteArray, val nickname: String, val colour: Int, val agreementPublicKey: ByteArray, val avatar: Avatar?) {
  val id: String get() = B64.encode(riderId)
}

/**
 * Everything one device keeps about one group: the secrets and the rider's own identity in it. The
 * group lives only here (the relay forgets it), so [toJson] is what the app stores, encrypted at rest.
 * Members are not stored; the relay replays their `hello` when the device connects.
 */
class GroupState(
  val groupId: ByteArray,
  groupKey: ByteArray,
  epoch: Int,
  val adminPublicKey: ByteArray,
  val relayHost: String,
  var name: String?,
  val riderId: ByteArray,
  val agreement: KeyPair,
  /** Only the admin holds this; it signs rekeys. */
  val adminSigning: KeyPair?,
  /** Tells this device from the rider's other devices (see [Payload.Claim]); never shared between them. */
  val deviceId: String = B64.encode(GroupKeys.randomBytes(8)),
  val kind: GroupKind = GroupKind.RIDE,
) {
  /** For a friend channel: who is at the other end, once they have said hello. */
  var peer: PeerProfile? = null

  var groupKey: ByteArray = groupKey
    private set
  var epoch: Int = epoch
    private set

  val isAdmin: Boolean get() = adminSigning != null
  val keys: GroupKeys get() = GroupKeys(groupId, groupKey, epoch)
  val groupIdHex: String get() = groupId.joinToString("") { "%02x".format(it) }

  fun switchKey(newKey: ByteArray, newEpoch: Int) {
    groupKey = newKey
    epoch = newEpoch
  }

  /** [expiresAt] in epoch seconds; the app checks it, the relay does not. */
  fun invite(expiresAt: Long? = null) = Invite(groupId, groupKey, adminPublicKey, relayHost, name, expiresAt, epoch, kind = kind)

  /**
   * A link for the rider's own other device: opening it there makes that device the same rider (same id and keys)
   * instead of a new one. It carries this rider's private keys, so it is shown only on screen and expires soon.
   */
  fun deviceLink(expiresAt: Long) = Invite(
    groupId, groupKey, adminPublicKey, relayHost, name, expiresAt, epoch,
    DeviceIdentity(riderId, agreement.privateKey, adminSigning?.privateKey), kind,
  )

  fun toJson(): String = JSONObject().put("groupId", B64.encode(groupId)).put("key", B64.encode(groupKey)).put("epoch", epoch)
    .put("admin", B64.encode(adminPublicKey)).put("relay", relayHost).put("name", name ?: JSONObject.NULL)
    .put("rider", B64.encode(riderId)).put("agreementPub", B64.encode(agreement.publicKey))
    .put("agreementPriv", B64.encode(agreement.privateKey))
    .put("signingPriv", adminSigning?.let { B64.encode(it.privateKey) } ?: JSONObject.NULL).put("device", deviceId).put("kind", kind.name)
    .put("peer", peer?.let { JSONObject().put("nick", it.nickname).put("colour", it.colour).put("icon", it.icon ?: JSONObject.NULL).put("photo", it.photoBase64 ?: JSONObject.NULL).put("rider", it.riderId ?: JSONObject.NULL) } ?: JSONObject.NULL)
    .toString()

  companion object {
    /** A new group: this rider becomes its admin. */
    fun create(relayHost: String, name: String?, kind: GroupKind = GroupKind.RIDE): GroupState {
      val signing = Keys.newSigningKeyPair()
      return GroupState(
        GroupKeys.newGroupId(), GroupKeys.newGroupKey(), 0, signing.publicKey, relayHost, name,
        GroupKeys.newRiderId(), Keys.newAgreementKeyPair(), signing, kind = kind,
      )
    }

    /**
     * Joining from an invite: a fresh rider id and key pair for this group only, or, from a [GroupState.deviceLink],
     * the identity of the rider's other device.
     */
    fun join(invite: Invite): GroupState {
      val me = invite.identity
      return GroupState(
        invite.groupId, invite.groupKey, invite.epoch, invite.adminPublicKey, invite.relayHost, invite.name,
        me?.riderId ?: GroupKeys.newRiderId(),
        me?.let { Keys.agreementKeyPairFrom(it.agreementPrivateKey) } ?: Keys.newAgreementKeyPair(),
        me?.signingPrivateKey?.let { KeyPair(invite.adminPublicKey, it) },
        kind = invite.kind,
      )
    }

    fun fromJson(text: String): GroupState? = try {
      val o = JSONObject(text)
      val signingPriv = if (o.isNull("signingPriv")) null else B64.decode(o.getString("signingPriv"))
      val admin = B64.decode(o.getString("admin"))
      GroupState(
        B64.decode(o.getString("groupId")), B64.decode(o.getString("key")), o.getInt("epoch"), admin,
        o.getString("relay"), if (o.isNull("name")) null else o.getString("name"), B64.decode(o.getString("rider")),
        KeyPair(B64.decode(o.getString("agreementPub")), B64.decode(o.getString("agreementPriv"))),
        signingPriv?.let { KeyPair(admin, it) },
        o.optString("device").ifEmpty { B64.encode(GroupKeys.randomBytes(8)) },
        runCatching { GroupKind.valueOf(o.optString("kind", "RIDE")) }.getOrDefault(GroupKind.RIDE),
      ).also { st ->
        o.optJSONObject("peer")?.let { p ->
          st.peer = PeerProfile(
            p.optString("nick"), p.optInt("colour"), if (p.isNull("icon")) null else p.optString("icon"),
            if (p.isNull("photo")) null else p.optString("photo"), if (p.isNull("rider")) null else p.optString("rider"),
          )
        }
      }
    } catch (_: Exception) {
      null
    }
  }
}
