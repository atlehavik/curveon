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
        Message.NotificationPosted("k1", "WhatsApp", "Kari", "Ses på Dombås? 🏍️", 1L, null, true, false, emptyList()),
        Message.NotificationPosted("k2", "Phone", "Ola", "Incoming call", 2L, "AAAA", false, true, listOf("Answer", "Decline")),
        Message.NotificationRemoved("k1"),
        Message.VoiceReplyState("k1", Message.VoiceState.RESULT, "Ja, 20 minutter"),
        Message.MediaCommand(Message.MediaAction.NEXT),
        Message.DismissNotification("k1"),
        Message.InvokeAction("k2", 1),
        Message.StartVoiceReply("k1"),
        Message.CancelVoiceReply("k1"),
        Message.SendReply("k1", "Ja, 20 minutter"),
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
        Message.PlaceCall("+47 912 34 567")
    )

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
