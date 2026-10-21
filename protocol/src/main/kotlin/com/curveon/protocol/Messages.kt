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
    /** 2: group rides. A peer on version 1 ignores the group messages, so mixed versions keep working. */
    const val VERSION = 2
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

    /**
     * How the phone can speak [language] (sent after each [Speak]): with a voice on the phone, only
     * with a connection, or not at all (the sounds play instead). Lets the tablet suggest a download.
     */
    data class SpeechVoice(val language: String, val availability: VoiceAvailability) : Message {
        override fun toJson() = obj("speech_voice").put("lang", language).put("availability", availability.name)
    }

    enum class VoiceAvailability { OFFLINE, ONLINE_ONLY, MISSING }

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

    /**
     * Say [text] in the rider's headset, in [language] (e.g. "nb-NO"), with the phone's own
     * text-to-speech. [interrupt] cuts off what is being said. Without a voice for the language the
     * phone plays [cue] instead (also what an older phone that doesn't know this message misses).
     */
    data class Speak(
        val text: String, val language: String, val interrupt: Boolean = false,
        val cue: NavCue, val side: CueSide = CueSide.CENTER
    ) : Message {
        override fun toJson() = obj("speak").put("text", text).put("lang", language).put("interrupt", interrupt)
            .put("cue", cue.name).put("side", side.name)
    }

    /** The navigation sounds: before a turn, at it, arriving, and leaving and rejoining the route. */
    enum class NavCue { PREPARE, TURN, ARRIVE, OFF_ROUTE, BACK_ON_ROUTE, CHOOSE }

    enum class CueSide { LEFT, CENTER, RIGHT }

    // ---- groups, tablet -> phone (docs/groups-protocol.md; the phone talks to the relay, the tablet only draws) ----

    /** Ask for [GroupList] and the state of the active group. */
    data object RequestGroups : Message {
        override fun toJson() = obj("request_groups")
    }

    /** Start a group; this rider becomes its admin. The phone answers with [GroupList] and [GroupInvite]. */
    data class GroupCreate(val name: String?) : Message {
        override fun toJson() = obj("group_create").put("name", name ?: JSONObject.NULL)
    }

    /** Join from an invite link scanned or opened on the phone, or typed in on the tablet. */
    data class GroupJoin(val invite: String) : Message {
        override fun toJson() = obj("group_join").put("invite", invite)
    }

    data class GroupLeave(val groupId: String) : Message {
        override fun toJson() = obj("group_leave").put("id", groupId)
    }

    /** Make this the group that gets the rider's position and is shown on the map; null for none. */
    data class GroupSelect(val groupId: String?) : Message {
        override fun toJson() = obj("group_select").put("id", groupId ?: JSONObject.NULL)
    }

    /** Start or pause sharing the rider's position with the active group. The group stays either way. */
    data class GroupSharing(val on: Boolean) : Message {
        override fun toJson() = obj("group_sharing").put("on", on)
    }

    /** What others see: nickname, colour and one avatar ([icon] id from the bundled set, or a small [photoJpegBase64]). */
    data class GroupProfile(val nickname: String, val colour: Int, val icon: String?, val photoJpegBase64: String?) : Message {
        override fun toJson() = obj("group_profile").put("nick", nickname).put("colour", colour)
            .put("icon", icon ?: JSONObject.NULL).put("photo", photoJpegBase64 ?: JSONObject.NULL)
    }

    /** The tablet's position, sent while sharing. The phone decides what is sent on and how often. */
    data class MyPosition(
        val lat: Double, val lon: Double, val speedMs: Float, val heading: Float, val accuracy: Float, val timeMs: Long
    ) : Message {
        override fun toJson() = obj("my_position").put("lat", lat).put("lon", lon).put("speed", speedMs.toDouble())
            .put("heading", heading.toDouble()).put("acc", accuracy.toDouble()).put("time", timeMs)
    }

    /** Share a route or GPX track ([gpxGzBase64] is gzipped GPX) with the active group. */
    data class ShareRoute(val name: String, val gpxGzBase64: String) : Message {
        override fun toJson() = obj("share_route").put("name", name).put("gpx", gpxGzBase64)
    }

    data class SosStart(val lat: Double, val lon: Double) : Message {
        override fun toJson() = obj("sos_start").put("lat", lat).put("lon", lon)
    }

    data object SosCancel : Message {
        override fun toJson() = obj("sos_cancel")
    }

    /** "I'm coming" ([coming]) to the SOS of [sosRiderId]; false just says it was seen. */
    data class SosAnswer(val sosRiderId: String, val coming: Boolean) : Message {
        override fun toJson() = obj("sos_answer").put("sos", sosRiderId).put("coming", coming)
    }

    /**
     * What the phone side is set up with: a relay of the rider's own ([relayHost], empty to use the standard one) and whether
     * to share the position less often ([dataSaver]). The tablet sends it to change them, and the phone sends it back
     * (also when the tablet connects), so the screen shows what is really set. An invite carries its own relay.
     */
    data class GroupSettings(val relayHost: String, val dataSaver: Boolean) : Message {
        override fun toJson() = obj("group_settings").put("relay", relayHost).put("saver", dataSaver)
    }

    /** The rider has two devices in the group and chose this one to send their position; the other one is told to stop. */
    data object GroupUseThisDevice : Message {
        override fun toJson() = obj("group_use_device")
    }

    /** Admin only: give everyone but [riderId] a new group key. */
    data class GroupRemoveRider(val groupId: String, val riderId: String) : Message {
        override fun toJson() = obj("group_remove").put("id", groupId).put("rider", riderId)
    }

    /** Ask for an invite for the group; the phone answers with [GroupInvite]. [validDays] null for the default. */
    data class RequestGroupInvite(val groupId: String, val validDays: Int?) : Message {
        override fun toJson() = obj("request_invite").put("id", groupId).put("days", validDays ?: JSONObject.NULL)
    }

    /**
     * Ask for a link that makes the rider's other device the same rider in [groupId] instead of a new one;
     * the phone answers with [GroupDeviceLink]. The link carries the rider's secrets: show it only as a QR code.
     */
    data class RequestDeviceLink(val groupId: String) : Message {
        override fun toJson() = obj("request_device_link").put("id", groupId)
    }

    // ---- friends and shared trips, tablet -> phone ----

    /** Ask for [FriendList] (and make the phone look for invites its friends have sent). */
    data object RequestFriends : Message {
        override fun toJson() = obj("request_friends")
    }

    /** "Show my code": a new private channel for a friend to join. The phone answers with [FriendInvite]. */
    data object RequestFriendInvite : Message {
        override fun toJson() = obj("request_friend_invite")
    }

    /** Stop being friends with the one in [channelId]. */
    data class FriendRemove(val channelId: String) : Message {
        override fun toJson() = obj("friend_remove").put("id", channelId)
    }

    /** Offer the ride group [groupId] to these friends (their channel ids): each gets an invite to answer. */
    data class FriendAddToGroup(val groupId: String, val friendIds: List<String>) : Message {
        override fun toJson() = obj("friend_add_to_group").put("id", groupId).put("friends", JSONArray(friendIds))
    }

    /** The rider's answer to a [GroupInviteOffer]: join the group, or not. */
    data class GroupInviteAnswer(val offerId: String, val accept: Boolean) : Message {
        override fun toJson() = obj("group_invite_answer").put("offer", offerId).put("accept", accept)
    }

    /** A trip made or changed here, for the active group; [rev] only goes up and the highest wins ([doc] is JSON the app defines). */
    data class TripShare(val tripId: String, val rev: Long, val name: String, val doc: String) : Message {
        override fun toJson() = obj("trip_share").put("trip", tripId).put("rev", rev).put("name", name).put("doc", doc)
    }

    // ---- friends and shared trips, phone -> tablet ----

    /** The friends, with who they are as last heard (the picture is the same small one riders have). */
    data class FriendList(val friends: List<FriendInfo>) : Message {
        override fun toJson() = obj("friend_list").put("list", JSONArray(friends.map {
            JSONObject().put("id", it.id).put("nick", it.nickname).put("colour", it.colour)
                .put("icon", it.icon ?: JSONObject.NULL).put("photo", it.photoJpegBase64 ?: JSONObject.NULL)
        }))
    }

    data class FriendInfo(val id: String, val nickname: String, val colour: Int, val icon: String?, val photoJpegBase64: String?)

    /** The answer to [RequestFriendInvite]: the link to show as a QR code or share. It is for one friend. */
    data class FriendInvite(val channelId: String, val uri: String) : Message {
        override fun toJson() = obj("friend_invite").put("id", channelId).put("uri", uri)
    }

    /** A friend asks the rider to join a ride group: shown once, with "join" or "ignore" ([GroupInviteAnswer]). */
    data class GroupInviteOffer(
        val offerId: String, val fromNickname: String, val groupName: String?, val fromPhotoJpegBase64: String?, val fromColour: Int
    ) : Message {
        override fun toJson() = obj("group_invite_offer").put("offer", offerId).put("from", fromNickname)
            .put("group", groupName ?: JSONObject.NULL).put("photo", fromPhotoJpegBase64 ?: JSONObject.NULL).put("colour", fromColour)
    }

    /** A trip shared with the active group, new or changed, by [from] ([mine]: by the rider's own other device). */
    data class GroupTrip(
        val groupId: String, val tripId: String, val rev: Long, val from: String, val fromNickname: String?,
        val name: String, val doc: String, val mine: Boolean
    ) : Message {
        override fun toJson() = obj("group_trip").put("id", groupId).put("trip", tripId).put("rev", rev).put("from", from)
            .put("nick", fromNickname ?: JSONObject.NULL).put("name", name).put("doc", doc).put("mine", mine)
    }

    // ---- the rider's own devices: one set of friends, groups and profile on all of them ----

    /** UI -> hub: a link for the rider's other device to scan; makes the private channel between them if there is none. Answered with [SelfLink]. */
    data object RequestSelfLink : Message {
        override fun toJson() = obj("request_self_link")
    }

    /** Hub -> UI: the link for the other device (short-lived: show it as a QR code only). */
    data class SelfLink(val uri: String) : Message {
        override fun toJson() = obj("self_link").put("uri", uri)
    }

    /** UI -> hub: stop keeping this device in step with the other one (what is here stays here). */
    data object SelfUnlink : Message {
        override fun toJson() = obj("self_unlink")
    }

    /** Hub -> UI: whether the rider's other device is linked ([waiting]: a link was made but the other device has not answered yet). */
    data class SelfStatus(val linked: Boolean, val waiting: Boolean) : Message {
        override fun toJson() = obj("self_status").put("linked", linked).put("waiting", waiting)
    }

    // ---- the relay through the phone: a tablet with no internet of its own reaches the relay over the Bluetooth link ----

    /** Tablet -> phone: open a WebSocket to [url] and call it [id]; the phone answers [RelayOpened] or [RelayClosed]. */
    data class RelayOpen(val id: Int, val url: String) : Message {
        override fun toJson() = obj("relay_open").put("sock", id).put("url", url)
    }

    /** Tablet -> phone: a text frame for socket [id]. */
    data class RelaySend(val id: Int, val text: String) : Message {
        override fun toJson() = obj("relay_send").put("sock", id).put("text", text)
    }

    /** Tablet -> phone: close socket [id] with the WebSocket close [code]. */
    data class RelayClose(val id: Int, val code: Int) : Message {
        override fun toJson() = obj("relay_close").put("sock", id).put("code", code)
    }

    /** Phone -> tablet: socket [id] is open. */
    data class RelayOpened(val id: Int) : Message {
        override fun toJson() = obj("relay_opened").put("sock", id)
    }

    /** Phone -> tablet: a text frame from socket [id]. */
    data class RelayText(val id: Int, val text: String) : Message {
        override fun toJson() = obj("relay_text").put("sock", id).put("text", text)
    }

    /** Phone -> tablet: socket [id] closed, with the WebSocket close [code] (-1 if it failed). */
    data class RelayClosed(val id: Int, val code: Int) : Message {
        override fun toJson() = obj("relay_closed").put("sock", id).put("code", code)
    }

    // ---- groups, phone -> tablet ----

    /** Every group on the phone, and which one is active. [sharing] is false while paused. */
    data class GroupList(val groups: List<GroupInfo>, val activeId: String?, val sharing: Boolean) : Message {
        override fun toJson() = obj("group_list").put("active", activeId ?: JSONObject.NULL).put("sharing", sharing)
            .put("list", JSONArray(groups.map {
                JSONObject().put("id", it.id).put("name", it.name ?: JSONObject.NULL).put("members", it.members).put("admin", it.admin)
            }))
    }

    data class GroupInfo(val id: String, val name: String?, val members: Int, val admin: Boolean)

    /**
     * How the active group's link is doing. [otherDeviceActive]: another of the rider's own devices is the one sending
     * their position, so this one keeps quiet until the rider takes over ([GroupUseThisDevice]).
     */
    data class GroupConnection(val groupId: String, val status: GroupStatus, val members: Int, val otherDeviceActive: Boolean = false) : Message {
        override fun toJson() = obj("group_connection").put("id", groupId).put("status", status.name).put("members", members)
            .put("other", otherDeviceActive)
    }

    enum class GroupStatus { CONNECTED, OFFLINE, PAUSED, REMOVED }

    /** Sent when someone joins or changes their profile; the tablet caches the pictures per rider. The first entry is the rider themself. */
    data class GroupMembers(val groupId: String, val members: List<GroupMemberInfo>) : Message {
        override fun toJson() = obj("group_members").put("id", groupId).put("list", JSONArray(members.map {
            JSONObject().put("id", it.id).put("nick", it.nickname).put("colour", it.colour)
                .put("icon", it.icon ?: JSONObject.NULL).put("photo", it.photoJpegBase64 ?: JSONObject.NULL)
        }))
    }

    data class GroupMemberInfo(val id: String, val nickname: String, val colour: Int, val icon: String?, val photoJpegBase64: String?)

    /** Small and frequent; riders are referred to by id (see [GroupMembers]). */
    data class GroupPositions(val groupId: String, val positions: List<GroupPositionInfo>) : Message {
        override fun toJson() = obj("group_positions").put("id", groupId).put("list", JSONArray(positions.map {
            JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon).put("heading", it.heading.toDouble())
                .put("speed", it.speedMs.toDouble()).put("time", it.timeMs).put("stopped", it.stopped)
                .put("since", it.stoppedSince ?: JSONObject.NULL).put("bat", it.battery ?: JSONObject.NULL)
        }))
    }

    data class GroupPositionInfo(
        val id: String, val lat: Double, val lon: Double, val heading: Float, val speedMs: Float, val timeMs: Long,
        val stopped: Boolean, val stoppedSince: Long?, val battery: Int?
    )

    /**
     * An SOS in the group, sent again whenever its answers change. [mine] is the rider's own, where [acks]
     * counts who has seen it ([SosAckInfo.coming] false) and who is on the way. [active] false means it ended.
     */
    data class GroupSos(
        val groupId: String, val from: String, val lat: Double, val lon: Double, val timeMs: Long,
        val active: Boolean, val mine: Boolean, val acks: List<SosAckInfo>, val memberCount: Int
    ) : Message {
        override fun toJson() = obj("group_sos").put("id", groupId).put("from", from).put("lat", lat).put("lon", lon)
            .put("time", timeMs).put("active", active).put("mine", mine).put("members", memberCount)
            .put("acks", JSONArray(acks.map { JSONObject().put("rider", it.riderId).put("coming", it.coming) }))
    }

    data class SosAckInfo(val riderId: String, val coming: Boolean)

    /** A route or track a rider shared; the tablet offers to open or save it. */
    data class GroupRoute(val groupId: String, val from: String, val name: String, val gpxGzBase64: String) : Message {
        override fun toJson() = obj("group_route").put("id", groupId).put("from", from).put("name", name).put("gpx", gpxGzBase64)
    }

    /** The invite for [groupId], to show as a QR code or share as a link. */
    data class GroupInvite(val groupId: String, val uri: String) : Message {
        override fun toJson() = obj("group_invite").put("id", groupId).put("uri", uri)
    }

    /** The answer to [RequestDeviceLink]; short-lived. */
    data class GroupDeviceLink(val groupId: String, val uri: String) : Message {
        override fun toJson() = obj("group_device_link").put("id", groupId).put("uri", uri)
    }

    /** Something about groups didn't work; the tablet shows its own (translated) text for [code]. */
    data class GroupError(val code: GroupErrorCode) : Message {
        override fun toJson() = obj("group_error").put("code", code.name)
    }

    /** [NO_RELAY]: no relay address to make a group on (not seen unless the standard one is blanked out). [NOT_LINKED]: the companion has no tablet to hand this to. */
    enum class GroupErrorCode { INVALID_INVITE, EXPIRED_INVITE, NOT_ADMIN, NO_SUCH_GROUP, ALREADY_MEMBER, NO_RELAY, NOT_LINKED }

    companion object {
        /**
         * A voice reply key ([StartVoiceReply], [SendReply]) starting with this is a new text message
         * (SMS) to the number after it, not a reply to a notification.
         */
        const val SMS_KEY_PREFIX = "sms:"

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
            "speech_voice" -> runCatching { VoiceAvailability.valueOf(o.getString("availability")) }.getOrNull()
                ?.let { SpeechVoice(o.getString("lang"), it) }
            "speak" -> Speak(
                o.getString("text"), o.getString("lang"), o.optBoolean("interrupt"),
                runCatching { NavCue.valueOf(o.getString("cue")) }.getOrDefault(NavCue.PREPARE),
                runCatching { CueSide.valueOf(o.optString("side")) }.getOrDefault(CueSide.CENTER)
            )
            // A cue this version doesn't know (from a newer tablet) is skipped.
            "play_cue" -> runCatching { NavCue.valueOf(o.getString("cue")) }.getOrNull()?.let { cue ->
                PlayCue(cue, runCatching { CueSide.valueOf(o.optString("side")) }.getOrDefault(CueSide.CENTER))
            }
            "request_self_link" -> RequestSelfLink
            "self_link" -> SelfLink(o.getString("uri"))
            "self_unlink" -> SelfUnlink
            "self_status" -> SelfStatus(o.optBoolean("linked"), o.optBoolean("waiting"))
            "relay_open" -> RelayOpen(o.getInt("sock"), o.getString("url"))
            "relay_send" -> RelaySend(o.getInt("sock"), o.getString("text"))
            "relay_close" -> RelayClose(o.getInt("sock"), o.optInt("code", 1000))
            "relay_opened" -> RelayOpened(o.getInt("sock"))
            "relay_text" -> RelayText(o.getInt("sock"), o.getString("text"))
            "relay_closed" -> RelayClosed(o.getInt("sock"), o.optInt("code", -1))
            // groups
            "request_groups" -> RequestGroups
            "group_create" -> GroupCreate(o.nullableString("name"))
            "group_join" -> GroupJoin(o.getString("invite"))
            "group_leave" -> GroupLeave(o.getString("id"))
            "group_select" -> GroupSelect(o.nullableString("id"))
            "group_sharing" -> GroupSharing(o.optBoolean("on"))
            "group_profile" -> GroupProfile(o.optString("nick"), o.optInt("colour"), o.nullableString("icon"), o.nullableString("photo"))
            "my_position" -> MyPosition(
                o.getDouble("lat"), o.getDouble("lon"), o.optDouble("speed", 0.0).toFloat(), o.optDouble("heading", 0.0).toFloat(),
                o.optDouble("acc", 0.0).toFloat(), o.optLong("time")
            )
            "share_route" -> ShareRoute(o.optString("name"), o.getString("gpx"))
            "sos_start" -> SosStart(o.getDouble("lat"), o.getDouble("lon"))
            "sos_cancel" -> SosCancel
            "sos_answer" -> SosAnswer(o.getString("sos"), o.optBoolean("coming", true))
            "request_friends" -> RequestFriends
            "request_friend_invite" -> RequestFriendInvite
            "friend_remove" -> FriendRemove(o.getString("id"))
            "friend_add_to_group" -> FriendAddToGroup(o.getString("id"), o.optJSONArray("friends")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
            "group_invite_answer" -> GroupInviteAnswer(o.getString("offer"), o.optBoolean("accept"))
            "trip_share" -> TripShare(o.getString("trip"), o.getLong("rev"), o.optString("name"), o.getString("doc"))
            "friend_list" -> FriendList(o.optJSONArray("list").objects().map {
                FriendInfo(it.getString("id"), it.optString("nick"), it.optInt("colour"), it.nullableString("icon"), it.nullableString("photo"))
            })
            "friend_invite" -> FriendInvite(o.getString("id"), o.getString("uri"))
            "group_invite_offer" -> GroupInviteOffer(o.getString("offer"), o.optString("from"), o.nullableString("group"), o.nullableString("photo"), o.optInt("colour"))
            "group_trip" -> GroupTrip(
                o.getString("id"), o.getString("trip"), o.getLong("rev"), o.getString("from"), o.nullableString("nick"),
                o.optString("name"), o.getString("doc"), o.optBoolean("mine")
            )
            "group_use_device" -> GroupUseThisDevice
            "group_settings" -> GroupSettings(o.optString("relay"), o.optBoolean("saver"))
            "group_remove" -> GroupRemoveRider(o.getString("id"), o.getString("rider"))
            "request_device_link" -> RequestDeviceLink(o.getString("id"))
            "group_device_link" -> GroupDeviceLink(o.getString("id"), o.getString("uri"))
            "request_invite" -> RequestGroupInvite(o.getString("id"), if (o.isNull("days")) null else o.optInt("days"))
            "group_list" -> GroupList(o.optJSONArray("list").objects().map {
                GroupInfo(it.getString("id"), it.nullableString("name"), it.optInt("members"), it.optBoolean("admin"))
            }, o.nullableString("active"), o.optBoolean("sharing", true))
            "group_connection" -> runCatching { GroupStatus.valueOf(o.getString("status")) }.getOrNull()
                ?.let { GroupConnection(o.getString("id"), it, o.optInt("members"), o.optBoolean("other")) }
            "group_members" -> GroupMembers(o.getString("id"), o.optJSONArray("list").objects().map {
                GroupMemberInfo(it.getString("id"), it.optString("nick"), it.optInt("colour"), it.nullableString("icon"), it.nullableString("photo"))
            })
            "group_positions" -> GroupPositions(o.getString("id"), o.optJSONArray("list").objects().map {
                GroupPositionInfo(
                    it.getString("id"), it.getDouble("lat"), it.getDouble("lon"), it.optDouble("heading", 0.0).toFloat(),
                    it.optDouble("speed", 0.0).toFloat(), it.optLong("time"), it.optBoolean("stopped"),
                    if (it.isNull("since")) null else it.optLong("since"), if (it.isNull("bat")) null else it.optInt("bat")
                )
            })
            "group_sos" -> GroupSos(
                o.getString("id"), o.getString("from"), o.getDouble("lat"), o.getDouble("lon"), o.optLong("time"),
                o.optBoolean("active", true), o.optBoolean("mine"),
                o.optJSONArray("acks").objects().map { SosAckInfo(it.getString("rider"), it.optBoolean("coming")) }, o.optInt("members")
            )
            "group_route" -> GroupRoute(o.getString("id"), o.getString("from"), o.optString("name"), o.getString("gpx"))
            "group_invite" -> GroupInvite(o.getString("id"), o.getString("uri"))
            // An error code from a newer phone is skipped.
            "group_error" -> runCatching { GroupErrorCode.valueOf(o.getString("code")) }.getOrNull()?.let { GroupError(it) }
            else -> null // unknown types from a newer peer are ignored
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
