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
    ENGINE(R.string.settings_engine, Icons.Default.Speed),
    PHONE(R.string.phone, Icons.Default.PhoneAndroid),
    DATA(R.string.settings_maps_data, Icons.Default.Map),
    HELP(R.string.settings_help, Icons.Default.HelpOutline)
}

/** Everything the Settings screen shows. */
data class SettingsState(
    val language: AppLanguage,
    val useMph: Boolean,
    val mapTheme: MapTheme,
    val screenLock: com.curveon.ScreenLock,
    val obdEnabled: Boolean,
    val obd: com.curveon.data.obd.ObdState,
    /** The chosen adapter's address, [com.curveon.data.obd.ObdLink.DEMO], or null. */
    val obdDevice: String?,
    val obdValues: List<com.curveon.data.obd.ObdValue>,
    val pairedDevices: () -> List<com.curveon.data.obd.PairedDevice>,
    val autoZoom: Boolean,
    val showSurfaces: Boolean,
    val showAltitude: Boolean,
    val autoFollowTracks: Boolean,
    val offRoute: com.curveon.data.routing.OffRouteSettings,
    val isNavigating: Boolean,
    val demoMode: Boolean,
    val maps: List<InstalledMap>,
    val mapDownload: MapDownloadState,
    val mapQueue: List<String>,
    /** Name of a catalog country/region by id, in the rider's language. */
    val mapName: (String) -> String,
    val routingDataStatus: String,
    val searchStatus: String,
    val speedLimitsStatus: String = "",
    val mapLayers: Set<com.curveon.data.search.MapLayer> = emptySet()
)

/** What the Settings screen can change. */
class SettingsActions(
    val onLanguage: (AppLanguage) -> Unit,
    val onUnits: (Boolean) -> Unit,
    val onMapTheme: (MapTheme) -> Unit,
    val onScreenLock: (com.curveon.ScreenLock) -> Unit,
    val onObd: (Boolean) -> Unit,
    val onObdDevice: (String?) -> Unit,
    val onObdValue: (com.curveon.data.obd.ObdValue) -> Unit,
    val onAutoZoom: (Boolean) -> Unit,
    val onShowSurfaces: (Boolean) -> Unit,
    val onShowAltitude: (Boolean) -> Unit,
    val onAutoFollow: (Boolean) -> Unit,
    val onOffRoute: (com.curveon.data.routing.OffRouteSettings) -> Unit,
    val onDemoMode: (Boolean) -> Unit,
    val onDownloadMap: (String) -> Unit,
    val onCancelMapDownload: (String) -> Unit,
    val onDeleteMap: (String) -> Unit,
    val onImportMap: () -> Unit,
    val onBrowseMaps: () -> Unit,
    /** Settings → Help: the welcome tour again. */
    val onTour: () -> Unit = {},
    val onMapLayer: (com.curveon.data.search.MapLayer, Boolean) -> Unit = { _, _ -> }
)

/** The kinds of place to keep on the map: one chip each, lit when on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapLayerChips(on: Set<com.curveon.data.search.MapLayer>, onToggle: (com.curveon.data.search.MapLayer, Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.curveon.data.search.MapLayer.entries.forEach { layer ->
            val selected = layer in on
            val color = Color(layer.icon.category.color)
            Row(
                Modifier.clip(RoundedCornerShape(22.dp))
                    .background(if (selected) color.copy(alpha = 0.22f) else CockpitSurfaceVariant)
                    .border(1.5.dp, if (selected) color else CockpitBorder, RoundedCornerShape(22.dp))
                    .clickable { onToggle(layer, !selected) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(layer.icon.icon, null, tint = if (selected) color else TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(layer.label), color = if (selected) TextPrimary else TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


/**
 * Full-screen settings with tabs on the left (like Android Auto), so each page stays short and
 * there is room for more.
 */
