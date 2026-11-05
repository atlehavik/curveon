package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.update.UpdateState
import com.curveon.ui.theme.*
import androidx.compose.ui.graphics.Color

private val OnCyan = Color(0xFF00363D)

/** Settings → Engine: the dashboard's engine panel on or off, the OBD2 adapter, and what it shows. */
@Composable
internal fun ColumnScope.EngineSettings(state: SettingsState, actions: SettingsActions) {
    Setting(stringResource(R.string.settings_obd_show), stringResource(R.string.settings_obd_show_hint)) {
        CockpitSwitch(state.obdEnabled, actions.onObd)
    }
    if (!state.obdEnabled) return
    SectionTitle(stringResource(R.string.settings_obd_adapter))
    Text(stringResource(R.string.settings_obd_adapter_hint), color = TextSecondary, fontSize = 13.sp)
    // Bluetooth not allowed or off: say which, with the button that sorts it (the list of adapters is empty until then).
    val btProblem = rememberBluetoothProblem()
    val paired = remember(btProblem) { state.pairedDevices() }
    val choices = paired.map { it.address to it.name } + (if (com.curveon.BuildConfig.DEV) listOf(com.curveon.data.obd.ObdLink.DEMO to stringResource(R.string.settings_obd_demo)) else emptyList())
    if (btProblem != null) BluetoothFix(btProblem)
    else if (paired.isEmpty()) Text(stringResource(R.string.settings_obd_none_paired), color = NeonAmber, fontSize = 13.sp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (address, name) ->
            val chosen = address == state.obdDevice
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (chosen) ElectricCyan.copy(alpha = 0.15f) else CockpitSurfaceVariant)
                    .clickable { actions.onObdDevice(if (chosen) null else address) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (chosen) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null,
                    tint = if (chosen) ElectricCyan else TextMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(name, color = TextPrimary, fontSize = 15.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f), maxLines = 1)
                if (chosen) Text(
                    stringResource(when (state.obd.status) {
                        com.curveon.data.obd.ObdState.Status.CONNECTED -> R.string.settings_obd_status_connected
                        com.curveon.data.obd.ObdState.Status.CONNECTING -> R.string.obd_connecting
                        com.curveon.data.obd.ObdState.Status.NO_ADAPTER -> btProblem?.text ?: R.string.obd_bluetooth_off
                        else -> R.string.obd_not_answering
                    }),
                    color = if (state.obd.status == com.curveon.data.obd.ObdState.Status.CONNECTED) EcoGreen else NeonAmber,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.widthIn(max = 220.dp)
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant)
            .clickable(onClick = actions.onFuelSetup).padding(horizontal = 14.dp, vertical = 12.dp).testTag("settings_fuel_setup"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_fuel_setup), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.settings_fuel_setup_hint), color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(if (state.fuelSetUp) R.string.settings_fuel_setup_done else R.string.settings_fuel_setup_open),
            color = if (state.fuelSetUp) EcoGreen else NeonAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    SectionTitle(stringResource(R.string.settings_obd_values))
    com.curveon.data.obd.ObdValue.entries.forEach { v ->
        Setting(stringResource(when (v) {
            com.curveon.data.obd.ObdValue.FUEL -> R.string.poi_fuel
            com.curveon.data.obd.ObdValue.RPM -> R.string.obd_rpm
            com.curveon.data.obd.ObdValue.COOLANT -> R.string.obd_coolant
            com.curveon.data.obd.ObdValue.VOLTAGE -> R.string.obd_battery
        })) {
            CockpitSwitch(v in state.obdValues, { actions.onObdValue(v) })
        }
    }
}

/** Settings → Help: this version, a newer one on GitHub (download and install; not in the Play build), and what's new. */
@Composable
internal fun AppVersionCard(update: UpdateState, actions: SettingsActions) {
    val release = when (update) {
        is UpdateState.Available -> update.release
        is UpdateState.Downloading -> update.release
        is UpdateState.Installing -> update.release
        is UpdateState.Failed -> update.release
        else -> null
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (release != null) ElectricCyan.copy(alpha = 0.12f) else CockpitSurfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (release != null) Icons.Default.SystemUpdate else Icons.Default.Info, null,
                tint = if (release != null) ElectricCyan else TextSecondary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    if (release != null) stringResource(R.string.update_available, release.version)
                    else stringResource(R.string.app_name) + " " + com.curveon.BuildConfig.VERSION_NAME,
                    color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                val status = when (update) {
                    UpdateState.Checking -> stringResource(R.string.update_checking)
                    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                    is UpdateState.Installing -> stringResource(R.string.update_installing)
                    is UpdateState.Downloading -> stringResource(R.string.update_downloading, (update.fraction * 100).toInt())
                    is UpdateState.Failed -> update.message
                    is UpdateState.Available -> stringResource(R.string.update_version, com.curveon.BuildConfig.VERSION_NAME)
                    UpdateState.Idle -> null
                }
                if (status != null) Text(status, color = if (update is UpdateState.Failed) WarningRed else TextSecondary, fontSize = 13.sp)
            }
            when {
                !com.curveon.BuildConfig.SELF_UPDATE -> Unit // Google Play updates the app
                update is UpdateState.Downloading || update is UpdateState.Installing || update == UpdateState.Checking -> Unit
                release != null -> Button(onClick = actions.onInstallUpdate, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                    Text(stringResource(R.string.update_install), color = OnCyan, fontWeight = FontWeight.Bold)
                }
                else -> OutlinedButton(onClick = actions.onCheckUpdate) { Text(stringResource(R.string.update_check), color = ElectricCyan) }
            }
        }
        if (update is UpdateState.Downloading) LinearProgressIndicator(progress = { update.fraction }, Modifier.fillMaxWidth(), color = ElectricCyan)
        // The release's own notes from GitHub, before it is installed.
        if (release != null && release.notes.isNotBlank()) Text(release.notes.take(600), color = TextSecondary, fontSize = 13.sp)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CockpitSurfaceVariant)
            .clickable(onClick = actions.onWhatsNew).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.NewReleases, null, tint = ElectricCyan, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.whats_new), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.whats_new_hint), color = TextSecondary, fontSize = 13.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
    }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CockpitSurfaceVariant)
            .clickable { runCatching { uri.openUri(GITHUB_URL) } }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Code, null, tint = ElectricCyan, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.github_title), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.github_hint), color = TextSecondary, fontSize = 13.sp)
        }
        Icon(Icons.Default.OpenInNew, null, tint = TextMuted)
    }
    FeedbackCard()
}

private const val GITHUB_URL = "https://github.com/atlehavik/curveon"

/** Settings → Help: the welcome tour, and what every symbol in the app means. */
@Composable
internal fun ColumnScope.HelpPage(onTour: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(ElectricCyan.copy(alpha = 0.16f), CockpitSurfaceVariant)))
            .clickable(onClick = onTour).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.TwoWheeler, null, tint = ElectricCyan, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.help_tour_title), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.help_tour_hint), color = TextSecondary, fontSize = 13.sp)
        }
        Button(onClick = onTour, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
            Text(stringResource(R.string.help_tour_start), color = OnCyan, fontWeight = FontWeight.Bold)
        }
    }
    // Two columns on the tablet, one on a phone held upright.
    val columns = if (isNarrowScreen()) 1 else 2
    HELP_GROUPS.forEach { (title, symbols) ->
        SectionTitle(stringResource(title))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            symbols.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { HelpSymbolRow(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
