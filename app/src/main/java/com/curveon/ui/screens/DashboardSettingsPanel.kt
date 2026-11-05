package com.curveon.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.ui.components.*
import com.curveon.ui.viewmodel.DashboardViewModel

/** Settings from the rail, with the fuel setup and the map catalogue it opens. */
@Composable
internal fun SettingsPanel(viewModel: DashboardViewModel, state: DashboardScreenState, imports: DashboardImports) {
    val installedMaps by viewModel.installedMaps.collectAsStateWithLifecycle()
    val mapDownload by viewModel.mapDownload.collectAsStateWithLifecycle()
    val mapQueue by viewModel.mapQueue.collectAsStateWithLifecycle()
    var showMapCatalog by remember { mutableStateOf(false) }
    var showFuelSetup by remember { mutableStateOf(false) }
    val fuelCalibration by viewModel.fuelCalibration.collectAsStateWithLifecycle()
    val obdState by viewModel.obd.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshMapStatus() }
    SettingsDialog(
        state = settingsState(viewModel, installedMaps, mapDownload, mapQueue, fuelCalibration, obdState),
        actions = settingsActions(viewModel, state, imports, onFuelSetup = { showFuelSetup = true }, onBrowseMaps = { showMapCatalog = true }),
        phone = viewModel.phoneCompanion,
        openEngine = state.settingsOnEngine,
        openHelp = state.settingsOnHelp,
        onDismiss = { state.showSettings = false }
    )
    if (showFuelSetup) {
        FuelSetupDialog(
            calibration = fuelCalibration, obd = obdState, capturing = viewModel.fuelCapturing.collectAsStateWithLifecycle().value,
            onTank = viewModel::setTankLiters, onRegister = viewModel::registerFuelLevel, onBefore = viewModel::startFuelFill, onAfter = viewModel::finishFuelFill, onCancelFill = viewModel::cancelFuelFill, onRemove = viewModel::removeFuelLevel,
            onUseSource = viewModel::setFuelSource, onReset = viewModel::resetFuelSetup, onDismiss = { showFuelSetup = false }
        )
    }
    if (showMapCatalog) {
        MapCatalogDialog(
            catalog = viewModel.mapCatalog, installed = installedMaps, download = mapDownload, queue = mapQueue,
            // Asking Android how much it can clear may take seconds: the plain free space until it answers.
            freeBytes = androidx.compose.runtime.produceState(remember { viewModel.usableStorageBytes() },
                installedMaps, mapDownload is com.curveon.data.map.MapDownloadState.Running) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.freeStorageBytes() }
            }.value,
            downloadBytes = remember(installedMaps) { viewModel.mapDownloadSizer() },
            startContinent = remember { viewModel.currentContinent() },
            onDownload = viewModel::downloadMap, onCancel = viewModel::cancelMapDownload,
            onDismiss = { showMapCatalog = false }
        )
    }
}

