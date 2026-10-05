package com.vibenav.data.model

import android.graphics.Bitmap

data class NotificationItem(
    /** The phone's notification key. */
    val id: String,
    val appName: String,
    val senderName: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val avatar: Bitmap? = null,
    val canReply: Boolean = false,
    val isIncomingCall: Boolean = false,
    /** The notification's own buttons, e.g. "Answer"/"Decline" for calls. */
    val actions: List<String> = emptyList()
) {
    val initials: String
        get() = senderName.split(' ', '(').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
}
