package com.curveon.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtocolTest {
    private val samples = listOf(
        Message.Hello("Pixel 9a"),
        Message.Ping,
        Message.MediaState("Riders on the Storm", "The Doors", "L.A. Woman", "Spotify", 434_000, 142_000, true, "abc"),
        Message.MediaState("Podcast", "", "", "Pocket Casts", 0, 0, false, null),
        Message.MediaStopped,
        Message.MediaArt("abc", "/9j/4AAQ"),
        Message.MediaSources(listOf(
            Message.MediaSource("com.spotify.music", "Spotify", true, "iVBOR"),
            Message.MediaSource("au.com.shiftyjelly.pocketcasts", "Pocket Casts", false, null)
        )),
        Message.PlayMediaApp("com.spotify.music"),
        Message.InstalledMediaApps(listOf(
            Message.MediaSource("com.spotify.music", "Spotify", false, "iVBOR"),
            Message.MediaSource("no.nrk.mobil.radio", "NRK Radio", false, null)
        )),
        Message.PhoneStatus("Pixel 9a", 78, true, true, "1.0"),
        Message.NotificationPosted("k1", "WhatsApp", "Kari", "Ses på Dombås? 🏍️", 1L, null, true, false, emptyList()),
        Message.NotificationPosted("k2", "Phone", "Ola", "Incoming call", 2L, "AAAA", false, true, listOf("Answer", "Decline")),
        Message.NotificationPosted("k3", "Phone", "Ola", "Ongoing call", 3L, null, false, true, listOf("Hang up", "Speaker"), callOngoing = true),
        Message.NotificationRemoved("k1"),
        Message.VoiceReplyState("k1", Message.VoiceState.RESULT, "Ja, 20 minutter"),
        Message.MediaCommand(Message.MediaAction.NEXT),
        Message.DismissNotification("k1"),
        Message.InvokeAction("k2", 1),
        Message.StartVoiceReply("k1"),
        Message.CancelVoiceReply("k1"),
        Message.SendReply("k1", "Ja, 20 minutter"),
        Message.PlayCue(Message.NavCue.PREPARE, Message.CueSide.LEFT),
        Message.PlayCue(Message.NavCue.OFF_ROUTE),
        Message.SpeechVoice("nb-NO", Message.VoiceAvailability.ONLINE_ONLY),
        Message.Speak("Om 300 meter, sving til høyre", "nb-NO", false, Message.NavCue.PREPARE, Message.CueSide.RIGHT),
        Message.Contacts(listOf(
            Message.Contact(7, "Kari Nordmann", true, "7:12", listOf(Message.PhoneNumber("+47 912 34 567", "Mobile"))),
            Message.Contact(8, "Ola", false, null, listOf(Message.PhoneNumber("22 33 44 55", "Work"), Message.PhoneNumber("900 00 000", "Mobile")))
        )),
        Message.Contacts(emptyList(), granted = false),
        Message.RecentCalls(listOf(
            Message.RecentCall("+4791234567", "Kari Nordmann", "Mobile", "7:12", Message.CallKind.MISSED, 3L, 2),
            Message.RecentCall("12345", null, "", null, Message.CallKind.OUTGOING, 4L)
        )),
        Message.ContactPhotos(listOf(Message.Photo("7:12", "/9j/"), Message.Photo("9:1", null))),
        Message.PhoneError("Calls aren't allowed"),
        Message.RequestPhoneBook,
        Message.RequestContactPhotos(listOf("7:12", "9:1")),
        Message.PlaceCall("+47 912 34 567"),
        // groups
        Message.RequestGroups,
        Message.GroupCreate("Lofoten"),
        Message.GroupCreate(null),
        Message.GroupJoin("https://relay.example.org/join#v=1&g=abc"),
        Message.GroupLeave("0123"),
        Message.GroupSelect("0123"),
        Message.GroupSelect(null),
        Message.GroupSharing(false),
        Message.GroupProfile("Ola æøå", -65536, "helmet", null),
        Message.GroupProfile("Kari", 255, null, "/9j/"),
        Message.MyPosition(59.91, 10.75, 21.5f, 90f, 4f, 1_700_000_000_000),
        Message.ShareRoute("Lofoten", "H4sI"),
        Message.RouteFromPhone("Helgetur", "H4sI"),
        Message.SosStart(68.1, 13.6),
        Message.SosCancel,
        Message.SosAnswer("rider1", true),
        Message.GroupRemoveRider("0123", "rider2"),
        Message.RequestGroupInvite("0123", 7),
        Message.RequestGroupInvite("0123", null),
        Message.GroupList(listOf(Message.GroupInfo("0123", "Lofoten", 4, true), Message.GroupInfo("4567", null, 2, false)), "0123", true),
        Message.GroupList(emptyList(), null, false),
        Message.GroupConnection("0123", Message.GroupStatus.OFFLINE, 3),
        Message.GroupConnection("0123", Message.GroupStatus.CONNECTED, 3, otherDeviceActive = true),
        Message.GroupUseThisDevice,
        Message.RequestFriends,
        Message.RequestFriendInvite,
        Message.FriendRemove("abc"),
        Message.FriendSharing(true, listOf("f1", "f2")),
        Message.FriendSharing(false, emptyList()),
        Message.RequestFriendLink,
        Message.FriendLink("https://relay.curveon.no/join#v=1"),
        Message.FriendAdded("f1", "Ola"),
        Message.FriendAddToGroup("0123", listOf("f1", "f2")),
        Message.FriendAddToGroup("0123", emptyList()),
        Message.GroupInviteAnswer("o1", true),
        Message.RequestSelfLink,
        Message.SelfLink("curveon://join#x"),
        Message.SelfUnlink,
        Message.SelfStatus(linked = true, waiting = false),
        Message.RelayOpen(1, "wss://relay.curveon.no/g/00?p=x"),
        Message.RelaySend(1, """{"v":1,"t":"pos"}"""),
        Message.RelayClose(1, 1000),
        Message.RelayOpened(1),
        Message.RelayText(2, "{\"é\":1}"),
        Message.RelayClosed(2, -1),
        Message.TripShare("t1", 3, "Dagstur", """{"stops":[{"name":"Kafé","lat":59.1,"lon":5.2}]}"""),
        Message.TripToFriends("t2", 4, "Kveldstur", """{"stops":[]}""", listOf("f1", "f2")),
        Message.KeepFriendsOpen(listOf("f1")),
        Message.FriendList(listOf(Message.FriendInfo("f1", "Kari", 5, "bike", null), Message.FriendInfo("f2", "Per", 6, null, "/9j/"))),
        Message.FriendList(emptyList()),
        Message.FriendList(listOf(Message.FriendInfo("f3", "Siri", 7, null, null, seesMe = true, sharesWithMe = true)), sharing = true),
        Message.FriendInvite("c1", "curveon://join#v=1&t=f"),
        Message.GroupInviteOffer("o1", "Ola", "Lofoten", "/9j/", 77),
        Message.GroupInviteOffer("o2", "Ola", null, null, 0),
        Message.GroupTrip("0123", "t1", 4, "r1", "Kari", "Dagstur", """{"stops":[]}""", false),
        Message.GroupTrip("0123", "t1", 5, "r2", null, "Dagstur", "{}", true),
        Message.GroupSettings(true),
        Message.GroupSettings(false),
        Message.GroupMembers("0123", listOf(
            Message.GroupMemberInfo("r1", "Ola", -1, "helmet", null),
            Message.GroupMemberInfo("r2", "Kari", 5, null, "/9j/"),
            Message.GroupMemberInfo("r3", "Per", 6, null, null)
        )),
        Message.GroupPositions("0123", listOf(
            Message.GroupPositionInfo("r1", 59.9, 10.7, 180f, 22f, 5L, false, null, 80),
            Message.GroupPositionInfo("r2", 60.0, 11.0, 0f, 0f, 6L, true, 3L, null)
        )),
        Message.GroupSos("0123", "r1", 68.1, 13.6, 7L, true, false, listOf(Message.SosAckInfo("r2", false), Message.SosAckInfo("r3", true)), 4),
        Message.GroupSos("0123", "r1", 68.1, 13.6, 7L, false, true, emptyList(), 4),
        Message.GroupRoute("0123", "r1", "Lofoten", "H4sI"),
        Message.GroupInvite("0123", "curveon://join#v=1"),
        Message.RequestDeviceLink("0123"),
        Message.GroupDeviceLink("0123", "curveon://join#v=1&me=abc"),
        Message.GroupError(Message.GroupErrorCode.EXPIRED_INVITE),
        Message.GroupError(Message.GroupErrorCode.NO_RELAY)
    )

    @Test
    fun `an error code or status from a newer peer is skipped`() {
        val unknownCode = org.json.JSONObject("""{"type":"group_error","code":"FROM_THE_FUTURE"}""")
        assertNull(Message.fromJson(unknownCode))
        val unknownStatus = org.json.JSONObject("""{"type":"group_connection","id":"x","status":"FROM_THE_FUTURE","members":1}""")
        assertNull(Message.fromJson(unknownStatus))
    }

    @Test
    fun `every message survives a round trip through the framing`() {
        val buf = ByteArrayOutputStream()
        val writer = MessageWriter(buf)
        samples.forEach(writer::write)
        val reader = MessageReader(ByteArrayInputStream(buf.toByteArray()))
        samples.forEach { assertEquals(it, reader.read()) }
        assertNull(reader.read())
    }

    @Test
    fun `unknown message types are skipped`() {
        val buf = ByteArrayOutputStream()
        val raw = """{"type":"from_the_future"}""".toByteArray()
        java.io.DataOutputStream(buf).apply { writeInt(raw.size); write(raw) }
        MessageWriter(buf).write(Message.Ping)
        assertEquals(Message.Ping, MessageReader(ByteArrayInputStream(buf.toByteArray())).read())
    }
}
