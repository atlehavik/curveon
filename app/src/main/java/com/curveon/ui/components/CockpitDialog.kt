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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.activity.compose.BackHandler
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
 * True inside the dashboard's panel area: the rail screens are laid out over the map instead of in
 * a window of their own, so the rail stays in sight and pressable.
 */
val LocalInPanel = compositionLocalOf { false }

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
    val inPanel = LocalInPanel.current
    val frame = @Composable {
        Box(
            frameModifier.then(if (inPanel) Modifier.fillMaxSize() else Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f)).clip(DialogShape).background(CockpitSurface)
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
    // In the dashboard's panel area it is just laid out there (Back closes it); inside a
    // [HostDialog] the pages swap without closing and reopening the window.
    if (inPanel) { BackHandler(onBack = onDismiss); frame() }
    else if (LocalInHostDialog.current) frame()
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
    if (LocalInPanel.current) {
        CompositionLocalProvider(LocalInHostDialog provides true) { content() }
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        CompositionLocalProvider(LocalInHostDialog provides true) { content() }
    }
}

/**
 * A screen of several pages in one frame (Settings, GPX / Rides / Planned, Phone): the page menu down the
 * left on a wide screen; upright on a phone a row of tabs on top with the pages swiped between side by side.
 * Every page is laid out in a column that fills what is left, so lists can take the rest with a weight.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun <T> PagedCockpitDialog(
    title: String,
    icon: ImageVector,
    accent: Color,
    onDismiss: () -> Unit,
    tag: String,
    pages: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    pageLabel: @Composable (T) -> String,
    pageIcon: (T) -> ImageVector,
    /** Pages that show a red dot, e.g. one with a recording under way. */
    pageBadge: (T) -> Boolean = { false },
    tabsWidth: androidx.compose.ui.unit.Dp = 210.dp,
    /** Replaces the title in the header (e.g. the phone's own tab buttons on a wide screen). */
    header: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    page: @Composable ColumnScope.(T) -> Unit
) {
    val slot = androidx.compose.runtime.remember { HeaderSlot() }
    CockpitDialog(
        title = title, icon = icon, accent = accent, onDismiss = onDismiss, tag = tag, header = header, subtitle = slot.subtitle,
        actions = { actions(); slot.actions?.invoke(this) }
    ) { CompositionLocalProvider(LocalHeaderSlot provides slot) {
        val tabs: @Composable () -> Unit = { DialogTabs(pages, selected, pageLabel, pageIcon, onSelect, width = tabsWidth, badge = pageBadge) }
        val pageColumn: @Composable (T) -> Unit = { which ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) { page(which) }
        }
        if (isNarrowScreen()) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tabs()
            val index = pages.indexOf(selected).coerceAtLeast(0)
            val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = index) { pages.size }
            // A tap on a tab scrolls to its page; a swipe lights the next tab as soon as the page is
            // more than half across, not after it has settled. The tabs passed on the way of a tapped
            // tab's scroll don't light up (they would send the scroll back).
            val tapScrolling = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(index) {
                if (pager.currentPage != index) {
                    tapScrolling.value = true
                    try { pager.animateScrollToPage(index) } finally { tapScrolling.value = false }
                }
            }
            LaunchedEffect(pager) {
                androidx.compose.runtime.snapshotFlow { pager.currentPage }.collect { if (!tapScrolling.value) pages.getOrNull(it)?.let(onSelect) }
            }
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp) { pageColumn(pages[it]) }
        } else Row(Modifier.fillMaxSize()) {
            tabs()
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp)) { pageColumn(selected) }
        }
    } }
}

/** What an open page hands to its frame's title bar (see [PageToolbar]). */
class HeaderSlot {
    var subtitle by androidx.compose.runtime.mutableStateOf<String?>(null)
    var actions by androidx.compose.runtime.mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
}

private val LocalHeaderSlot = compositionLocalOf<HeaderSlot?> { null }

/**
 * A page's own buttons (select, delete…) that must not scroll away with the list. Upright on a
 * phone they sit as a fixed row above the list; on a wide screen, where the list needs every
 * line it can get, they move up into the frame's title bar (with [subtitle] under the title).
 */
@Composable
fun ColumnScope.PageToolbar(subtitle: String?, actions: @Composable RowScope.() -> Unit) {
    val slot = LocalHeaderSlot.current
    if (slot == null || isNarrowScreen()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (subtitle != null) Text(subtitle, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            actions()
        }
        return
    }
    androidx.compose.runtime.SideEffect { slot.subtitle = subtitle; slot.actions = actions }
    androidx.compose.runtime.DisposableEffect(slot) { onDispose { slot.subtitle = null; slot.actions = null } }
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
    width: androidx.compose.ui.unit.Dp = 210.dp,
    /** Pages that show a red dot on their icon. */
    badge: (T) -> Boolean = { false }
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
                    Box {
                        Icon(icon(t), label(t), tint = if (on) OnCyanText else TextSecondary, modifier = Modifier.size(22.dp))
                        if (badge(t)) RecordingDot(Modifier.align(Alignment.TopEnd))
                    }
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
                Box {
                    Icon(icon(t), null, tint = if (on) OnCyanText else TextSecondary)
                    if (badge(t)) RecordingDot(Modifier.align(Alignment.TopEnd))
                }
                Spacer(Modifier.width(12.dp))
                Text(label(t), color = if (on) OnCyanText else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

private val OnCyanText = Color(0xFF00363D)

/** The small red dot of a recording under way, with a light edge so it reads on both cyan and dark. */
@Composable
fun RecordingDot(modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(WarningRed).border(1.5.dp, Color.White.copy(alpha = 0.85f), androidx.compose.foundation.shape.CircleShape))
}

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
