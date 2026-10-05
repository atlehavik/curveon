package com.vibenav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vibenav.data.model.NotificationItem
import com.vibenav.ui.theme.*

@Composable
fun NotificationPanel(
    notifications: List<NotificationItem>,
    onDismiss: (String) -> Unit,
    onQuickReply: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CockpitSurface)
            .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
            .padding(14.dp)
            .testTag("notification_panel_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        tint = NeonAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "NOTIFICATIONS",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                if (notifications.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(NeonAmber)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${notifications.size} New",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No pending alerts • Road is clear",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    notifications.take(2).forEach { notif ->
                        NotificationCardItem(
                            notification = notif,
                            onDismiss = { onDismiss(notif.id) },
                            onQuickReply = { reply -> onQuickReply(notif.id, reply) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationCardItem(
    notification: NotificationItem,
    onDismiss: () -> Unit,
    onQuickReply: (String) -> Unit
) {
    var showReplyRow by remember { mutableStateOf(false) }

    val appBadgeColor = when (notification.appName) {
        "WhatsApp" -> Color(0xFF25D366)
        "Phone" -> ElectricCyan
        "SMS" -> Color(0xFF3B82F6)
        else -> NeonAmber
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitSurfaceVariant)
            .border(1.dp, CockpitBorder.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Native Phonebook Contact Avatar / Initials
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(appBadgeColor.copy(alpha = 0.2f))
                        .border(1.5.dp, appBadgeColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (notification.senderAvatarUrl != null) {
                        AsyncImage(
                            model = notification.senderAvatarUrl,
                            contentDescription = notification.senderName,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = notification.initials,
                            color = appBadgeColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Sender & Message
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = notification.senderName,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(appBadgeColor.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = notification.appName,
                                color = appBadgeColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = notification.message,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Quick Action buttons (Reply / Dismiss)
                IconButton(
                    onClick = { showReplyRow = !showReplyRow },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Reply,
                        contentDescription = "Quick Reply",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Quick Canned Reply Chips for glove riders
            if (showReplyRow) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickReplyChip("🏍️ Riding now") {
                        onQuickReply("Riding motorcycle now, talk soon!")
                        showReplyRow = false
                    }
                    QuickReplyChip("⛽ Fuel stop") {
                        onQuickReply("At fuel stop, calling you soon.")
                        showReplyRow = false
                    }
                    QuickReplyChip("⏰ 15m away") {
                        onQuickReply("About 15 minutes away!")
                        showReplyRow = false
                    }
                }
            }
        }
    }
}

@Composable
fun QuickReplyChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CockpitBackground)
            .border(1.dp, ElectricCyanDim, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = ElectricCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("quick_reply_$text")
        )
    }
}
