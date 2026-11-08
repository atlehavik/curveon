package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.curveon.data.map.InstalledMap
import com.curveon.data.map.MapDownloadState
import com.curveon.data.update.UpdateState
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.MapDetail
import com.curveon.ui.viewmodel.RoadContrast
import com.curveon.ui.viewmodel.MapTheme

private val OnCyan = Color(0xFF00363D)

private enum class SettingsTab(val label: Int, val icon: ImageVector) {
    DISPLAY(R.string.settings_display, Icons.Default.Tune),
    NAVIGATION(R.string.settings_navigation, Icons.Default.Navigation),
    ENGINE(R.string.settings_engine, Icons.Default.Speed),
    PHONE(R.string.phone, Icons.Default.PhoneAndroid),
    REMOTE(R.string.settings_remote, Icons.Default.SportsEsports),
    DATA(R.string.settings_maps_data, Icons.Default.Map),
    HELP(R.string.settings_help, Icons.Default.HelpOutline)
}

/** Everything the Settings screen shows. */
data class SettingsState(
    val language: AppLanguage,
    val useMph: Boolean,
    val mapTheme: MapTheme,
    val roadContrast: RoadContrast = RoadContrast.NORMAL,
    val mapDetail: MapDetail = MapDetail.RESPONSE,
    val screenLock: com.curveon.ScreenLock,
    val obdEnabled: Boolean,
    val obd: com.curveon.data.obd.ObdState,
    /** The chosen adapter's address, [com.curveon.data.obd.ObdLink.DEMO], or null. */
    val obdDevice: String?,
    val obdValues: List<com.curveon.data.obd.ObdValue>,
    /** The fuel gauge has a tank table and a signal (Settings → Engine → Fuel gauge setup). */
    val fuelSetUp: Boolean = false,
    val pairedDevices: () -> List<com.curveon.data.obd.PairedDevice>,
    val autoZoom: Boolean,
    val arrowLead: Double = com.curveon.data.location.RiderMotion.DEFAULT_LEAD_S,
    val surfaceMode: SurfaceMode = SurfaceMode.COLOURED,
    /** Whether the map is drawn dark now, so the dash colours are shown as they look on it. */
    val mapDark: Boolean = true,
    val gravelHeatButton: GravelHeatButton = GravelHeatButton.OFF,
    val buildings3d: Boolean = false,
    val quietTowns: Boolean = true,
    val showAltitude: Boolean,
    val showClock: Boolean = true,
    val showBattery: Boolean = false,
    val showPhoneBattery: Boolean = false,
    /** A companion phone is linked: Curveon on a phone of its own has no other phone to show the battery of. */
    val phoneBatteryOffered: Boolean = true,
    val speedSign: com.curveon.ui.viewmodel.SpeedSignSize = com.curveon.ui.viewmodel.SpeedSignSize.HUGE,
    val speedSignsSideRoads: Boolean = false,
    val showRoadInfo: Boolean = true,
    val roadCardSize: com.curveon.ui.viewmodel.RoadCardSize = com.curveon.ui.viewmodel.RoadCardSize.NORMAL,
    val showMapMedia: Boolean = true,
    val autoFollowTracks: Boolean,
    val offRoute: com.curveon.data.routing.OffRouteSettings,
    val navAlerts: com.curveon.data.routing.NavAlerts = com.curveon.data.routing.NavAlerts.SOUNDS,
    val offRouteAlerts: Boolean = true,
    /** Weather along the trip and "Good weather today" (sends a rough position to MET Norway when planning). */
    val weather: Boolean = true,
    val voiceHeadsUp: Boolean = true,
    /** The voice where speech plays, when it is not downloaded there: Settings suggests getting it. */
    val voiceMissing: com.curveon.data.bluetooth.PhoneCompanionManager.VoiceStatus? = null,
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
    val mapLayers: Set<com.curveon.data.search.MapLayer> = emptySet(),
    val appUpdate: UpdateState = UpdateState.Idle
)

