package com.curveon.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.ui.theme.*

private val CardShape = RoundedCornerShape(20.dp)

@Composable
internal fun DashCard(modifier: Modifier = Modifier, border: Color = CockpitBorder, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .clip(CardShape)
            .background(CockpitSurface)
            .border(1.dp, border, CardShape),
        content = content
    )
}

// ---------------------------------------------------------------------------------- speed

// ---------------------------------------------------------------------------------- media

// ---------------------------------------------------------------------------------- notifications

// ---------------------------------------------------------------------------------- buttons

@Composable
fun RoundButton(icon: ImageVector, description: String, size: Dp, background: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun PillButton(icon: ImageVector, label: String, background: Color, content: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(background).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        // A label too long for a narrow button (Norwegian on a 360 dp phone) gets smaller, then two lines, instead of cut off.
        // Fitted anew when the words change, not when only a number in them does (a countdown would blink every second).
        val words = label.filterNot { it.isDigit() }
        var size by remember(words, LocalConfiguration.current.screenWidthDp) { mutableStateOf(16f) }
        var ready by remember(words, LocalConfiguration.current.screenWidthDp) { mutableStateOf(false) }
        Text(label, color = content, fontSize = size.sp, lineHeight = (size * 1.15f).sp, fontWeight = FontWeight.Bold, maxLines = 2,
            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.drawWithContent { if (ready) drawContent() },
            onTextLayout = { r ->
                if ((r.hasVisualOverflow || (r.lineCount > 1 && size > 13f)) && size > 11f) size -= 1f else ready = true
            })
    }
}
