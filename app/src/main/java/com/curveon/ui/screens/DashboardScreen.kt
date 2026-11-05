package com.curveon.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.ui.components.*
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.DashboardViewModel

/**
 * Android Auto-inspired cockpit: a slim rail on the left, the map, and an adaptive column with
 * speed, then either the newest message (or a spoken reply in progress) plus a mini player, or
 * – when nothing needs attention – the music card with full-bleed cover art.
 *
 * Its parts are functions of their own, sharing one [DashboardScreenState]: the map and what lies over it
 * ([DashboardMapArea]), the cards beside it ([DashboardSideCards]), the rail ([DashboardRail]) and its screens
 * ([PanelLayer]), the call and the group over everything, and the dialogs. Kept apart because one function holding all
 * of it grew so big that the compiler wrote code Android 17 refused to run (it crashed at start on 2026-11-05).
 */
@Composable
fun DashboardScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val state = rememberDashboardScreenState(viewModel)
    val importMapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importOfflineMap(uri)
    }
    val importGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importGpx(uri)
    }
    val imports = remember(importGpxLauncher, importMapLauncher) {
        DashboardImports(importGpx = { importGpxLauncher.launch(arrayOf("*/*")) }, importMap = { importMapLauncher.launch(arrayOf("*/*")) })
    }
    // Portrait (a phone held upright): speed on top, then the map, the cards below it, the rail along the bottom.
    val portrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    DashboardEffects(viewModel, state, portrait)

    val rootModifier = Modifier
        .fillMaxSize()
        .background(CockpitBackground)
        .statusBarsPadding()
        .navigationBarsPadding()
        // A phone's camera hole sits on one edge: keep the rail and cards clear of it, whichever
        // way round the phone is held.
        .windowInsetsPadding(WindowInsets.displayCutout)
        // While the road's name shows, any touch outside its card puts it away (and still does what it does).
        .onGloballyPositioned { state.rootTopLeft = it.positionInRoot() }
        .pointerInput(state.roadNameShown) {
            if (!state.roadNameShown) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (!state.roadCardBounds.contains(down.position + state.rootTopLeft)) state.roadNameShown = false
            }
        }
        .testTag("dashboard_screen_root")
    CompositionLocalProvider(LocalTourTargets provides state.tourTargets) { Box(modifier.fillMaxSize()) {
        if (portrait) PortraitDashboard(viewModel, state, imports, rootModifier)
        else LandscapeDashboard(viewModel, state, imports, rootModifier)
        DashboardOffers(viewModel, state)
    } }

    DashboardPlaceDialogs(viewModel, state)
}

/** A phone held upright: the map from the top (the speed is on it); music and the engine panel share the bottom, the rail under them. */
@Composable
private fun PortraitDashboard(viewModel: DashboardViewModel, state: DashboardScreenState, imports: DashboardImports, rootModifier: Modifier) {
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val voiceReply by viewModel.phoneCompanion.voiceReply.collectAsStateWithLifecycle()
    val notifications by viewModel.phoneCompanion.notifications.collectAsStateWithLifecycle()
    val showEngine = showEngine(viewModel)
    Column(rootModifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMapArea(viewModel, state, Modifier.weight(1f).fillMaxWidth())
                // Planning a trip needs the map: the preview sheet covers much of it.
                // A message (or voice reply) and the one-row player or call above it need more than the share
                // on a small phone; the map gives up the difference, so the message keeps its text and buttons.
                val message = voiceReply != null || notifications.any { !it.isIncomingCall }
                if (!isFullScreenMap && preview == null) Row(
                    Modifier.fillMaxWidth().heightIn(min = if (message) PORTRAIT_MESSAGE_CARDS_MIN else 0.dp)
                        .fillMaxHeight(PORTRAIT_CARDS_SHARE).tourTarget(TourTarget.CARDS),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardSideCards(viewModel, portrait = true, showEngine = showEngine)
                    }
                    if (showEngine) DashboardEngine(viewModel, state, Modifier.weight(1f).fillMaxHeight())
                }
            }
            PanelLayer(viewModel, state, imports)
            CallLayer(viewModel, portrait = true)
            GroupLayer(viewModel, state)
        }
        DashboardRail(viewModel, state, portrait = true)
    }
}

/** On its side (the X7 on the handlebars): the rail down the left, the map, and the cards in a column beside it. */
@Composable
private fun LandscapeDashboard(viewModel: DashboardViewModel, state: DashboardScreenState, imports: DashboardImports, rootModifier: Modifier) {
    val isFullScreenMap by viewModel.isFullScreenMap.collectAsStateWithLifecycle()
    val showEngine = showEngine(viewModel)
    Row(rootModifier) {
        DashboardRail(viewModel, state, portrait = false)
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMapArea(viewModel, state, Modifier.weight(1f).fillMaxHeight())
                if (!isFullScreenMap) Column(
                    Modifier.width(SIDE_COLUMN_WIDTH).fillMaxHeight().tourTarget(TourTarget.CARDS),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showEngine) DashboardEngine(viewModel, state, Modifier)
                    DashboardSideCards(viewModel, portrait = false, showEngine = showEngine)
                }
            }
            PanelLayer(viewModel, state, imports)
            CallLayer(viewModel, portrait = false)
            GroupLayer(viewModel, state)
        }
    }
}

/** Engine data is shown: switched on, with values picked. */
@Composable
private fun showEngine(viewModel: DashboardViewModel): Boolean {
    val telemetryState = viewModel.telemetry.collectAsStateWithLifecycle()
    val obdEnabled by remember { derivedStateOf { telemetryState.value.isObdEnabled } }
    val obdValues by viewModel.obdValues.collectAsStateWithLifecycle()
    return obdEnabled && obdValues.isNotEmpty()
}

/** The engine panel (Settings → Engine), where the speed used to be. */
@Composable
private fun DashboardEngine(viewModel: DashboardViewModel, state: DashboardScreenState, modifier: Modifier) {
    val obdState by viewModel.obd.state.collectAsStateWithLifecycle()
    val obdValues by viewModel.obdValues.collectAsStateWithLifecycle()
    EnginePanel(obdState, obdValues, onOpenSettings = { state.settingsOnEngine = true; state.showSettings = true }, modifier = modifier)
}

/** Upright, the speed and music cards take this much of the height under the map. */
private const val PORTRAIT_CARDS_SHARE = 0.34f
/** Least height for the cards upright with a message: a 72 dp row (player or call) and the message's sender, a line and the buttons. */
private val PORTRAIT_MESSAGE_CARDS_MIN = 270.dp
