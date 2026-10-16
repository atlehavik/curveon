package com.curveon.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Tablet <-> phone protocol. The phone runs an RFCOMM server ([SERVICE_UUID]); the tablet
 * connects to the paired phone. Each message is one JSON object, framed by [Framing].
 * Images travel as base64 JPEG inside the JSON; they are small (album art ~320 px, avatars ~96 px).
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("6b3a1f6e-2c55-4e0b-9a4b-56a1f0b7c1d2")
    const val SERVICE_NAME = "Curveon Companion"
    const val VERSION = 1
}

sealed interface Message {
    fun toJson(): JSONObject

    // ---- both directions ----
    data class Hello(val deviceName: String, val version: Int = Protocol.VERSION) : Message {
        override fun toJson() = obj("hello").put("device", deviceName).put("version", version)
    }

    data object Ping : Message {
        override fun toJson() = obj("ping")
    }

    // ---- phone -> tablet ----
    data class MediaState(
        val title: String,
        val artist: String,
        val album: String,
        val app: String,
        val durationMs: Long,
        val positionMs: Long,
        val playing: Boolean,
        /** Identifies the current album art; the image itself comes once in [MediaArt]. */
        val artId: String?
    ) : Message {
        override fun toJson() = obj("media").put("title", title).put("artist", artist).put("album", album)
            .put("app", app).put("duration", durationMs).put("position", positionMs).put("playing", playing)
            .put("artId", artId ?: JSONObject.NULL)
    }

    data object MediaStopped : Message {
        override fun toJson() = obj("media_stopped")
    }

    data class MediaArt(val artId: String, val jpegBase64: String) : Message {
        override fun toJson() = obj("media_art").put("artId", artId).put("jpeg", jpegBase64)
    }

    /**
     * The phone's media apps that have a session (playing or paused), so the rider can pick which
     * one to listen to. [MediaSource.package] identifies it in [PlayMediaApp].
     */
    data class MediaSources(val sources: List<MediaSource>) : Message {
        override fun toJson() = obj("media_sources").put("list", JSONArray(sources.map {
            JSONObject().put("pkg", it.packageName).put("app", it.app).put("playing", it.playing)
                .put("icon", it.iconPngBase64 ?: JSONObject.NULL)
        }))
    }

    data class MediaSource(val packageName: String, val app: String, val playing: Boolean, val iconPngBase64: String?)

    /**
     * Every media app installed on the phone that can be started from outside (it offers a media
     * browser, like for Android Auto), running or not. The rider marks favourites among them.
     */
    data class InstalledMediaApps(val apps: List<MediaSource>) : Message {
        override fun toJson() = obj("installed_media_apps").put("list", JSONArray(apps.map {
            JSONObject().put("pkg", it.packageName).put("app", it.app).put("icon", it.iconPngBase64 ?: JSONObject.NULL)
        }))
    }

    /** How the phone is doing, for Settings → Phone on the tablet. Sent on connect and on changes. */
    data class PhoneStatus(
        val model: String,
        val batteryPercent: Int,
        val charging: Boolean,
        /** Without it there are no messages or music on the tablet. */
        val notificationAccess: Boolean,
        val appVersion: String
    ) : Message {
        override fun toJson() = obj("phone_status").put("model", model).put("battery", batteryPercent)
            .put("charging", charging).put("notifications", notificationAccess).put("version", appVersion)
    }

    data class NotificationPosted(
        val key: String,
        val app: String,
        val sender: String,
        val text: String,
        val timeMs: Long,
        val avatarJpegBase64: String?,
        val canReply: Boolean,
        val isCall: Boolean,
        /** Titles of the notification's own actions (e.g. "Answer", "Decline" for calls). */
        val actions: List<String>,
        /** A call that has been answered (as opposed to one that is ringing). An older phone app leaves it out. */
        val callOngoing: Boolean = false
    ) : Message {
        override fun toJson() = obj("notification").put("key", key).put("app", app).put("sender", sender)
            .put("text", text).put("time", timeMs).put("avatar", avatarJpegBase64 ?: JSONObject.NULL)
            .put("canReply", canReply).put("isCall", isCall).put("actions", JSONArray(actions)).put("ongoing", callOngoing)
    }

    data class NotificationRemoved(val key: String) : Message {
        override fun toJson() = obj("notification_removed").put("key", key)
    }

    /** Progress of a spoken reply recorded on the phone. */
    data class VoiceReplyState(val key: String, val state: VoiceState, val text: String = "") : Message {
        override fun toJson() = obj("voice_state").put("key", key).put("state", state.name).put("text", text)
    }