/** What Settings shows: every setting as it is now, the maps, and what is installed and downloading. */
@Composable
private fun settingsState(
    viewModel: DashboardViewModel,
    installedMaps: List<com.curveon.data.map.InstalledMap>,
    mapDownload: com.curveon.data.map.MapDownloadState,
    mapQueue: List<String>,
    fuelCalibration: com.curveon.data.obd.FuelCalibration,
    obdState: com.curveon.data.obd.ObdState,
): SettingsState {
    val context = LocalContext.current
    val phone = viewModel.phoneCompanion
    val telemetryState = viewModel.telemetry.collectAsStateWithLifecycle()
    val useMph by remember { derivedStateOf { telemetryState.value.useMph } }
    val obdEnabled by remember { derivedStateOf { telemetryState.value.isObdEnabled } }
    val mapTheme by viewModel.mapTheme.collectAsStateWithLifecycle()
    val roadContrast by viewModel.roadContrast.collectAsStateWithLifecycle()
    val mapDetail by viewModel.mapDetail.collectAsStateWithLifecycle()
    val obdValues by viewModel.obdValues.collectAsStateWithLifecycle()
    val arrowLead by viewModel.arrowLead.collectAsStateWithLifecycle()
    val surfaceMode by viewModel.surfaceMode.collectAsStateWithLifecycle()
    val mapDark by viewModel.mapDark.collectAsStateWithLifecycle()
    val gravelHeatButton by viewModel.gravelHeatButton.collectAsStateWithLifecycle()
    val showAltitude by viewModel.showAltitude.collectAsStateWithLifecycle()
    val showClock by viewModel.showClock.collectAsStateWithLifecycle()
    val showBattery by viewModel.showBattery.collectAsStateWithLifecycle()
    val showPhoneBattery by viewModel.showPhoneBattery.collectAsStateWithLifecycle()
    val thisDeviceIsPhone by phone.thisDevice.collectAsStateWithLifecycle()
    val speedSign by viewModel.speedSign.collectAsStateWithLifecycle()
    val speedSignsSideRoads by viewModel.speedSignsSideRoads.collectAsStateWithLifecycle()
    val roadCardSize by viewModel.roadCardSize.collectAsStateWithLifecycle()
    val showRoadInfo by viewModel.showRoadInfo.collectAsStateWithLifecycle()
    val showMapMedia by viewModel.showMapMedia.collectAsStateWithLifecycle()
    val autoFollow by viewModel.autoFollowTracks.collectAsStateWithLifecycle()
    val navigationSummary by viewModel.navigationSummary.collectAsStateWithLifecycle()
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()
    val mapLayers by viewModel.mapLayers.collectAsStateWithLifecycle()
    val routingDataStatus by viewModel.routingDataStatus.collectAsStateWithLifecycle()
    val appUpdate by viewModel.appUpdate.collectAsStateWithLifecycle()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val autoZoom by viewModel.autoZoom.collectAsStateWithLifecycle()
    return SettingsState(
        language = remember { com.curveon.AppLanguage.current(context) },
        useMph = useMph, mapTheme = mapTheme, roadContrast = roadContrast, mapDetail = mapDetail, screenLock = viewModel.screenLock.collectAsStateWithLifecycle().value,
        obdEnabled = obdEnabled, fuelSetUp = fuelCalibration.source != null && fuelCalibration.tankLiters != null,
        obd = obdState, obdDevice = viewModel.obdDevice.collectAsStateWithLifecycle().value, obdValues = obdValues,
        pairedDevices = viewModel.obd::pairedDevices,
        autoZoom = autoZoom, arrowLead = arrowLead, surfaceMode = surfaceMode,
        mapDark = mapDark, gravelHeatButton = gravelHeatButton, showAltitude = showAltitude, showClock = showClock, showBattery = showBattery, showPhoneBattery = showPhoneBattery, phoneBatteryOffered = !thisDeviceIsPhone, speedSign = speedSign, speedSignsSideRoads = speedSignsSideRoads, roadCardSize = roadCardSize, showRoadInfo = showRoadInfo, showMapMedia = showMapMedia, autoFollowTracks = autoFollow,
        offRoute = viewModel.offRouteSettings.collectAsStateWithLifecycle().value,
        navAlerts = viewModel.navAlerts.collectAsStateWithLifecycle().value,
        offRouteAlerts = viewModel.offRouteAlerts.collectAsStateWithLifecycle().value,
        weather = viewModel.weather.enabled.collectAsStateWithLifecycle().value,
        voiceHeadsUp = viewModel.voiceHeadsUp.collectAsStateWithLifecycle().value,
        voiceMissing = viewModel.speechVoice.collectAsStateWithLifecycle().value?.takeIf {
            it.availability != com.curveon.protocol.Message.VoiceAvailability.OFFLINE && it.language == viewModel.speechLanguage()
        },
        isNavigating = navigationSummary != null, demoMode = demoMode,
        maps = installedMaps, mapDownload = mapDownload, mapQueue = mapQueue,
        mapLayers = mapLayers,
        mapName = { id -> viewModel.mapCatalog.byId[id]?.let { if (it.parent.isEmpty()) it.displayName(locale) else it.name } ?: id },
        routingDataStatus = routingDataStatus, searchStatus = remember { viewModel.searchStatus() },
        speedLimitsStatus = remember { viewModel.speedLimitsStatus() },
        appUpdate = appUpdate
    )
}

/** What each setting does when changed. */
@Composable
private fun settingsActions(
    viewModel: DashboardViewModel,
    state: DashboardScreenState,
    imports: DashboardImports,
    onFuelSetup: () -> Unit,
    onBrowseMaps: () -> Unit,
): SettingsActions {
    val context = LocalContext.current
    return SettingsActions(
        onLanguage = { com.curveon.AppLanguage.set(context, it) },
        onUnits = viewModel::setUseMph,
        onMapTheme = viewModel::setMapTheme,
        onRoadContrast = viewModel::setRoadContrast,
        onMapDetail = viewModel::setMapDetail,
        onScreenLock = viewModel::setScreenLock,
        onObd = viewModel::setObd,
        onObdDevice = viewModel::setObdDevice,
        onObdValue = viewModel::toggleObdValue,
        onAutoZoom = viewModel::setAutoZoom,
        onArrowLead = viewModel::setArrowLead,
        onSurfaceMode = viewModel::setSurfaceMode,
        onGravelHeatButton = viewModel::setGravelHeatButton,
        onShowAltitude = viewModel::setShowAltitude,
        onShowClock = viewModel::setShowClock,
        onShowBattery = viewModel::setShowBattery,
        onShowPhoneBattery = viewModel::setShowPhoneBattery,
        onSpeedSign = viewModel::setSpeedSign,
        onSpeedSignsSideRoads = viewModel::setSpeedSignsSideRoads,
        onRoadCardSize = viewModel::setRoadCardSize,
        onShowRoadInfo = viewModel::setShowRoadInfo,
        onShowMapMedia = viewModel::setShowMapMedia,
        onAutoFollow = viewModel::setAutoFollow,
        onOffRoute = viewModel::setOffRouteSettings,
        onNavAlerts = viewModel::setNavAlerts,
        onOffRouteAlerts = viewModel::setOffRouteAlerts,
        onWeather = viewModel.weather::setEnabled,
        onVoiceHeadsUp = viewModel::setVoiceHeadsUp,
        onInstallVoice = {
            runCatching { context.startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                .onFailure { runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) } }
        },
        onDemoMode = viewModel::setDemoMode,
        onFuelSetup = onFuelSetup,
        onDownloadMap = viewModel::downloadMap,
        onCancelMapDownload = viewModel::cancelMapDownload,
        onDeleteMap = viewModel::deleteMap,
        onImportMap = { imports.importMap() },
        onBrowseMaps = onBrowseMaps,
        onTour = { state.showSettings = false; state.tourIsFirst = false; state.showTour = true; viewModel.resetTripHelp() },
        onMapLayer = viewModel::setMapLayer,
        onCheckUpdate = { viewModel.checkForUpdate() },
        onInstallUpdate = viewModel::installUpdate,
        onWhatsNew = { state.whatsNew = viewModel.changelog() }
    )
}
