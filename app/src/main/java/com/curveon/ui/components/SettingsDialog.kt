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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.curveon.data.bluetooth.PhoneCompanionManager
import com.curveon.data.map.MapDownloadState
import com.curveon.data.map.MapRegionStatus
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.MapTheme

private val OnCyan = Color(0xFF00363D)

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    DISPLAY("Display", Icons.Default.Tune),
    NAVIGATION("Navigation", Icons.Default.Navigation),
    PHONE("Phone", Icons.Default.PhoneAndroid),
    DATA("Maps & data", Icons.Default.Map)
}

/** Everything the Settings screen shows. */
data class SettingsState(
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f).clip(RoundedCornerShape(24.dp)).background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(24.dp)).testTag("settings_dialog")
        ) {
            Column(
                Modifier.width(210.dp).fillMaxHeight().background(CockpitBackground.copy(alpha = 0.6f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Settings", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 14.dp))
                SettingsTab.entries.forEach { t ->
                    val on = t == tab
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) ElectricCyan else Color.Transparent)
                            .clickable { tab = t }.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(t.icon, null, tint = if (on) OnCyan else TextSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text(t.label, color = if (on) OnCyan else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("settings_dialog_close_btn")) {
                    Icon(Icons.Default.Close, null, tint = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text("Close", color = TextSecondary, fontSize = 15.sp)
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                when (tab) {
                    SettingsTab.DISPLAY -> Page {
                        Setting("Speed") {
                            Segmented(listOf(false, true), state.useMph, { if (it) "mph" else "km/h" }, actions.onUnits)
                        }
                        Setting("Map") {
                            Segmented(MapTheme.entries, state.mapTheme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, actions.onMapTheme)
                        }
                        Setting("OBD2 data", "Engine data card when an adapter is connected") {
                            Switch(state.obdEnabled, actions.onObd, colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan))
                        }
                    }
                    SettingsTab.NAVIGATION -> Page {
                        Setting("Auto-zoom", "Zoom in when slow, out on fast roads") {
                            Switch(state.autoZoom, actions.onAutoZoom, colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan))
                        }
                        Setting("Auto-follow GPX tracks", "Navigate when riding along a track shown on the map") {
                            Switch(state.autoFollowTracks, actions.onAutoFollow, colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan))
                        }
                        Setting("Demo ride", "Drive the current route without GPS") {
                            OutlinedButton(onClick = actions.onDemoRide, enabled = state.isNavigating || state.isDemoRiding) {
                                Text(if (state.isDemoRiding) "Stop" else "Start", color = NeonAmber)
                            }
                        }
                    }
                    SettingsTab.PHONE -> PhoneLinkContent(phone, Modifier.fillMaxSize())
                    SettingsTab.DATA -> Page {
                        SectionTitle("Offline maps")
                        state.maps.forEach { m -> MapRow(m, state.mapDownload, actions) { confirmDelete = m } }
                        (state.mapDownload as? MapDownloadState.Failed)?.let { Text(it.message, color = WarningRed, fontSize = 13.sp) }
                        Text("Map data © OpenStreetMap contributors, via the Protomaps daily build. Download over Wi-Fi or the phone's hotspot.",
                            color = TextMuted, fontSize = 12.sp)
                        TextButton(onClick = actions.onImportMap) { Text("Import a .pmtiles file instead…", color = ElectricCyan) }

                        SectionTitle("Routing data")
                        Setting(state.routingDataStatus) {
                            OutlinedButton(onClick = actions.onDownloadRouting) { Text("Download", color = ElectricCyan) }
                        }
                        SectionTitle("Search index")
                        Text(state.searchStatus, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete the ${m.region.name} map?") },
            text = { Text("Without it the map only shows online tiles.") },
            confirmButton = { TextButton(onClick = { actions.onDeleteMap(m.region.id); confirmDelete = null }) { Text("Delete", color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
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
                Text(m.region.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        m.installedBytes > 0 -> "%.1f GB".format(m.installedBytes / 1e9) +
                            (m.installedBuild?.let { " · from $it" } ?: "") +
                            (if (m.updateAvailable) " · newer map from ${m.latestBuild}" else "")
                        else -> "Not installed · about %.1f GB".format(m.region.approxBytes / 1e9)
                    },
                    color = if (m.updateAvailable) NeonAmber else TextSecondary, fontSize = 13.sp
                )
            }
            if (running != null) {
                OutlinedButton(onClick = actions.onCancelMapDownload) { Text("Cancel", color = WarningRed) }
            } else {
                if (m.installedBytes > 0) {
                    TextButton(onClick = onDelete) { Text("Delete", color = TextMuted) }
                    Spacer(Modifier.width(6.dp))
                }
                Button(
                    onClick = { actions.onDownloadMap(m.region.id) },
                    enabled = download !is MapDownloadState.Running,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) { Text(if (m.installedBytes > 0) "Update" else "Download", color = OnCyan, fontWeight = FontWeight.Bold) }
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