    enum class VoiceState { LISTENING, PARTIAL, RESULT, ERROR, SENT }

    /**
     * The phone book: every contact that has a phone number, sorted by name. Starred contacts are
     * the favourites. Photos aren't included; the tablet asks for the ones it shows ([RequestContactPhotos]).
     * [granted] is false when the companion lacks the contacts permission.
     */
    data class Contacts(val contacts: List<Contact>, val granted: Boolean = true) : Message {
        override fun toJson() = obj("contacts").put("granted", granted)
            .put("list", JSONArray(contacts.map { it.toJson() }))
    }

    data class Contact(
        val id: Long,
        val name: String,
        val starred: Boolean,
        /** Identifies the contact's photo (changes when the photo does); null without one. */
        val photoKey: String?,
        val numbers: List<PhoneNumber>
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("starred", starred)
            .put("photo", photoKey ?: JSONObject.NULL)
            .put("numbers", JSONArray(numbers.map { JSONObject().put("n", it.number).put("l", it.label) }))
    }

    /** [label] is the phone book's type, e.g. "Mobile", "Work". */
    data class PhoneNumber(val number: String, val label: String)

    /** The call log, newest first; repeated calls to/from the same number are folded into [RecentCall.count]. */
    data class RecentCalls(val calls: List<RecentCall>, val granted: Boolean = true) : Message {
        override fun toJson() = obj("recent_calls").put("granted", granted).put("list", JSONArray(calls.map {
            JSONObject().put("number", it.number).put("name", it.name ?: JSONObject.NULL)
                .put("label", it.label).put("photo", it.photoKey ?: JSONObject.NULL)
                .put("kind", it.kind.name).put("time", it.timeMs).put("count", it.count)
        }))
    }

    data class RecentCall(
        val number: String,
        /** Phone-book name; null for unknown numbers. */
        val name: String?,
        val label: String,
        val photoKey: String?,
        val kind: CallKind,
        val timeMs: Long,
        val count: Int = 1
    )

    enum class CallKind { INCOMING, OUTGOING, MISSED, REJECTED }

    /** Answer to [RequestContactPhotos]; [jpegBase64] is null when the photo can't be read. */
    data class ContactPhotos(val photos: List<Photo>) : Message {
        override fun toJson() = obj("contact_photos").put("list", JSONArray(photos.map {
            JSONObject().put("key", it.key).put("jpeg", it.jpegBase64 ?: JSONObject.NULL)
        }))
    }

    data class Photo(val key: String, val jpegBase64: String?)

    /** Something the rider asked for didn't work on the phone, e.g. a call that couldn't be placed. */
    data class PhoneError(val text: String) : Message {
        override fun toJson() = obj("phone_error").put("text", text)
    }

    // ---- tablet -> phone ----
    data class MediaCommand(val command: MediaAction) : Message {
        override fun toJson() = obj("media_command").put("command", command.name)
    }

    enum class MediaAction { PLAY_PAUSE, NEXT, PREVIOUS }

    /** Play this media app (and pause any other that is playing). */
    data class PlayMediaApp(val packageName: String) : Message {
        override fun toJson() = obj("play_media_app").put("pkg", packageName)
    }

    data class DismissNotification(val key: String) : Message {
        override fun toJson() = obj("dismiss").put("key", key)
    }

    data class InvokeAction(val key: String, val actionIndex: Int) : Message {
        override fun toJson() = obj("invoke_action").put("key", key).put("index", actionIndex)
    }

    /** Ask the phone to listen for a spoken reply (through the rider's headset). */
    data class StartVoiceReply(val key: String) : Message {
        override fun toJson() = obj("start_voice_reply").put("key", key)
    }

    data class CancelVoiceReply(val key: String) : Message {
        override fun toJson() = obj("cancel_voice_reply").put("key", key)
    }

    data class SendReply(val key: String, val text: String) : Message {
        override fun toJson() = obj("send_reply").put("key", key).put("text", text)
    }

    /** Ask for [Contacts] and [RecentCalls]. */
    data object RequestPhoneBook : Message {
        override fun toJson() = obj("request_phone_book")
    }

    data class RequestContactPhotos(val keys: List<String>) : Message {
        override fun toJson() = obj("request_photos").put("keys", JSONArray(keys))
    }

    /** Dial [number] on the phone; the call then shows up like any other (call notification). */
    data class PlaceCall(val number: String) : Message {
        override fun toJson() = obj("place_call").put("number", number)
    }