@Composable
fun SettingsDialog(state: SettingsState, actions: SettingsActions, phone: PhoneCompanionManager, openEngine: Boolean = false, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (openEngine) SettingsTab.ENGINE else SettingsTab.DISPLAY) }
    var confirmDelete by remember { mutableStateOf<InstalledMap?>(null) }
    CockpitDialog(title = stringResource(R.string.settings), icon = Icons.Default.Settings, accent = TextSecondary, onDismiss = onDismiss, tag = "settings_dialog") {
        val pages: @Composable () -> Unit = {
                when (tab) {
                    SettingsTab.DISPLAY -> Page {
                        if (AppLanguage.canChoose) Setting(stringResource(R.string.settings_language)) {
                            Segmented(AppLanguage.entries, state.language, { it.label }, actions.onLanguage)
                        }
                        Setting(stringResource(R.string.settings_speed)) {
                            Segmented(listOf(false, true), state.useMph, { if (it) "mph" else "km/h" }, actions.onUnits)
                        }
                        Setting(stringResource(R.string.settings_screen_lock), stringResource(R.string.settings_screen_lock_hint)) {
                            Segmented(com.curveon.ScreenLock.entries, state.screenLock, { stringResource(it.label) }, actions.onScreenLock)
                        }
                        Setting(stringResource(R.string.settings_map)) {
                            Segmented(MapTheme.entries, state.mapTheme, { stringResource(it.label) }, actions.onMapTheme)
                        }
                        Setting(stringResource(R.string.settings_surfaces), below = { SurfaceKey() }) {
                            Segmented(listOf(false, true), state.showSurfaces,
                                { stringResource(if (it) R.string.settings_surfaces_dashed else R.string.settings_surfaces_off) }, actions.onShowSurfaces)
                        }
                        Setting(stringResource(R.string.settings_altitude), stringResource(R.string.settings_altitude_hint), small = true) {
                            CockpitSwitch(state.showAltitude, actions.onShowAltitude)
                        }
                        SectionTitle(stringResource(R.string.settings_map_layers))
                        Text(stringResource(R.string.settings_map_layers_hint), color = TextMuted, fontSize = 13.sp)
                        MapLayerChips(state.mapLayers, actions.onMapLayer)
                    }
                    SettingsTab.NAVIGATION -> Page {
                        Setting(stringResource(R.string.settings_auto_zoom), stringResource(R.string.settings_auto_zoom_hint), small = true) {
                            CockpitSwitch(state.autoZoom, actions.onAutoZoom)
                        }
                        Setting(stringResource(R.string.settings_auto_follow), stringResource(R.string.settings_auto_follow_hint), small = true) {
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
                        if (com.curveon.BuildConfig.DEV) Setting(stringResource(R.string.settings_demo_ride), stringResource(R.string.settings_demo_ride_hint), small = true) {
                            CockpitSwitch(state.demoMode, actions.onDemoMode, accent = NeonAmber)
                        }
                    }
                    SettingsTab.ENGINE -> Page { EngineSettings(state, actions) }
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
                        SectionTitle(stringResource(R.string.settings_speed_limits))
                        Text(state.speedLimitsStatus, color = TextSecondary, fontSize = 13.sp)
                    }
                    SettingsTab.HELP -> Page { HelpPage(actions.onTour) }
                }
        }
        val tabs: @Composable () -> Unit = { DialogTabs(SettingsTab.entries, tab, { stringResource(it.label) }, { it.icon }, { tab = it }) }
        // Upright on a phone the page tabs go on top; otherwise down the left, like Android Auto.
        if (isNarrowScreen()) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tabs()
            Box(Modifier.weight(1f).fillMaxWidth()) { pages() }
        } else Row(Modifier.fillMaxSize()) {
            tabs()
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 6.dp)) { pages() }
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { HideSystemBars(); Text(stringResource(R.string.settings_delete_map_title, m.displayName())) },
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

/** Settings → Engine: the dashboard's engine panel on or off, the OBD2 adapter, and what it shows. */
@Composable
private fun ColumnScope.EngineSettings(state: SettingsState, actions: SettingsActions) {
    Setting(stringResource(R.string.settings_obd_show), stringResource(R.string.settings_obd_show_hint), small = true) {
        CockpitSwitch(state.obdEnabled, actions.onObd)
    }
    if (!state.obdEnabled) return
    SectionTitle(stringResource(R.string.settings_obd_adapter))
    Text(stringResource(R.string.settings_obd_adapter_hint), color = TextSecondary, fontSize = 13.sp)
    val paired = remember { state.pairedDevices() }
    val choices = paired.map { it.address to it.name } + (if (com.curveon.BuildConfig.DEV) listOf(com.curveon.data.obd.ObdLink.DEMO to stringResource(R.string.settings_obd_demo)) else emptyList())
    if (paired.isEmpty()) Text(stringResource(R.string.settings_obd_none_paired), color = NeonAmber, fontSize = 13.sp)
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
                        com.curveon.data.obd.ObdState.Status.NO_ADAPTER -> R.string.obd_bluetooth_off
                        else -> R.string.obd_not_answering
                    }),
                    color = if (state.obd.status == com.curveon.data.obd.ObdState.Status.CONNECTED) EcoGreen else NeonAmber,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.widthIn(max = 220.dp)
                )
            }
        }
    }
    SectionTitle(stringResource(R.string.settings_obd_values))
    com.curveon.data.obd.ObdValue.entries.forEach { v ->
        Setting(stringResource(when (v) {
            com.curveon.data.obd.ObdValue.FUEL -> R.string.poi_fuel
            com.curveon.data.obd.ObdValue.RPM -> R.string.obd_rpm
            com.curveon.data.obd.ObdValue.COOLANT -> R.string.obd_coolant
            com.curveon.data.obd.ObdValue.VOLTAGE -> R.string.obd_battery
        }), small = true) {
            CockpitSwitch(v in state.obdValues, { actions.onObdValue(v) })
        }
    }
}

/** Settings → Help: the welcome tour, and what every symbol in the app means. */
@Composable
private fun ColumnScope.HelpPage(onTour: () -> Unit) {
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
private fun Setting(title: String, hint: String? = null, below: (@Composable () -> Unit)? = null, small: Boolean = false, control: @Composable () -> Unit) {
    // Narrow: a wide choice goes under its title instead of beside it (a switch still fits beside).
    if (isNarrowScreen() && !small) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (hint != null) Text(hint, color = TextMuted, fontSize = 13.sp)
        below?.invoke()
        control()
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (hint != null) Text(hint, color = TextMuted, fontSize = 13.sp, maxLines = if (isNarrowScreen()) 3 else 1)
            below?.invoke()
        }
        control()
    }
}

/** What the map's dashes mean: a sample of each, as drawn on the dark map. */
@Composable
private fun SurfaceKey() {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DashSample(SurfaceColors.GRAVEL_DARK)
        Text(stringResource(R.string.surface_gravel), color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, end = 16.dp))
        DashSample(SurfaceColors.SOFT_DARK)
        Text(stringResource(R.string.surface_soft), color = TextMuted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun DashSample(hex: String) {
    val color = Color(android.graphics.Color.parseColor(hex))
    androidx.compose.foundation.Canvas(Modifier.size(width = 30.dp, height = 6.dp)) {
        drawLine(
            color, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(size.height * 1.6f, size.height * 1.1f))
        )
    }
}
