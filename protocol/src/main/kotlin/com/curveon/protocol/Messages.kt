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
        val actions: List<String>
    ) : Message {
        override fun toJson() = obj("notification").put("key", key).put("app", app).put("sender", sender)
            .put("text", text).put("time", timeMs).put("avatar", avatarJpegBase64 ?: JSONObject.NULL)
            .put("canReply", canReply).put("isCall", isCall).put("actions", JSONArray(actions))
    }

    data class NotificationRemoved(val key: String) : Message {
        override fun toJson() = obj("notification_removed").put("key", key)
    }

    /** Progress of a spoken reply recorded on the phone. */
    data class VoiceReplyState(val key: String, val state: VoiceState, val text: String = "") : Message {
        override fun toJson() = obj("voice_state").put("key", key).put("state", state.name).put("text", text)
    }

    enum class VoiceState { LISTENING, PARTIAL, RESULT, ERROR, SENT }

    // ---- tablet -> phone ----
    data class MediaCommand(val command: MediaAction) : Message {
        override fun toJson() = obj("media_command").put("command", command.name)
    }

    enum class MediaAction { PLAY_PAUSE, NEXT, PREVIOUS }

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
            "notification" -> NotificationPosted(
                o.getString("key"), o.optString("app"), o.optString("sender"), o.optString("text"), o.optLong("time"),
                o.optString("avatar").takeIf { it.isNotEmpty() && !o.isNull("avatar") },
                o.optBoolean("canReply"), o.optBoolean("isCall"),
                o.optJSONArray("actions")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            )
            "notification_removed" -> NotificationRemoved(o.getString("key"))
            "voice_state" -> VoiceReplyState(o.getString("key"), VoiceState.valueOf(o.getString("state")), o.optString("text"))
            "media_command" -> MediaCommand(MediaAction.valueOf(o.getString("command")))
            "dismiss" -> DismissNotification(o.getString("key"))
            "invoke_action" -> InvokeAction(o.getString("key"), o.getInt("index"))
            "start_voice_reply" -> StartVoiceReply(o.getString("key"))
            "cancel_voice_reply" -> CancelVoiceReply(o.getString("key"))
            "send_reply" -> SendReply(o.getString("key"), o.getString("text"))
            else -> null // unknown types from a newer peer are ignored
        }
    }
}
