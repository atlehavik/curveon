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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.AppLanguage
import com.curveon.R
import com.curveon.data.bluetooth.PhoneCompanionManager
import com.curveon.data.map.InstalledMap
import com.curveon.data.map.MapDownloadState
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
    val offRoute: com.curveon.data.routing.OffRouteSettings,
    val isNavigating: Boolean,
    val isDemoRiding: Boolean,
    val maps: List<InstalledMap>,
    val mapDownload: MapDownloadState,
    val mapQueue: List<String>,
    /** Name of a catalog country/region by id, in the rider's language. */
    val mapName: (String) -> String,
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
    val onOffRoute: (com.curveon.data.routing.OffRouteSettings) -> Unit,
    val onDemoRide: () -> Unit,
    val onDownloadMap: (String) -> Unit,
    val onCancelMapDownload: (String) -> Unit,
    val onDeleteMap: (String) -> Unit,
    val onImportMap: () -> Unit,
    val onBrowseMaps: () -> Unit
)

/**
 * Full-screen settings with tabs on the left (like Android Auto), so each page stays short and
 * there is room for more.
 */
@Composable
fun SettingsDialog(state: SettingsState, actions: SettingsActions, phone: PhoneCompanionManager, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(SettingsTab.DISPLAY) }
    var confirmDelete by remember { mutableStateOf<InstalledMap?>(null) }
    CockpitDialog(title = stringResource(R.string.settings), icon = Icons.Default.Settings, accent = TextSecondary, onDismiss = onDismiss, tag = "settings_dialog") {
        Row(Modifier.fillMaxSize()) {
            DialogTabs(SettingsTab.entries, tab, { stringResource(it.label) }, { it.icon }, { tab = it })

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
                            CockpitSwitch(state.obdEnabled, actions.onObd)
                        }
                    }
                    SettingsTab.NAVIGATION -> Page {
                        Setting(stringResource(R.string.settings_auto_zoom), stringResource(R.string.settings_auto_zoom_hint)) {
                            CockpitSwitch(state.autoZoom, actions.onAutoZoom)
                        }
                        Setting(stringResource(R.string.settings_auto_follow), stringResource(R.string.settings_auto_follow_hint)) {
                            CockpitSwitch(state.autoFollowTracks, actions.onAutoFollow)
                        }
                        val o = state.offRoute
                        Setting(stringResource(R.string.settings_off_route), stringResource(R.string.settings_off_route_hint)) {
                            Segmented(listOf(25, 40, 60, 100), o.offRouteMeters, { stringResource(R.string.distance_m, it.toDouble()) }, { actions.onOffRoute(o.copy(offRouteMeters = it)) })
                        }
                        Setting(stringResource(R.string.settings_rejoin_minutes), stringResource(R.string.settings_rejoin_minutes_hint)) {
                            Segmented(listOf(1, 3, 5, 10), o.maxExtraMinutes, { stringResource(R.string.minutes_short, it) }, { actions.onOffRoute(o.copy(maxExtraMinutes = it)) })
                        }
                        Setting(stringResource(R.string.settings_rejoin_percent), stringResource(R.string.settings_rejoin_percent_hint)) {
                            Segmented(listOf(5, 10, 20, 30), o.maxExtraPercent, { "$it %" }, { actions.onOffRoute(o.copy(maxExtraPercent = it)) })
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
                        if (state.maps.isEmpty()) Text(stringResource(R.string.maps_none), color = TextSecondary, fontSize = 14.sp)
                        state.maps.forEach { m -> MapRow(m, state.mapDownload, state.mapQueue, actions) { confirmDelete = m } }
                        // Downloads queued from the country list that aren't installed yet.
                        val pending = listOfNotNull((state.mapDownload as? MapDownloadState.Running)?.areaId) + state.mapQueue
                        pending.filter { id -> state.maps.none { it.id == id } }.forEach { id -> PendingRow(id, state.mapName(id), state.mapDownload, actions) }
                        (state.mapDownload as? MapDownloadState.Failed)?.let { Text(it.message, color = WarningRed, fontSize = 13.sp) }
                        Button(onClick = actions.onBrowseMaps, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                            Icon(Icons.Default.Public, null, tint = OnCyan)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.maps_add), color = OnCyan, fontWeight = FontWeight.Bold)
                        }
                        Text(stringResource(R.string.settings_map_attribution),
                            color = TextMuted, fontSize = 12.sp)
                        TextButton(onClick = actions.onImportMap) { Text(stringResource(R.string.settings_import_pmtiles), color = ElectricCyan) }

                        SectionTitle(stringResource(R.string.settings_routing_data))
                        Text(state.routingDataStatus, color = TextSecondary, fontSize = 13.sp)
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
            title = { Text(stringResource(R.string.settings_delete_map_title, m.displayName())) },
            text = { Text(stringResource(R.string.settings_delete_map_text)) },
            confirmButton = { TextButton(onClick = { actions.onDeleteMap(m.id); confirmDelete = null }) { Text(stringResource(R.string.delete), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun InstalledMap.displayName() = area?.displayName(LocalConfiguration.current.locales[0]) ?: file.name

@Composable
private fun MapRow(m: InstalledMap, download: MapDownloadState, queue: List<String>, actions: SettingsActions, onDelete: () -> Unit) {
    val running = (download as? MapDownloadState.Running)?.takeIf { it.areaId == m.id }
    val busy = running != null || m.id in queue
    val missing = m.missingRouting.isNotEmpty() || m.missingSearch
    val warn = m.newerBuild != null || missing
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.displayName(), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        if (m.bytes < 1e9) stringResource(R.string.size_mb, maxOf(1.0, m.bytes / 1e6)) else stringResource(R.string.size_gb, m.bytes / 1e9),
                        if (m.area == null) stringResource(R.string.maps_by_hand) else m.build?.let { stringResource(R.string.settings_map_from, it) },
                        m.newerBuild?.let { stringResource(R.string.settings_map_newer, it) },
                        stringResource(R.string.maps_routing_missing).takeIf { m.missingRouting.isNotEmpty() },
                        when {
                            m.area == null -> null
                            m.search == com.curveon.data.search.SearchIndexes.Source.NONE -> stringResource(R.string.maps_search_missing)
                            m.betterSearch -> stringResource(R.string.maps_search_better)
                            m.search == com.curveon.data.search.SearchIndexes.Source.MAP -> stringResource(R.string.maps_search_basic)
                            else -> null
                        }
                    ).joinToString(" · "),
                    color = if (warn) NeonAmber else TextSecondary, fontSize = 13.sp
                )
            }
            if (busy) {
                if (running == null) Text(stringResource(R.string.maps_waiting), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                OutlinedButton(onClick = { actions.onCancelMapDownload(m.id) }) { Text(stringResource(R.string.cancel), color = WarningRed) }
            } else {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = TextMuted) }
                if (m.area != null) {
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { actions.onDownloadMap(m.id) }, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                        Text(stringResource(if (m.newerBuild == null && missing) R.string.maps_get_missing else R.string.update),
                            color = OnCyan, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (running != null) DownloadProgress(running)
    }
}

/** A queued or running download of a country that isn't on the tablet yet. */
@Composable
private fun PendingRow(id: String, name: String, download: MapDownloadState, actions: SettingsActions) {
    val running = (download as? MapDownloadState.Running)?.takeIf { it.areaId == id }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (running == null) Text(stringResource(R.string.maps_waiting), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            OutlinedButton(onClick = { actions.onCancelMapDownload(id) }) { Text(stringResource(R.string.cancel), color = WarningRed) }
        }
        if (running != null) DownloadProgress(running)
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
