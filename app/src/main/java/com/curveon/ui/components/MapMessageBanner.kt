package com.curveon.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.curveon.R
import com.curveon.data.model.NotificationItem
import com.curveon.ui.theme.*

/**
 * A message that arrives while the full-screen map is up: who it is from and what it says, over the top of the
 * map for a few seconds, with the buttons to hear it or answer it. The caller slides it in and takes it away
 * again; the message itself stays with the dashboard's messages and the count on its button.
 */
@Composable
fun MapMessageBanner(
    n: NotificationItem,
    onRead: () -> Unit,
    onReply: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.widthIn(max = 520.dp).fillMaxWidth().clip(shape).background(CockpitSurface).border(2.dp, ElectricCyan, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("map_message_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Avatar(n, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(n.senderName, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(n.message, color = TextSecondary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(Icons.Default.VolumeUp, stringResource(R.string.read_aloud), 46.dp, CockpitSurfaceVariant, ElectricCyan, onRead)
        if (n.canReply) RoundButton(Icons.Default.Mic, stringResource(R.string.reply), 46.dp, ElectricCyan, androidx.compose.ui.graphics.Color(0xFF00363D), onReply)
        RoundButton(Icons.Default.Close, stringResource(R.string.dismiss), 46.dp, CockpitSurfaceVariant, TextPrimary, onClose)
    }
}
