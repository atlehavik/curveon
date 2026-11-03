package com.curveon.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After a reply the message's app may post it again: that clears, but what the other person writes next must not. */
class ReplyEchoTest {
    private val original = "Ses vi i kveld?"
    private val reply = "Ja det passer"

    @Test
    fun theRidersOwnMessageLastIsAnEcho() = assertTrue(isReplyEcho(true, reply, original, reply))

    @Test
    fun theReplyOrTheOldMessageAgainIsAnEcho() {
        assertTrue(isReplyEcho(false, reply, original, reply))
        assertTrue(isReplyEcho(false, "$reply ", original, reply))
        assertTrue(isReplyEcho(false, original, original, reply))
        assertTrue(isReplyEcho(false, "Du: $reply", original, reply))
    }

    @Test
    fun aNewMessageIsNotAnEcho() {
        assertFalse(isReplyEcho(false, "Flott, da ses vi", original, reply))
        assertFalse(isReplyEcho(false, "$reply for meg også", original, reply))
    }

    @Test
    fun noTextIsNotAnEcho() = assertFalse(isReplyEcho(false, null, original, reply))
}
