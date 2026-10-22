package com.curveon.protocol

/**
 * What went wrong on the phone, for [Message.PhoneError] and [Message.VoiceReplyState]: the tablet turns it into
 * words in the rider's language, saying what to do about it. Unknown codes fall back to the English text.
 */
object PhoneErrorCode {
    const val CALL_NOT_ALLOWED = "call_not_allowed"
    const val CALL_FAILED = "call_failed"
    const val SMS_NOT_ALLOWED = "sms_not_allowed"
    const val SMS_FAILED = "sms_failed"
    const val SMS_UNAVAILABLE = "sms_unavailable"
    const val VOICE_UNAVAILABLE = "voice_unavailable"
    const val NO_RECOGNIZER = "no_recognizer"
    const val NOT_CAUGHT = "not_caught"
    const val MIC_NOT_ALLOWED = "mic_not_allowed"
    const val NEEDS_CONNECTION = "needs_connection"
    const val RECOGNIZER_ERROR = "recognizer_error"
    const val CANT_REPLY = "cant_reply"
    const val REPLY_FAILED = "reply_failed"
    const val CALL_ACTION_FAILED = "call_action_failed"
}