/** What the Settings screen can change. */
class SettingsActions(
    val onLanguage: (AppLanguage) -> Unit,
    val onUnits: (Boolean) -> Unit,
    val onMapTheme: (MapTheme) -> Unit,
    val onRoadContrast: (RoadContrast) -> Unit = {},
    val onMapDetail: (MapDetail) -> Unit = {},
    val onScreenLock: (com.curveon.ScreenLock) -> Unit,
    val onObd: (Boolean) -> Unit,
    val onObdDevice: (String?) -> Unit,
    val onObdValue: (com.curveon.data.obd.ObdValue) -> Unit,
    val onFuelSetup: () -> Unit = {},
    val onAutoZoom: (Boolean) -> Unit,
    val onArrowLead: (Double) -> Unit = {},
    val onSurfaceMode: (SurfaceMode) -> Unit = {},
    val onGravelHeatButton: (GravelHeatButton) -> Unit = {},
    val onBuildings3d: (Boolean) -> Unit = {},
    val onQuietTowns: (Boolean) -> Unit = {},
    val onShowAltitude: (Boolean) -> Unit,
    val onShowClock: (Boolean) -> Unit = {},
    val onShowBattery: (Boolean) -> Unit = {},
    val onShowPhoneBattery: (Boolean) -> Unit = {},
    val onSpeedSign: (com.curveon.ui.viewmodel.SpeedSignSize) -> Unit = {},
    val onSpeedSignsSideRoads: (Boolean) -> Unit = {},
    val onRoadCardSize: (com.curveon.ui.viewmodel.RoadCardSize) -> Unit = {},
    val onShowRoadInfo: (Boolean) -> Unit = {},
    val onShowMapMedia: (Boolean) -> Unit = {},
    val onAutoFollow: (Boolean) -> Unit,
    val onOffRoute: (com.curveon.data.routing.OffRouteSettings) -> Unit,
    val onNavAlerts: (com.curveon.data.routing.NavAlerts) -> Unit = {},
    val onOffRouteAlerts: (Boolean) -> Unit = {},
    val onWeather: (Boolean) -> Unit = {},
    val onVoiceHeadsUp: (Boolean) -> Unit = {},
    /** Opens the text-to-speech engine's voice download on this device. */
    val onInstallVoice: () -> Unit = {},
    val onDemoMode: (Boolean) -> Unit,
    val onDownloadMap: (String) -> Unit,
    val onCancelMapDownload: (String) -> Unit,
    val onDeleteMap: (String) -> Unit,
    val onImportMap: () -> Unit,
    val onBrowseMaps: () -> Unit,
    /** Settings → Help: the welcome tour again. */
    val onTour: () -> Unit = {},
    val onMapLayer: (com.curveon.data.search.MapLayer, Boolean) -> Unit = { _, _ -> },
    val onCheckUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    /** Settings → Help: what changed in each version. */
    val onWhatsNew: () -> Unit = {}
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
fun SettingsDialog(state: SettingsState, actions: SettingsActions, phone: PhoneCompanionManager, openEngine: Boolean = false, openHelp: Boolean = false, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(when { openEngine -> SettingsTab.ENGINE; openHelp -> SettingsTab.HELP; else -> SettingsTab.DISPLAY }) }
    var confirmDelete by remember { mutableStateOf<InstalledMap?>(null) }
    PagedCockpitDialog(
        title = stringResource(R.string.settings), icon = Icons.Default.Settings, accent = TextSecondary, onDismiss = onDismiss, tag = "settings_dialog",
        pages = SettingsTab.entries, selected = tab, onSelect = { tab = it }, pageLabel = { stringResource(it.label) }, pageIcon = { it.icon }
    ) { current ->
        val pageFor: @Composable (SettingsTab) -> Unit = { which ->
                when (which) {
                    SettingsTab.REMOTE -> Page { LocalRemote.current?.let { RemoteSettingsPage(it) } }
                    SettingsTab.DISPLAY -> Page {
                        if (AppLanguage.canChoose) Setting(stringResource(R.string.settings_language), stringResource(R.string.settings_language_hint)) {
                            Choice(AppLanguage.entries, state.language, { it.label }, actions.onLanguage)
                        }
                        Setting(stringResource(R.string.settings_speed), stringResource(R.string.settings_speed_hint)) {
                            Choice(listOf(false, true), state.useMph, { if (it) "mph" else "km/h" }, actions.onUnits)
                        }
                        Setting(stringResource(R.string.settings_screen_lock), stringResource(R.string.settings_screen_lock_hint)) {
                            Choice(com.curveon.ScreenLock.entries, state.screenLock, { stringResource(it.label) }, actions.onScreenLock)
                        }
                        Setting(stringResource(R.string.settings_map), stringResource(R.string.settings_map_hint)) {
                            Choice(MapTheme.entries, state.mapTheme, { stringResource(it.label) }, actions.onMapTheme)
                        }
                        Setting(stringResource(R.string.settings_roads), stringResource(R.string.settings_roads_hint)) {
                            Choice(RoadContrast.entries, state.roadContrast, { stringResource(it.label) }, actions.onRoadContrast)
                        }
                        Setting(stringResource(R.string.settings_map_detail), stringResource(R.string.settings_map_detail_hint)) {
                            Choice(MapDetail.entries, state.mapDetail, { stringResource(it.label) }, actions.onMapDetail)
                        }
                        Setting(stringResource(R.string.settings_surfaces), stringResource(R.string.settings_surfaces_hint), below = {
                            if (state.surfaceMode != SurfaceMode.OFF) SurfaceColours(state.surfaceMode, state.mapDark)
                        }) {
                            Choice(SurfaceMode.entries, state.surfaceMode, { stringResource(it.label) }, actions.onSurfaceMode)
                        }
                        Setting(stringResource(R.string.settings_gravel_heat), stringResource(R.string.settings_gravel_heat_hint)) {
                            CockpitSwitch(state.gravelHeatButton == GravelHeatButton.MAP, onCheckedChange = {
                                actions.onGravelHeatButton(if (it) GravelHeatButton.MAP else GravelHeatButton.OFF)
                            })
                        }
                        Setting(stringResource(R.string.settings_quiet_towns), stringResource(R.string.settings_quiet_towns_hint)) {
                            CockpitSwitch(state.quietTowns, actions.onQuietTowns)
                        }
                        Setting(stringResource(R.string.settings_buildings_3d), stringResource(R.string.settings_buildings_3d_hint)) {
                            CockpitSwitch(state.buildings3d, actions.onBuildings3d)
                        }
                        Setting(stringResource(R.string.settings_altitude), stringResource(R.string.settings_altitude_hint)) {
                            CockpitSwitch(state.showAltitude, actions.onShowAltitude)
                        }
                        Setting(stringResource(R.string.settings_clock), stringResource(R.string.settings_clock_hint)) {
                            CockpitSwitch(state.showClock, actions.onShowClock)
                        }
                        Setting(stringResource(R.string.settings_battery), stringResource(R.string.settings_battery_hint)) {
                            CockpitSwitch(state.showBattery, actions.onShowBattery)
                        }
                        if (state.phoneBatteryOffered) Setting(stringResource(R.string.settings_phone_battery), stringResource(R.string.settings_phone_battery_hint)) {
                            CockpitSwitch(state.showPhoneBattery, actions.onShowPhoneBattery)
                        }
                        Setting(stringResource(R.string.settings_speed_sign), stringResource(R.string.settings_speed_sign_hint)) {
                            Choice(com.curveon.ui.viewmodel.SpeedSignSize.entries, state.speedSign, { stringResource(it.label) }, actions.onSpeedSign)
                        }
                        Setting(stringResource(R.string.settings_side_road_signs), stringResource(R.string.settings_side_road_signs_hint)) {
                            CockpitSwitch(state.speedSignsSideRoads, actions.onSpeedSignsSideRoads)
                        }
                        Setting(stringResource(R.string.settings_road_info), stringResource(R.string.settings_road_info_hint)) {
                            CockpitSwitch(state.showRoadInfo, actions.onShowRoadInfo)
                        }
                        Setting(stringResource(R.string.settings_road_size), stringResource(R.string.settings_road_size_hint)) {
                            Choice(com.curveon.ui.viewmodel.RoadCardSize.entries, state.roadCardSize, { stringResource(it.label) }, actions.onRoadCardSize)
                        }
                        Setting(stringResource(R.string.settings_map_media), stringResource(R.string.settings_map_media_hint)) {
                            CockpitSwitch(state.showMapMedia, actions.onShowMapMedia)
                        }
                        SectionTitle(stringResource(R.string.settings_map_layers))
                        Text(stringResource(R.string.settings_map_layers_hint), color = TextMuted, fontSize = 13.sp)
                        MapLayerChips(state.mapLayers, actions.onMapLayer)
                    }
                    SettingsTab.NAVIGATION -> Page {
                        Setting(stringResource(R.string.settings_auto_zoom), stringResource(R.string.settings_auto_zoom_hint)) {
                            CockpitSwitch(state.autoZoom, actions.onAutoZoom)
                        }
                        Setting(stringResource(R.string.settings_arrow), stringResource(R.string.settings_arrow_hint)) {
                            ArrowLeadStepper(state.arrowLead, actions.onArrowLead)
                        }
                        Setting(stringResource(R.string.settings_auto_follow), stringResource(R.string.settings_auto_follow_hint)) {
                            CockpitSwitch(state.autoFollowTracks, actions.onAutoFollow)
                        }
                        Setting(stringResource(R.string.settings_weather), stringResource(R.string.settings_weather_hint)) {
                            CockpitSwitch(state.weather, actions.onWeather)
                        }
                        SectionTitle(stringResource(R.string.settings_sound_title))
                        val voice = state.navAlerts == com.curveon.data.routing.NavAlerts.VOICE
                        Setting(stringResource(R.string.settings_nav_alerts),
                            stringResource(if (voice) R.string.settings_nav_alerts_voice_hint else R.string.settings_nav_alerts_hint)) {
                            Choice(com.curveon.data.routing.NavAlerts.entries, state.navAlerts, {
                                stringResource(when (it) {
                                    com.curveon.data.routing.NavAlerts.OFF -> R.string.nav_alerts_off
                                    com.curveon.data.routing.NavAlerts.SOUNDS -> R.string.nav_alerts_sounds
                                    com.curveon.data.routing.NavAlerts.VOICE -> R.string.nav_alerts_voice
                                })
                            }, actions.onNavAlerts)
                        }
                        if (voice) {
                            state.voiceMissing?.let { VoiceDownloadTip(it, actions.onInstallVoice) }
                            Setting(stringResource(R.string.settings_voice_heads_up), stringResource(R.string.settings_voice_heads_up_hint)) {
                                CockpitSwitch(state.voiceHeadsUp, actions.onVoiceHeadsUp)
                            }
                        }
                        Setting(stringResource(R.string.settings_off_route_alerts), stringResource(R.string.settings_off_route_alerts_hint)) {
                            CockpitSwitch(state.offRouteAlerts, actions.onOffRouteAlerts)
                        }
                        val o = state.offRoute
                        SectionTitle(stringResource(R.string.settings_wrong_turn_title))
                        Setting(stringResource(R.string.settings_off_route), stringResource(R.string.settings_off_route_hint)) {
                            Choice(listOf(25, 40, 60, 100), o.offRouteMeters, { stringResource(R.string.distance_m, it.toDouble()) }, { actions.onOffRoute(o.copy(offRouteMeters = it)) })
                        }
                        Text(stringResource(R.string.settings_rejoin_explain), color = TextMuted, fontSize = 13.sp)
                        Setting(stringResource(R.string.settings_rejoin_minutes), stringResource(R.string.settings_rejoin_minutes_hint)) {
                            Choice(listOf(1, 3, 5, 10), o.maxExtraMinutes, { stringResource(R.string.minutes_short, it) }, { actions.onOffRoute(o.copy(maxExtraMinutes = it)) })
                        }
                        Setting(stringResource(R.string.settings_rejoin_percent), stringResource(R.string.settings_rejoin_percent_hint)) {
                            Choice(listOf(5, 10, 20, 30), o.maxExtraPercent, { "$it %" }, { actions.onOffRoute(o.copy(maxExtraPercent = it)) })
                        }
                        // Kept in public releases too: handy for showing and trying Curveon.
                        Setting(stringResource(R.string.settings_demo_ride), stringResource(R.string.settings_demo_ride_hint)) {
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
                        val running = state.mapDownload as? MapDownloadState.Running
                        val pending = listOfNotNull(running?.areaId, running?.finishing?.areaId) + state.mapQueue
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
                    SettingsTab.HELP -> Page { AppVersionCard(state.appUpdate, actions); HelpPage(actions.onTour) }
                }
        }
        pageFor(current)
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
