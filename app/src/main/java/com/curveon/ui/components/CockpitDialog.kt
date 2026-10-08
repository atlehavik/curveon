package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.curveon.ui.theme.*

/**
 * A short message for the rider (e.g. "Calling Kari…"). Dialogs show it on top of themselves,
 * since the dashboard's own toast is hidden behind an open dialog.
 */
val LocalToast = compositionLocalOf<String?> { null }

/**
 * The one dialog frame every rail screen uses (Where to?, Roundtrip, GPX, Phone, Settings, Rides):
 * same size, same header, and the same round close button in the top-right corner.
 *
 * [header] replaces the title (e.g. the search field); [actions] sit just left of the close button.
 */
@Composable
fun CockpitDialog(
    title: String,
    icon: ImageVector,
    accent: Color,
    onDismiss: () -> Unit,
    tag: String,
    subtitle: String? = null,
    header: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /** A menu on the left (see [DialogTabs]), for screens with several pages like Settings. */
    sideBar: (@Composable () -> Unit)? = null,
    /** Applied to the frame first, e.g. a scale while it shrinks onto the map. */
    frameModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val toast = LocalToast.current
    val frame = @Composable {
        Box(
            frameModifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f).clip(DialogShape).background(CockpitSurface)
                .border(1.dp, CockpitBorder, DialogShape).testTag(tag)
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (header != null) header() else {
                        Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                            if (subtitle != null) Text(subtitle, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    actions()
                    Box(Modifier.testTag("${tag}_close")) {
                        RoundButton(Icons.Default.Close, stringResource(R.string.close), 48.dp, CockpitSurfaceVariant, TextPrimary, onDismiss)
                    }
                }
                if (sideBar == null) content()
                else Row(Modifier.fillMaxSize()) {
                    sideBar()
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
            }
            toast?.let {
                Text(it, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(RoundedCornerShape(12.dp))
                        .background(CockpitSurfaceVariant).border(1.dp, ElectricCyan, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
    // Inside a [HostDialog] the pages swap without closing and reopening the window.
    if (LocalInHostDialog.current) frame()
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { frame() }
}

private val LocalInHostDialog = compositionLocalOf { false }

/** One dialog window whose [CockpitDialog] pages (e.g. GPX and Rides) can change in place. */
@Composable
fun HostDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalInHostDialog provides true) { content() }
    }
}

/** The left-hand page menu of a dialog (Settings, GPX & rides): icon and label per page. */
@Composable
fun <T> DialogTabs(
    items: List<T>, selected: T, label: @Composable (T) -> String, icon: (T) -> ImageVector, onSelect: (T) -> Unit,
    width: androidx.compose.ui.unit.Dp = 210.dp
) {
    Column(
        Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(CockpitBackground.copy(alpha = 0.6f)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { t ->
            val on = t == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) ElectricCyan else Color.Transparent)
                    .clickable { onSelect(t) }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon(t), null, tint = if (on) OnCyanText else TextSecondary)
                Spacer(Modifier.width(12.dp))
                Text(label(t), color = if (on) OnCyanText else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

private val OnCyanText = Color(0xFF00363D)

/**
 * The app's on/off switch: a dark knob on a cyan (or [accent]) track when on, a grey knob on a
 * dark outlined track when off, so the state reads at a glance on the bike.
 */
@Composable
fun CockpitSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, accent: Color = ElectricCyan) {
    androidx.compose.material3.Switch(
        checked = checked, onCheckedChange = onCheckedChange, modifier = modifier,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = CockpitBackground, checkedTrackColor = accent, checkedBorderColor = accent,
            uncheckedThumbColor = TextSecondary, uncheckedTrackColor = CockpitBackground, uncheckedBorderColor = TextMuted
        )
    )
}

private val DialogShape = RoundedCornerShape(24.dp)
