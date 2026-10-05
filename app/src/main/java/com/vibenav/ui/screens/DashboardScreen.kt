package com.vibenav.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibenav.data.model.GeoPoint
import com.vibenav.data.model.RoundtripConfig
import com.vibenav.ui.components.*
import com.vibenav.ui.theme.*
import com.vibenav.ui.viewmodel.DashboardViewModel

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val routePoints by viewModel.activeRoutePoints.collectAsStateWithLifecycle()
    val groundPreference by viewModel.groundPreference.collectAsStateWithLifecycle()
    val currentTurnInstruction by viewModel.currentTurnInstruction.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()

    val currentTrack by viewModel.phoneCompanion.currentTrack.collectAsStateWithLifecycle()
    val notifications by viewModel.phoneCompanion.notifications.collectAsStateWithLifecycle()
    val connectionState by viewModel.phoneCompanion.connectionState.collectAsStateWithLifecycle()
    val connectedDeviceName by viewModel.phoneCompanion.connectedDeviceName.collectAsStateWithLifecycle()

    val savedRoutes by viewModel.savedRoutes.collectAsStateWithLifecycle()
    val savedGpxTracks by viewModel.savedGpxTracks.collectAsStateWithLifecycle()

    val offlineMapStatus by viewModel.offlineMapStatus.collectAsStateWithLifecycle()
    val routingDataStatus by viewModel.routingDataStatus.collectAsStateWithLifecycle()
    val navigationSummary by viewModel.navigationSummary.collectAsStateWithLifecycle()
    val routingStatus by viewModel.routingStatus.collectAsStateWithLifecycle()
    val isDemoRiding by viewModel.isDemoRiding.collectAsStateWithLifecycle()
    var longPressTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var lastRouteChoice by remember { mutableStateOf(RouteChoice()) }

    val mapOverlays: @Composable BoxScope.() -> Unit = {
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoutingStatusCard(routingStatus, onDismiss = { viewModel.dismissRoutingError() })
            navigationSummary?.let { NavigationSummaryBar(it, onStop = { viewModel.stopNavigation() }) }
        }
    }
    val importMapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importOfflineMap(uri)
    }

    // Dialogs state
    var showNavigationMenuDialog by remember { mutableStateOf(false) }
    var showRoundtripDialog by remember { mutableStateOf(false) }
    var showGpxDialog by remember { mutableStateOf(false) }
    var showCompanionDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("dashboard_screen_root")
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Slim Vertical Navigation Rail on Left (Available on both Dashboard and Full Map Mode)
            Column(
                modifier = Modifier
                    .width(54.dp)
                    .fillMaxHeight()
                    .background(CockpitSurface)
                    .border(
                        width = 1.dp,
                        color = CockpitBorder,
                        shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                    )
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Section: App Logo / Navigation Menu + Contextual Action Icons
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Navigation Menu Button (Motorcycle Icon - Opens Address & POI Navigation)
                    IconButton(
                        onClick = { showNavigationMenuDialog = true },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElectricCyan)
                            .testTag("action_nav_menu_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.TwoWheeler,
                            contentDescription = "Open Navigation Menu",
                            tint = Color(0xFF00363D),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Roundtrip Button (Explore Icon) - Available in both modes
                    IconButton(
                        onClick = { showRoundtripDialog = true },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NeonAmber.copy(alpha = 0.2f))
                            .border(1.dp, NeonAmber, RoundedCornerShape(12.dp))
                            .testTag("action_roundtrip_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "GoRide Roundtrip",
                            tint = NeonAmber,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // GPX Routes Button - ONLY available in map mode
                    if (isFullScreenMap) {
                        IconButton(
                            onClick = { showGpxDialog = true },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CockpitSurfaceVariant)
                                .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                                .testTag("action_gpx_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AltRoute,
                                contentDescription = "GPX Routes",
                                tint = ElectricCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Phone Companion Bridge Button - ONLY available in dashboard mode
                    if (!isFullScreenMap) {
                        IconButton(
                            onClick = { showCompanionDialog = true },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CockpitSurfaceVariant)
                                .border(1.dp, EcoGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .testTag("action_companion_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = "Phone Link",
                                tint = EcoGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Settings Button - ONLY available in dashboard mode
                    if (!isFullScreenMap) {
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CockpitSurfaceVariant)
                                .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                                .testTag("action_settings_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Bottom Section: Toggle Button between Dashboard & Full Map (replaces clock)
                IconButton(
                    onClick = { viewModel.toggleFullScreenMap() },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isFullScreenMap) NeonAmber else CockpitSurfaceVariant)
                        .border(
                            1.dp,
                            if (isFullScreenMap) NeonAmber else ElectricCyan,
                            RoundedCornerShape(12.dp)
                        )
                        .testTag("action_toggle_view_btn")
                ) {
                    Icon(
                        imageVector = if (isFullScreenMap) Icons.Default.Dashboard else Icons.Default.Map,
                        contentDescription = if (isFullScreenMap) "Switch to Dashboard" else "Switch to Full Map",
                        tint = if (isFullScreenMap) Color.Black else ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Main Dashboard Body: 2/3 Map and 1/3 Controls
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 8.dp,
                        end = 10.dp,
                        top = 6.dp,
                        bottom = 6.dp
                    )
            ) {
                val isLandscape = maxWidth > maxHeight

                if (isFullScreenMap) {
                    // FULL SCREEN MAP MODE
                    VibeMapView(
                        location = currentLocation,
                        headingDeg = telemetry.headingDeg,
                        speedKmh = telemetry.speedKmh,
                        hasGpsFix = telemetry.hasGpsFix,
                        speedLimitKmh = telemetry.speedLimitKmh,
                        routePoints = routePoints,
                        groundPreference = groundPreference,
                        currentTurnInstruction = currentTurnInstruction,
                        isFullScreen = true,
                        onToggleFullScreen = { viewModel.toggleFullScreenMap() },
                        onLongPress = { longPressTarget = it },
                        overlays = mapOverlays
                    )
                } else if (isLandscape) {
                    // TABLET LANDSCAPE: 2/3 Map on Left + 1/3 Side Dashboard on Right
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 2/3 SCREEN: Map
                        Box(
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxHeight()
                        ) {
                            VibeMapView(
                                location = currentLocation,
                                headingDeg = telemetry.headingDeg,
                                speedKmh = telemetry.speedKmh,
                                hasGpsFix = telemetry.hasGpsFix,
                                speedLimitKmh = telemetry.speedLimitKmh,
                                routePoints = routePoints,
                                groundPreference = groundPreference,
                                currentTurnInstruction = currentTurnInstruction,
                                isFullScreen = false,
                                onToggleFullScreen = { viewModel.toggleFullScreenMap() },
                                onLongPress = { longPressTarget = it },
                                overlays = mapOverlays
                            )
                        }

                        // 1/3 SCREEN: Shared Instrumental Cluster (Speedometer, Music, Notifications, OBD2)
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. Speedometer & Tachometer
                            item {
                                SpeedometerTachometer(
                                    telemetry = telemetry,
                                    onToggleUnit = { viewModel.toggleSpeedUnits() }
                                )
                            }

                            // 2. Music Player Control
                            item {
                                MusicCard(
                                    track = currentTrack,
                                    connectedDeviceName = connectedDeviceName,
                                    onPlayPause = { viewModel.phoneCompanion.togglePlayPause() },
                                    onSkipNext = { viewModel.phoneCompanion.skipNext() },
                                    onSkipPrevious = { viewModel.phoneCompanion.skipPrevious() }
                                )
                            }

                            // 3. Notifications from phone
                            item {
                                NotificationPanel(
                                    notifications = notifications,
                                    onDismiss = { id -> viewModel.phoneCompanion.dismissNotification(id) },
                                    onQuickReply = { id, reply -> viewModel.phoneCompanion.sendQuickReply(id, reply) }
                                )
                            }

                            // 4. Optional OBD2 Telemetry (Visible when enabled)
                            if (telemetry.isObdEnabled) {
                                item {
                                    Obd2TelemetryCard(telemetry = telemetry)
                                }
                            }
                        }
                    }
                } else {
                    // PORTRAIT / NARROW MODE: 2/3 Map Top + 1/3 Dashboard Bottom
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1.8f)
                                .fillMaxWidth()
                        ) {
                            VibeMapView(
                                location = currentLocation,
                                headingDeg = telemetry.headingDeg,
                                speedKmh = telemetry.speedKmh,
                                hasGpsFix = telemetry.hasGpsFix,
                                speedLimitKmh = telemetry.speedLimitKmh,
                                routePoints = routePoints,
                                groundPreference = groundPreference,
                                currentTurnInstruction = currentTurnInstruction,
                                isFullScreen = false,
                                onToggleFullScreen = { viewModel.toggleFullScreenMap() },
                                onLongPress = { longPressTarget = it },
                                overlays = mapOverlays
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1.2f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                SpeedometerTachometer(
                                    telemetry = telemetry,
                                    onToggleUnit = { viewModel.toggleSpeedUnits() }
                                )
                            }
                            item {
                                MusicCard(
                                    track = currentTrack,
                                    connectedDeviceName = connectedDeviceName,
                                    onPlayPause = { viewModel.phoneCompanion.togglePlayPause() },
                                    onSkipNext = { viewModel.phoneCompanion.skipNext() },
                                    onSkipPrevious = { viewModel.phoneCompanion.skipPrevious() }
                                )
                            }
                            item {
                                NotificationPanel(
                                    notifications = notifications,
                                    onDismiss = { id -> viewModel.phoneCompanion.dismissNotification(id) },
                                    onQuickReply = { id, reply -> viewModel.phoneCompanion.sendQuickReply(id, reply) }
                                )
                            }
                            if (telemetry.isObdEnabled) {
                                item {
                                    Obd2TelemetryCard(telemetry = telemetry)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dialogs
        if (showNavigationMenuDialog) {
            NavigationMenuDialog(
                currentLocation = currentLocation,
                onDismiss = { showNavigationMenuDialog = false },
                onNavigateToDestination = { destination, name, roadType, groundPreference ->
                    viewModel.navigateToDestination(destination, name, roadType, groundPreference)
                    showNavigationMenuDialog = false
                }
            )
        }

        longPressTarget?.let { target ->
            NavigateHereDialog(
                target = target,
                initial = lastRouteChoice,
                onDismiss = { longPressTarget = null },
                onGo = { choice ->
                    lastRouteChoice = choice
                    longPressTarget = null
                    viewModel.navigateToDestination(
                        target, "map point", choice.roadType, choice.ground, choice.avoidFerries, choice.avoidMotorways
                    )
                }
            )
        }

        if (showRoundtripDialog) {
            RoundtripDialog(
                initialConfig = RoundtripConfig(groundPreference = groundPreference),
                onDismiss = { showRoundtripDialog = false },
                onGenerate = { config ->
                    viewModel.generateCustomRoundtrip(config)
                    showRoundtripDialog = false
                }
            )
        }

        if (showGpxDialog) {
            GpxImportDialog(
                savedRoutes = savedRoutes,
                savedGpxTracks = savedGpxTracks,
                onSelectRoute = { route -> viewModel.selectSavedRoute(route) },
                onLoadSampleGpx = { sample -> viewModel.loadSampleGpx(sample) },
                onDismiss = { showGpxDialog = false }
            )
        }

        if (showCompanionDialog) {
            PhoneCompanionDialog(
                connectionState = connectionState,
                connectedDeviceName = connectedDeviceName,
                onTriggerSimulatedNotification = { app, sender, msg, isCall ->
                    viewModel.phoneCompanion.triggerSimulatedNotification(app, sender, msg, isCall)
                },
                onDismiss = { showCompanionDialog = false }
            )
        }

        if (showSettingsDialog) {
            SettingsDialog(
                useMph = telemetry.useMph,
                onToggleUnits = { viewModel.toggleSpeedUnits() },
                isObdEnabled = telemetry.isObdEnabled,
                onToggleObd = { viewModel.toggleObd() },
                offlineMapStatus = offlineMapStatus,
                onImportMap = { importMapLauncher.launch(arrayOf("*/*")) },
                routingDataStatus = routingDataStatus,
                onDownloadRoutingData = { viewModel.downloadRoutingData() },
                isNavigating = navigationSummary != null,
                isDemoRiding = isDemoRiding,
                onToggleDemoRide = { viewModel.toggleDemoRide(); showSettingsDialog = false },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}
