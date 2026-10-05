package com.vibenav.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibenav.data.bluetooth.CompanionConnectionState
import com.vibenav.data.model.GeoPoint
import com.vibenav.data.model.RoundtripConfig
import com.vibenav.data.search.SearchResult
import com.vibenav.ui.components.*
import com.vibenav.ui.theme.*
import com.vibenav.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Auto-inspired cockpit: a slim rail on the left, the map, and an adaptive column with
 * speed, then either the newest message (or a spoken reply in progress) plus a mini player, or
 * – when nothing needs attention – the music card with full-bleed cover art.
 */
@Composable
fun DashboardScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val routePoints by viewModel.activeRoutePoints.collectAsStateWithLifecycle()
    val groundPreference by viewModel.groundPreference.collectAsStateWithLifecycle()
    val currentTurnInstruction by viewModel.currentTurnInstruction.collectAsStateWithLifecycle()
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val navigationSummary by viewModel.navigationSummary.collectAsStateWithLifecycle()
    val routingStatus by viewModel.routingStatus.collectAsStateWithLifecycle()

    val phone = viewModel.phoneCompanion
    val track by phone.currentTrack.collectAsStateWithLifecycle()
    val albumArt by phone.albumArt.collectAsStateWithLifecycle()
    val notifications by phone.notifications.collectAsStateWithLifecycle()
    val voiceReply by phone.voiceReply.collectAsStateWithLifecycle()
    val phoneState by phone.connectionState.collectAsStateWithLifecycle()
    val phoneName by phone.connectedDeviceName.collectAsStateWithLifecycle()

    val offlineMapStatus by viewModel.offlineMapStatus.collectAsStateWithLifecycle()
    val routingDataStatus by viewModel.routingDataStatus.collectAsStateWithLifecycle()
    val isDemoRiding by viewModel.isDemoRiding.collectAsStateWithLifecycle()
    val savedGpxTracks by viewModel.savedGpxTracks.collectAsStateWithLifecycle()
    val gpxMessage by viewModel.gpxMessage.collectAsStateWithLifecycle()

    val importMapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importOfflineMap(uri)
    }
    val importGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importGpx(uri)
    }

    var showSearch by remember { mutableStateOf(false) }
    var showRoundtrip by remember { mutableStateOf(false) }
    var showGpx by remember { mutableStateOf(false) }
    var showPhoneLink by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var pickedResult by remember { mutableStateOf<SearchResult?>(null) }
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
        // In full-screen map keep music reachable, like Android Auto's media pill.
        if (isFullScreenMap) track?.let { t ->
            MiniMediaCard(t, albumArt, { phone.togglePlayPause() }, { phone.skipNext() },
                Modifier.align(Alignment.BottomEnd).padding(14.dp).width(300.dp))
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("dashboard_screen_root")
    ) {
        Rail(
            isFullScreenMap = isFullScreenMap,
            phoneState = phoneState,
            unread = notifications.size,
            onSearch = { showSearch = true },
            onRoundtrip = { showRoundtrip = true },
            onPhone = { showPhoneLink = true },
            onSettings = { showSettings = true },
            onToggleMap = { viewModel.toggleFullScreenMap() }
        )

        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                VibeMapView(
                    location = currentLocation,
                    headingDeg = telemetry.headingDeg,
                    speedKmh = telemetry.speedKmh,
                    speedLimitKmh = telemetry.speedLimitKmh,
                    hasGpsFix = telemetry.hasGpsFix,
                    routePoints = routePoints,
                    groundPreference = groundPreference,
                    currentTurnInstruction = currentTurnInstruction,
                    isFullScreen = isFullScreenMap,
                    onToggleFullScreen = { viewModel.toggleFullScreenMap() },
                    onLongPress = { longPressTarget = it },
                    overlays = mapOverlays
                )
            }

            if (!isFullScreenMap) {
                Column(
                    Modifier.width(SIDE_COLUMN_WIDTH).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpeedCard(telemetry, onToggleUnit = { viewModel.toggleSpeedUnits() })

                    val attention = voiceReply != null || notifications.isNotEmpty()
                    when {
                        voiceReply != null -> VoiceReplyCard(
                            reply = voiceReply!!,
                            onSend = { phone.confirmVoiceReply() },
                            onRetry = { phone.startVoiceReply(voiceReply!!.notificationKey) },
                            onCancel = { phone.cancelVoiceReply() },
                            modifier = Modifier.weight(1f)
                        )
                        notifications.isNotEmpty() -> NotificationCard(
                            notifications = notifications,
                            onReply = { phone.startVoiceReply(it.id) },
                            onDismiss = { phone.dismissNotification(it.id) },
                            onAction = { n, i -> phone.invokeAction(n.id, i) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (attention) {
                        track?.let { MiniMediaCard(it, albumArt, { phone.togglePlayPause() }, { phone.skipNext() }) }
                    } else {
                        FullMediaCard(
                            track = track, art = albumArt, phoneState = phoneState, phoneName = phoneName,
                            onPlayPause = { phone.togglePlayPause() }, onNext = { phone.skipNext() }, onPrevious = { phone.skipPrevious() },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (telemetry.isObdEnabled && telemetry.isObdConnected) Obd2TelemetryCard(telemetry = telemetry)
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    if (showSearch) {
        DestinationSearchDialog(
            searchStatus = remember { viewModel.searchStatus() },
            onSearch = { viewModel.searchPlaces(it) },
            onPick = { pickedResult = it; showSearch = false },
            onRoundtrip = { showSearch = false; showRoundtrip = true },
            onGpx = { showSearch = false; showGpx = true },
            onDismiss = { showSearch = false }
        )
    }

    pickedResult?.let { r ->
        SearchResultRouteDialog(
            result = r,
            initial = lastRouteChoice,
            onDismiss = { pickedResult = null },
            onGo = { choice ->
                lastRouteChoice = choice
                pickedResult = null
                viewModel.navigateToDestination(GeoPoint(r.lat, r.lon), r.name, choice.roadType, choice.ground, choice.avoidFerries, choice.avoidMotorways)
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
                viewModel.navigateToDestination(target, "map point", choice.roadType, choice.ground, choice.avoidFerries, choice.avoidMotorways)
            }
        )
    }

    if (showRoundtrip) {
        RoundtripDialog(
            initialConfig = RoundtripConfig(groundPreference = groundPreference),
            onDismiss = { showRoundtrip = false },
            onGenerate = { config ->
                viewModel.generateCustomRoundtrip(config)
                showRoundtrip = false
            }
        )
    }

    if (showGpx) {
        GpxImportDialog(
            tracks = savedGpxTracks,
            message = gpxMessage,
            onImport = { importGpxLauncher.launch(arrayOf("*/*")) },
            onFollow = { track, reverse, ground ->
                viewModel.followGpx(track, reverse, ground)
                showGpx = false
            },
            onDelete = { viewModel.deleteGpx(it) },
            onDismiss = { showGpx = false }
        )
    }

    if (showPhoneLink) {
        PhoneLinkDialog(manager = phone, onDismiss = { showPhoneLink = false })
    }

    if (showSettings) {
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
            onToggleDemoRide = { viewModel.toggleDemoRide(); showSettings = false },
            onDismiss = { showSettings = false }
        )
    }
}

/**
 * ~1/3 of the 7" tablet's 960 dp width. Narrower made cover art and messages too small to read
 * at a glance; the map still gets ~55% of the screen and goes full width with one tap.
 */
private val SIDE_COLUMN_WIDTH = 300.dp

@Composable
private fun Rail(
    isFullScreenMap: Boolean,
    phoneState: CompanionConnectionState,
    unread: Int,
    onSearch: () -> Unit,
    onRoundtrip: () -> Unit,
    onPhone: () -> Unit,
    onSettings: () -> Unit,
    onToggleMap: () -> Unit
) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(CockpitSurface).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RailButton(Icons.Default.Search, "Where to?", ElectricCyan, Color(0xFF00363D), filled = true, tag = "rail_search", onClick = onSearch)
        RailButton(Icons.Default.Explore, "Roundtrip", NeonAmber, NeonAmber, tag = "rail_roundtrip", onClick = onRoundtrip)
        Box {
            RailButton(Icons.Default.PhoneAndroid, "Phone link", TextSecondary, TextPrimary, tag = "rail_phone", onClick = onPhone)
            val dot = when (phoneState) {
                CompanionConnectionState.CONNECTED -> EcoGreen
                CompanionConnectionState.DEMO -> NeonAmber
                CompanionConnectionState.CONNECTING -> ElectricCyan
                else -> WarningRed
            }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp).clip(CircleShape).background(dot).border(2.dp, CockpitSurface, CircleShape))
        }
        RailButton(Icons.Default.Settings, "Settings", TextSecondary, TextPrimary, tag = "rail_settings", onClick = onSettings)
        Spacer(Modifier.weight(1f))
        Clock()
        Box {
            RailButton(
                if (isFullScreenMap) Icons.Default.Dashboard else Icons.Default.Map,
                if (isFullScreenMap) "Dashboard" else "Full map",
                ElectricCyan, if (isFullScreenMap) Color(0xFF00363D) else ElectricCyan,
                filled = isFullScreenMap, tag = "rail_toggle_map", onClick = onToggleMap
            )
            if (isFullScreenMap && unread > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(NeonAmber),
                    contentAlignment = Alignment.Center
                ) { Text(unread.toString(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black) }
            }
        }
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    description: String,
    accent: Color,
    tint: Color,
    filled: Boolean = false,
    tag: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) accent else CockpitSurfaceVariant)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    Text(
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
        color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold
    )
}