    /** Play a short navigation sound in the rider's headset, in the ear given by [side]. */
    data class PlayCue(val cue: NavCue, val side: CueSide = CueSide.CENTER) : Message {
        override fun toJson() = obj("play_cue").put("cue", cue.name).put("side", side.name)
    }

    /** The navigation sounds: before a turn, at it, arriving, and leaving and rejoining the route. */
    enum class NavCue { PREPARE, TURN, ARRIVE, OFF_ROUTE, BACK_ON_ROUTE, CHOOSE }

    enum class CueSide { LEFT, CENTER, RIGHT }

    companion object {
        private fun obj(type: String) = JSONObject().put("type", type)

        fun fromJson(o: JSONObject): Message? = when (o.optString("type")) {
            "hello" -> Hello(o.getString("device"), o.optInt("version", 1))
            "ping" -> Ping
            "media" -> MediaState(
                o.optString("title"), o.optString("artist"), o.optString("album"), o.optString("app"),
                o.optLong("duration"), o.optLong("position"), o.optBoolean("playing"),
                o.optString("artId").takeIf { it.isNotEmpty() && !o.isNull("artId") }
            )
            "media_stopped" -> MediaStopped
            "media_art" -> MediaArt(o.getString("artId"), o.getString("jpeg"))
            "media_sources" -> MediaSources(o.optJSONArray("list").objects().map {
                MediaSource(it.getString("pkg"), it.optString("app"), it.optBoolean("playing"), it.nullableString("icon"))
            })
            "installed_media_apps" -> InstalledMediaApps(o.optJSONArray("list").objects().map {
                MediaSource(it.getString("pkg"), it.optString("app"), false, it.nullableString("icon"))
            })
            "phone_status" -> PhoneStatus(
                o.optString("model"), o.optInt("battery", -1), o.optBoolean("charging"),
                o.optBoolean("notifications", true), o.optString("version")
            )
            "notification" -> NotificationPosted(
                o.getString("key"), o.optString("app"), o.optString("sender"), o.optString("text"), o.optLong("time"),
                o.optString("avatar").takeIf { it.isNotEmpty() && !o.isNull("avatar") },
                o.optBoolean("canReply"), o.optBoolean("isCall"),
                o.optJSONArray("actions")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                o.optBoolean("ongoing")
            )
            "notification_removed" -> NotificationRemoved(o.getString("key"))
            "voice_state" -> VoiceReplyState(o.getString("key"), VoiceState.valueOf(o.getString("state")), o.optString("text"))
            "media_command" -> MediaCommand(MediaAction.valueOf(o.getString("command")))
            "play_media_app" -> PlayMediaApp(o.getString("pkg"))
            "dismiss" -> DismissNotification(o.getString("key"))
            "invoke_action" -> InvokeAction(o.getString("key"), o.getInt("index"))
            "start_voice_reply" -> StartVoiceReply(o.getString("key"))
            "cancel_voice_reply" -> CancelVoiceReply(o.getString("key"))
            "send_reply" -> SendReply(o.getString("key"), o.getString("text"))
            "contacts" -> Contacts(o.optJSONArray("list").objects().map { c ->
                Contact(
                    c.getLong("id"), c.optString("name"), c.optBoolean("starred"), c.nullableString("photo"),
                    c.optJSONArray("numbers").objects().map { PhoneNumber(it.optString("n"), it.optString("l")) }
                )
            }, o.optBoolean("granted", true))
            "recent_calls" -> RecentCalls(o.optJSONArray("list").objects().map {
                RecentCall(
                    it.optString("number"), it.nullableString("name"), it.optString("label"), it.nullableString("photo"),
                    runCatching { CallKind.valueOf(it.optString("kind")) }.getOrDefault(CallKind.INCOMING),
                    it.optLong("time"), it.optInt("count", 1)
                )
            }, o.optBoolean("granted", true))
            "contact_photos" -> ContactPhotos(o.optJSONArray("list").objects().map { Photo(it.getString("key"), it.nullableString("jpeg")) })
            "phone_error" -> PhoneError(o.optString("text"))
            "request_phone_book" -> RequestPhoneBook
            "request_photos" -> RequestContactPhotos(o.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
            "place_call" -> PlaceCall(o.getString("number"))
            // A cue this version doesn't know (from a newer tablet) is skipped.
            "play_cue" -> runCatching { NavCue.valueOf(o.getString("cue")) }.getOrNull()?.let { cue ->
                PlayCue(cue, runCatching { CueSide.valueOf(o.optString("side")) }.getOrDefault(CueSide.CENTER))
            }
            else -> null // unknown types from a newer peer are ignored
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
