package com.curveon.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.curveon.groups.GroupState
import com.curveon.protocol.Message
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The relay Curveon runs, used unless the rider sets another one under Advanced. */
const val DEFAULT_RELAY_HOST = "relay.curveon.no"

/**
 * What the phone remembers about group rides. The groups (keys and the rider's identity in each) are
 * encrypted with a key in the Android Keystore, which never leaves the device, so a copy of the app's
 * files is useless. A group that cannot be decrypted (restored to another phone) is dropped: the rider
 * needs a new invite, as when the phone is lost.
 */
class GroupStore(context: Context) {
    private val prefs = context.getSharedPreferences("groups", Context.MODE_PRIVATE)

    fun loadGroups(): List<GroupState> = prefs.all.keys.filter { it.startsWith(GROUP) }.mapNotNull { key ->
        decrypt(prefs.getString(key, null))?.let { GroupState.fromJson(it) }
            ?: run { prefs.edit().remove(key).apply(); null }
    }

    fun save(state: GroupState) {
        encrypt(state.toJson())?.let { prefs.edit().putString(GROUP + state.groupIdHex, it).apply() }
    }

    fun delete(groupIdHex: String) = prefs.edit().remove(GROUP + groupIdHex).apply()

    /** What this device last knew of the rider's shared friends, groups and profile ([com.curveon.groups.SyncDoc] as JSON). */
    var syncDoc: String?
        get() = prefs.getString(SYNC, null)
        set(v) = prefs.edit().putString(SYNC, v).apply()

    var activeId: String?
        get() = prefs.getString(ACTIVE, null)
        set(v) = prefs.edit().putString(ACTIVE, v).apply()

    /** False while the rider has paused sharing; the groups stay. */
    var sharing: Boolean
        get() = prefs.getBoolean(SHARING, true)
        set(v) = prefs.edit().putBoolean(SHARING, v).apply()

    /** A relay the rider chose themselves (someone running their own); empty to use [DEFAULT_RELAY_HOST]. */
    var customRelay: String
        get() = prefs.getString(RELAY, "").orEmpty()
        set(v) = prefs.edit().putString(RELAY, v.trim()).apply()

    /** Host of the relay new groups are made on: the rider's own if set, else Curveon's (invites carry their own). */
    val relayHost: String get() = customRelay.ifBlank { DEFAULT_RELAY_HOST }

    /** Invites from friends that have been answered (or are out of date): each is shown once, although the relay keeps them for a week. */
    fun isOfferHandled(id: String) = id in handledOffers()

    fun markOfferHandled(id: String) = prefs.edit().putString(HANDLED, (handledOffers() + id).takeLast(200).joinToString(",")).apply()

    private fun handledOffers() = prefs.getString(HANDLED, "").orEmpty().split(',').filter { it.isNotEmpty() }

    var dataSaver: Boolean
        get() = prefs.getBoolean(DATA_SAVER, false)
        set(v) = prefs.edit().putBoolean(DATA_SAVER, v).apply()

    /** Nickname, colour and avatar shown to every group. Not secret, as the others see it anyway. */
    var profile: Message.GroupProfile?
        get() = prefs.getString(PROFILE, null)?.let { runCatching { Message.fromJson(org.json.JSONObject(it)) as? Message.GroupProfile }.getOrNull() }
        set(v) = prefs.edit().putString(PROFILE, v?.toJson()?.toString()).apply()

    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }.getOrNull()

    private fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(stored: String?): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
        String(c.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()

    private companion object {
        const val GROUP = "g:"
        const val ACTIVE = "active"
        const val SHARING = "sharing"
        const val RELAY = "relay"
        const val SYNC = "sync_doc"
        const val DATA_SAVER = "data_saver"
        const val HANDLED = "handled_offers"
        const val PROFILE = "profile"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "curveon_groups"
    }
}
