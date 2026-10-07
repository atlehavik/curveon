package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.AppLanguage
import com.curveon.R
import com.curveon.data.bluetooth.PhoneCompanionManager
import com.curveon.data.map.MapDownloadState
import com.curveon.data.map.MapRegionStatus
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.MapTheme

private val OnCyan = Color(0xFF00363D)

private enum class SettingsTab(val label: Int, val icon: ImageVector) {
    DISPLAY(R.string.settings_display, Icons.Default.Tune),
    NAVIGATION(R.string.settings_navigation, Icons.Default.Navigation),
    PHONE(R.string.phone, Icons.Default.PhoneAndroid),
    DATA(R.string.settings_maps_data, Icons.Default.Map)
}

/** Everything the Settings screen shows. */
data class SettingsState(
    val language: AppLanguage,
    val useMph: Boolean,
    val mapTheme: MapTheme,
    val obdEnabled: Boolean,
    val autoZoom: Boolean,
    val autoFollowTracks: Boolean,
    val isNavigating: Boolean,
    val isDemoRiding: Boolean,
    val maps: List<MapRegionStatus>,
    val mapDownload: MapDownloadState,
    val routingDataStatus: String,
    val searchStatus: String
)

/** What the Settings screen can change. */
class SettingsActions(
    val onLanguage: (AppLanguage) -> Unit,
    val onUnits: (Boolean) -> Unit,
    val onMapTheme: (MapTheme) -> Unit,
    val onObd: (Boolean) -> Unit,
    val onAutoZoom: (Boolean) -> Unit,
    val onAutoFollow: (Boolean) -> Unit,
    val onDemoRide: () -> Unit,
    val onDownloadMap: (String) -> Unit,
    val onCancelMapDownload: () -> Unit,
    val onDeleteMap: (String) -> Unit,
    val onImportMap: () -> Unit,
    val onDownloadRouting: () -> Unit
)

/**
 * Full-screen settings with tabs on the left (like Android Auto), so each page stays short and
 * there is room for more.
 */
@Composable
fun SettingsDialog(state: SettingsState, actions: SettingsActions, phone: PhoneCompanionManager, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(SettingsTab.DISPLAY) }
    var confirmDelete by remember { mutableStateOf<MapRegionStatus?>(null) }
    CockpitDialog(title = stringResource(R.string.settings), icon = Icons.Default.Settings, accent = TextSecondary, onDismiss = onDismiss, tag = "settings_dialog") {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(210.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(CockpitBackground.copy(alpha = 0.6f)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SettingsTab.entries.forEach { t ->
                    val on = t == tab
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) ElectricCyan else Color.Transparent)
                            .clickable { tab = t }.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(t.icon, null, tint = if (on) OnCyan else TextSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(t.label), color = if (on) OnCyan else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 6.dp)) {
                when (tab) {
                    SettingsTab.DISPLAY -> Page {
                        if (AppLanguage.canChoose) Setting(stringResource(R.string.settings_language)) {
                            Segmented(AppLanguage.entries, state.language, { it.label }, actions.onLanguage)
                        }
                        Setting(stringResource(R.string.settings_speed)) {
                            Segmented(listOf(false, true), state.useMph, { if (it) "mph" else "km/h" }, actions.onUnits)
                        }
                        Setting(stringResource(R.string.settings_map)) {
                            Segmented(MapTheme.entries, state.mapTheme, { stringResource(it.label) }, actions.onMapTheme)
                        }
                        Setting(stringResource(R.string.settings_obd), stringResource(R.string.settings_obd_hint)) {
                            Switch(state.obdEnabled, actions.onObd, colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan))
                        }
                    }
                    SettingsTab.NAVIGATION -> Page {
                        Setting(stringResource(R.string.settings_auto_zoom), stringResource(R.string.settings_auto_zoom_hint)) {
                            Switch(state.autoZoom, actions.onAutoZoom, colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan))
                        }
                        Setting(stringResource(R.string.settings_auto_follow), stringResource(R.string.settings_auto_follow_hint)) {
                            Switch(state.autoFollowTracks, actions.onAutoFollow, colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan))
                        }
                        Setting(stringResource(R.string.settings_demo_ride), stringResource(R.string.settings_demo_ride_hint)) {
                            OutlinedButton(onClick = actions.onDemoRide, enabled = state.isNavigating || state.isDemoRiding) {
                                Text(stringResource(if (state.isDemoRiding) R.string.stop else R.string.start), color = NeonAmber)
                            }
                        }
                    }
                    SettingsTab.PHONE -> PhoneLinkContent(phone, Modifier.fillMaxSize())
                    SettingsTab.DATA -> Page {
                        SectionTitle(stringResource(R.string.settings_offline_maps))
                        state.maps.forEach { m -> MapRow(m, state.mapDownload, actions) { confirmDelete = m } }
                        (state.mapDownload as? MapDownloadState.Failed)?.let { Text(it.message, color = WarningRed, fontSize = 13.sp) }
                        Text(stringResource(R.string.settings_map_attribution),
                            color = TextMuted, fontSize = 12.sp)
                        TextButton(onClick = actions.onImportMap) { Text(stringResource(R.string.settings_import_pmtiles), color = ElectricCyan) }

                        SectionTitle(stringResource(R.string.settings_routing_data))
                        Setting(state.routingDataStatus) {
                            OutlinedButton(onClick = actions.onDownloadRouting) { Text(stringResource(R.string.download), color = ElectricCyan) }
                        }
                        SectionTitle(stringResource(R.string.settings_search_index))
                        Text(state.searchStatus, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.settings_delete_map_title, stringResource(m.region.name))) },
            text = { Text(stringResource(R.string.settings_delete_map_text)) },
            confirmButton = { TextButton(onClick = { actions.onDeleteMap(m.region.id); confirmDelete = null }) { Text(stringResource(R.string.delete), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun MapRow(m: MapRegionStatus, download: MapDownloadState, actions: SettingsActions, onDelete: () -> Unit) {
    val running = (download as? MapDownloadState.Running)?.takeIf { it.regionId == m.region.id }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(m.region.name), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        m.installedBytes > 0 -> stringResource(R.string.size_gb, m.installedBytes / 1e9) +
                            (m.installedBuild?.let { " · " + stringResource(R.string.settings_map_from, it) } ?: "") +
                            (if (m.updateAvailable) " · " + stringResource(R.string.settings_map_newer, m.latestBuild ?: "") else "")
                        else -> stringResource(R.string.settings_map_not_installed, m.region.approxBytes / 1e9)
                    },
                    color = if (m.updateAvailable) NeonAmber else TextSecondary, fontSize = 13.sp
                )
            }
            if (running != null) {
                OutlinedButton(onClick = actions.onCancelMapDownload) { Text(stringResource(R.string.cancel), color = WarningRed) }
            } else {
                if (m.installedBytes > 0) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = TextMuted) }
                    Spacer(Modifier.width(6.dp))
                }
                Button(
                    onClick = { actions.onDownloadMap(m.region.id) },
                    enabled = download !is MapDownloadState.Running,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) { Text(stringResource(if (m.installedBytes > 0) R.string.update else R.string.download), color = OnCyan, fontWeight = FontWeight.Bold) }
            }
        }
        if (running != null) {
            LinearProgressIndicator(progress = { running.fraction }, color = ElectricCyan, trackColor = CockpitBorder,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
            Text(running.message, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

/** One setting: title (and an optional one-line hint) on the left, the control on the right. */
@Composable
private fun Setting(title: String, hint: String? = null, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (hint != null) Text(hint, color = TextMuted, fontSize = 13.sp, maxLines = 1)
        }
        control()
    }
}
