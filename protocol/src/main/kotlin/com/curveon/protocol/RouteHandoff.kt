package com.curveon.protocol

/**
 * Curveon on the phone hands a trip to Curveon Companion on the same phone, which sends it on to the tablet
 * ([Message.RouteFromPhone]). An ordered broadcast to the companion's receiver; its result code says how it went.
 */
object RouteHandoff {
    const val COMPANION_PACKAGE = "com.curveon.companion"
    const val ACTION = "com.curveon.companion.SEND_ROUTE"
    const val EXTRA_NAME = "name"
    const val EXTRA_ROUTE = "route"

    /** No answer: the companion is too old to know routes, or has never been opened (Android keeps it asleep). */
    const val RESULT_NO_ANSWER = 0
    /** On the tablet now. */
    const val RESULT_SENT = 1
    /** The companion is sharing but no tablet is connected: it goes over when the tablet connects. */
    const val RESULT_WAITING = 2
    /** The companion is not sharing (Start sharing not pressed): it goes over once it is and the tablet connects. */
    const val RESULT_SHARING_OFF = 3
}
