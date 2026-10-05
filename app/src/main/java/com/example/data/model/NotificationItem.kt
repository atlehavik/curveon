package com.example.data.model

data class NotificationItem(
    val id: String,
    val appName: String, // "WhatsApp", "SMS", "Phone", "Telegram"
    val senderName: String,
    val senderAvatarUrl: String? = null,
    val senderInitials: String = "",
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isIncomingCall: Boolean = false,
    val isDismissed: Boolean = false
) {
    val initials: String
        get() = if (senderInitials.isNotEmpty()) senderInitials else senderName.take(2).uppercase()
}
