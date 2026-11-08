package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.bluetooth.PhoneCompanionManager
import com.curveon.ui.theme.*

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
}

/** For the pages kept in files of their own. */
@Composable
internal fun SettingTitle(text: String) = SectionTitle(text)

@Composable
internal fun SettingRow(title: String, hint: String? = null, control: @Composable () -> Unit) = Setting(title, hint, hintLines = Int.MAX_VALUE, control = control)

@Composable
internal fun <T> SettingChoice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) = Choice(options, selected, label, onSelect)

@Composable
internal fun SectionTitle(text: String) {
    Text(text.uppercase(), color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

/**
 * A group of settings under a title ([SectionTitle]'s look, with an arrow): tap the title to fold the group away or
 * open it again. Open unless [startFolded]; what the rider folds stays folded (by [key]) until opened again.
 */
@Composable
internal fun Group(title: String, key: String, startFolded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("settings_groups", android.content.Context.MODE_PRIVATE)
    var open by remember(key) { mutableStateOf(prefs.getBoolean(key, !startFolded)) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
            .clickable { open = !open; prefs.edit().putBoolean(key, open).apply() }.testTag("settings_group_$key"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) { SectionTitle(title) }
        androidx.compose.material3.Icon(
            if (open) androidx.compose.material.icons.Icons.Default.ExpandLess else androidx.compose.material.icons.Icons.Default.ExpandMore,
            stringResource(if (open) R.string.settings_group_fold else R.string.settings_group_open), tint = TextMuted, modifier = Modifier.size(22.dp)
        )
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
}

/**
 * One setting: title (and an optional hint) on the left, the control (a switch or a [Choice]) on
 * the right, on the phone as on the tablet.
 */
@Composable
internal fun Setting(title: String, hint: String? = null, below: (@Composable () -> Unit)? = null, hintLines: Int = if (isNarrowScreen()) 3 else 2, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (hint != null) Text(hint, color = TextMuted, fontSize = 13.sp, maxLines = hintLines)
            below?.invoke()
        }
        control()
    }
}

/**
 * One of several choices (km/h or mph, Auto / Day / Night, …): a button showing the chosen one,
 * tap for the list. Always as wide as the longest choice, so it doesn't jump when the choice changes.
 */
@Composable
internal fun <T> Choice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, icon: (@Composable (T) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant)
                .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                .clickable { open = true }.padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                // Every choice laid out but unseen: the button takes the width of the longest.
                options.forEach {
                    Row(Modifier.alpha(0f), verticalAlignment = Alignment.CenterVertically) {
                        icon?.invoke(it)
                        Text(label(it), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon?.invoke(selected)
                    Text(label(selected), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary, modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.remotePopup(), containerColor = CockpitSurface) {
            options.forEach { o ->
                val on = o == selected
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            icon?.invoke(o)
                            Text(label(o), color = if (on) ElectricCyan else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    trailingIcon = { if (on) Icon(Icons.Default.Check, null, tint = ElectricCyan) },
                    onClick = { open = false; onSelect(o) }
                )
            }
        }
    }
}

/** − and + around the arrow's lead, counted from the usual setting ("+0.25 s" further ahead, "−0.50 s" behind it). */
@Composable
internal fun ArrowLeadStepper(leadS: Double, onChange: (Double) -> Unit) {
    val m = com.curveon.data.location.RiderMotion
    // In whole steps, so repeated taps don't drift off the 0.25 grid.
    val step = Math.round(leadS / m.LEAD_STEP_S).toInt()
    fun set(n: Int) = onChange(n * m.LEAD_STEP_S)
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant)
            .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { set(step - 1) }, enabled = leadS > m.MIN_LEAD_S) {
            Icon(Icons.Default.Remove, stringResource(R.string.settings_arrow_back), tint = TextPrimary)
        }
        // Shown from the usual setting: 0 is the default lead, the rider adjusts + or − from there.
        val v = (step - Math.round(m.DEFAULT_LEAD_S / m.LEAD_STEP_S).toInt()) * m.LEAD_STEP_S
        Text(
            stringResource(R.string.settings_arrow_seconds, (if (v > 0) "+" else if (v < 0) "−" else "") +
                String.format(java.util.Locale.getDefault(), "%.2f", kotlin.math.abs(v))),
            color = if (step == Math.round(m.DEFAULT_LEAD_S / m.LEAD_STEP_S).toInt()) TextPrimary else ElectricCyan,
            fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp)
        )
        IconButton(onClick = { set(step + 1) }, enabled = leadS < m.MAX_LEAD_S) {
            Icon(Icons.Default.Add, stringResource(R.string.settings_arrow_ahead), tint = TextPrimary)
        }
    }
}

/** The key under the choice above: gravel and soft ground as drawn on the map in use. */
@Composable
internal fun SurfaceColours(mode: SurfaceMode, dark: Boolean) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        DashSample(mode.gravel(if (dark) com.curveon.data.map.OfflineMaps.NIGHT_ROAD_HC else com.curveon.data.map.OfflineMaps.HC_ROAD))
        Text(stringResource(R.string.surface_gravel), color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, end = 16.dp))
        DashSample(mode.soft(dark))
        Text(stringResource(R.string.surface_soft), color = TextMuted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun DashSample(hex: String, width: androidx.compose.ui.unit.Dp = 30.dp) {
    val color = Color(android.graphics.Color.parseColor(hex))
    androidx.compose.foundation.Canvas(Modifier.size(width = width, height = 6.dp)) {
        drawLine(
            color, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(size.height * 1.6f, size.height * 1.1f))
        )
    }
}

/**
 * The voice for spoken navigation isn't on the device that speaks (the phone, or this one): say
 * where to download it, so it also talks without coverage. On this device a button goes there.
 */
@Composable
internal fun VoiceDownloadTip(status: com.curveon.data.bluetooth.PhoneCompanionManager.VoiceStatus, onInstall: () -> Unit) {
    val missing = status.availability == com.curveon.protocol.Message.VoiceAvailability.MISSING
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NeonAmber.copy(alpha = 0.12f))
            .border(1.dp, NeonAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(if (missing) R.string.voice_missing else R.string.voice_online_only), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(if (status.onThisDevice) R.string.voice_download_where_here else R.string.voice_download_where_phone), color = TextSecondary, fontSize = 13.sp)
        if (status.onThisDevice) TextButton(onClick = onInstall, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.voice_download), color = NeonAmber, fontWeight = FontWeight.Bold)
        }
    }
}
