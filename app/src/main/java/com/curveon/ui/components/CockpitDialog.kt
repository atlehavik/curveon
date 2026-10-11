package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
                            if (subtitle != null) Text(subtitle, color = TextSecondary, fontSize = 13.sp, maxLines = if (isNarrowScreen()) 2 else 1)
                        }
                    }
                    actions()
                    Box(Modifier.testTag("${tag}_close")) {
                        RoundButton(Icons.Default.Close, stringResource(R.string.close), 48.dp, CockpitSurfaceVariant, TextPrimary, onDismiss)
                    }
                }
                if (sideBar == null) content()
                // Narrow (a phone held upright): the page menu becomes a row of tabs on top.
                else if (isNarrowScreen()) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    sideBar()
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
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
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { HideSystemBars(); frame() }
}

/**
 * Call inside every dialog (any slot of an AlertDialog will do). A dialog is its own window and doesn't inherit the activity's hidden status and navigation bars,
 * so without this the back/home/recents buttons appear (and the map squeezes) whenever one is open,
 * and the home button sits right under the keyboard while typing an address.
 */
@Composable
fun HideSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    LaunchedEffect(window) {
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private val LocalInHostDialog = compositionLocalOf { false }

/** One dialog window whose [CockpitDialog] pages (e.g. GPX and Rides) can change in place. */
@Composable
fun HostDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        CompositionLocalProvider(LocalInHostDialog provides true) { content() }
    }
}

/** Narrower than a 7" tablet held upright: a phone held upright. Dialogs stack instead of sitting side by side. */
@Composable
fun isNarrowScreen(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600

/**
 * The left-hand page menu of a dialog (Settings, GPX & rides): icon and label per page. On a narrow
 * screen a row of tabs instead, which scrolls sideways if they don't fit.
 */
@Composable
fun <T> DialogTabs(
    items: List<T>, selected: T, label: @Composable (T) -> String, icon: (T) -> ImageVector, onSelect: (T) -> Unit,
    width: androidx.compose.ui.unit.Dp = 210.dp
) {
    if (isNarrowScreen()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CockpitBackground.copy(alpha = 0.6f))
                .horizontalScroll(rememberScrollState()).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { t ->
                val on = t == selected
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) ElectricCyan else Color.Transparent)
                        .clickable { onSelect(t) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Only the open page is labelled, so all of them fit across.
                    Icon(icon(t), label(t), tint = if (on) OnCyanText else TextSecondary, modifier = Modifier.size(22.dp))
                    if (on) {
                        Spacer(Modifier.width(8.dp))
                        Text(label(t), color = OnCyanText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        return
    }
    Column(
        Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(CockpitBackground.copy(alpha = 0.6f))
            .verticalScroll(rememberScrollState()).padding(8.dp),
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
