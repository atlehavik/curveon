package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.RouteCandidate
import com.curveon.data.routing.RoutePreview
import com.curveon.routing.RoadStyle
import com.curveon.routing.SurfacePreference
import com.curveon.ui.theme.*

private val OnCyan = Color(0xFF00363D)

/**
 * Shown over the map before riding: the alternatives side by side (tap to pick), the few options
 * that change the route, stops, save, and GO. Everything else stays out of the way.
 */
@Composable
fun RoutePreviewSheet(
    preview: RoutePreview,
    onSelect: (Int) -> Unit,
    onSurface: (SurfacePreference) -> Unit,
    onToggleFerries: () -> Unit,
    /** (avoid motorways, avoid trunk roads too). */
    onMotorways: (Boolean, Boolean) -> Unit,
    onRemoveStop: (Int) -> Unit,
    onShuffle: () -> Unit,
    /** Show another of the adventures found while the first was on the map. */
    onSelectSuggestion: (Int) -> Unit = {},
    onAddStop: () -> Unit = {},
    onMoveStop: (index: Int, delta: Int) -> Unit = { _, _ -> },
    onEditStart: () -> Unit = {},
    onReverse: () -> Unit = {},
    /** A track made into an ordinary trip, to change like one. */
    onTrackToTrip: () -> Unit = {},
    /** Pick another place for stop i (search or map). */
    onChangeStop: (Int) -> Unit = {},
    /** Put a new stop just before stop i. */
    onAddStopBefore: (Int) -> Unit = {},
    /** Edit the trip by dragging it on the map. */
    onEditOnMap: () -> Unit = {},
    /** A drawn loop: plan it again no longer than this (null = the whole area). */
    onLoopMax: (Int?) -> Unit = {},
    /** A drawn loop or adventure: draw the area again. */
    onRedraw: () -> Unit = {},
    /** A trip to a place: make it an adventure (draw the area to ride in, say how long). */
    onAdventure: () -> Unit = {},
    /** An adventure: plan it again taking this many minutes (null = no limit). */
    onAdventureMinutes: (Int?) -> Unit = {},
    /** Gravel/Offroad: how far off the ordinary route it may go for gravel (null = any distance). */
    onGravelReach: (Int?) -> Unit = {},
    onSave: () -> Unit,
    onGo: () -> Unit,
    onClose: () -> Unit,
    /** Folded down to one line (name, time, GO) so more of the route shows on the map. */
    minimized: Boolean = false,
    onMinimizedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val goal = preview.goal
    val options = goal.options
    var askDiscard by remember { mutableStateOf(false) }
    // Like "Pick on map": the sheet shrinks into the bar, the bar springs up, and the sheet grows back out of it.
    val scope = rememberCoroutineScope()
    var growIn by remember { mutableStateOf(false) }
    if (minimized) {
        MinimizedPreview(preview, onRestore = { growIn = true; onMinimizedChange(false) }, onGo = onGo, modifier = modifier)
        return
    }
    // 1 = open, 0 = shrunk into the bar.
    val size = remember { Animatable(if (growIn) 0f else 1f) }
    LaunchedEffect(Unit) { if (growIn) size.animateTo(1f, tween(260)) }
    val minimize: () -> Unit = {
        scope.launch {
            size.animateTo(0f, tween(280, easing = FastOutLinearInEasing))
            onMinimizedChange(true)
        }
    }
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = 0.2f + 0.8f * size.value
                scaleY = 0.08f + 0.92f * size.value
                alpha = (size.value * 1.6f).coerceAtMost(1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(22.dp))
            .background(CockpitSurface.copy(alpha = 0.97f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(22.dp))
            .padding(14.dp)
            .testTag("route_preview_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Title row: where to, save, close
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (goal) {
                    is NavigationGoal.Destination -> when {
                        goal.adventure != null -> stringResource(R.string.preview_adventure_title, goal.name)
                        goal.loop?.area != null -> stringResource(R.string.preview_area_roundtrip_title)
                        goal.loop != null -> stringResource(R.string.preview_loop_from, goal.name)
                        else -> goal.name
                    }
                    is NavigationGoal.Roundtrip -> if (goal.area != null) stringResource(R.string.preview_area_roundtrip_title)
                        else stringResource(R.string.preview_roundtrip_title, goal.distanceKm)
                    is NavigationGoal.Adventure -> stringResource(R.string.preview_adventure_title, goal.name)
                    is NavigationGoal.Track -> goal.name
                },
                color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (goal is NavigationGoal.Roundtrip || goal is NavigationGoal.Adventure || (goal as? NavigationGoal.Destination)?.loop != null || (goal as? NavigationGoal.Destination)?.adventure != null) SmallIcon(Icons.Default.Shuffle, stringResource(R.string.preview_another_loop), onShuffle)
            if (goal is NavigationGoal.Track) SmallIcon(Icons.Default.SwapHoriz, stringResource(R.string.preview_reverse), onReverse)
            if (goal is NavigationGoal.Track) SmallIcon(Icons.Default.EditRoad, stringResource(R.string.preview_track_to_trip), onTrackToTrip)
            if (preview.selectedRoute != null) SmallIcon(Icons.Default.BookmarkBorder, stringResource(R.string.preview_save), onSave)
            SmallIcon(Icons.Default.ExpandMore, stringResource(R.string.collapse), minimize)
            // A trip on the map may be half an hour of planning: ask before throwing it away.
            SmallIcon(Icons.Default.Close, stringResource(R.string.cancel)) { if (preview.selectedRoute != null) askDiscard = true else onClose() }
        }
        if (askDiscard) DiscardTripDialog(
            onDiscard = { askDiscard = false; onClose() },
            onSave = { askDiscard = false; onSave() },
            onKeep = { askDiscard = false }
        )

        // Everything between the title and GO scrolls when the sheet runs out of height (a phone
        // held sideways with a gravel warning), so the options and GO always stay on screen.
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // An adventure goes on being worked on after the first trip is shown: the ones found so far to switch
            // between (the others grey on the map), and a small sign of work while it keeps looking.
            // The first trip is marked as such ("Trip 1") while it is being improved on, even before there is a second.
            if (preview.suggestions.size >= 2 || (preview.improving && preview.suggestions.isNotEmpty())) Row(
                Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                preview.suggestions.forEachIndexed { i, r ->
                    val on = i == preview.suggestion
                    PlanChip(
                        Icons.Default.Explore,
                        stringResource(R.string.adventure_suggestion, i + 1, adventureTimeLabel((r.durationSeconds / 60).toInt()), (r.distanceMeters / 1000).toInt()),
                        if (on) NeonAmber else TextSecondary, "preview_suggestion_$i", { onSelectSuggestion(i) }, outlined = !on
                    )
                }
            }
            if (preview.improving) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = NeonAmber, strokeWidth = 2.dp)
                val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "improving").animateFloat(
                    initialValue = 0.5f, targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "improvingAlpha"
                )
                Text(
                    stringResource(R.string.adventure_improving), color = NeonAmber, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer { alpha = pulse }.testTag("adventure_improving")
                )
            }
            // A loop: where it starts (here, or elsewhere to plan one away from home).
            if (goal is NavigationGoal.Roundtrip) PlanChip(
                Icons.Default.TripOrigin,
                goal.start?.let { stringResource(R.string.from_place, it.name) } ?: stringResource(R.string.from_here),
                if (goal.start != null) ElectricCyan else TextSecondary, "preview_start_chip", onEditStart
            )
            // An adventure: how long, where from, and drawing the area again.
            if (goal is NavigationGoal.Adventure) Row(
                Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdventureTimeChip(goal.minutes, onAdventureMinutes)
                PlanChip(Icons.Default.Gesture, stringResource(R.string.loop_redraw), NeonAmber, "preview_redraw", onRedraw, outlined = true)
                PlanChip(
                    Icons.Default.TripOrigin,
                    goal.start?.let { stringResource(R.string.from_place, it.name) } ?: stringResource(R.string.from_here),
                    if (goal.start != null) ElectricCyan else TextSecondary, "preview_start_chip", onEditStart
                )
            }
            // A→B: where from (the planner), "+ Stop", and the stops with how long to each.
            if (goal is NavigationGoal.Destination) {
                val route = preview.selectedRoute
                val legs = remember(route, goal.stops) { route?.let { stopTimes(goal.stops, it) } }
                var stopMenu by remember { mutableStateOf<Int?>(null) }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // An adventure: how long it takes, and drawing it again, first.
                    goal.adventure?.let { adventure ->
                        AdventureTimeChip(adventure.minutes, onAdventureMinutes)
                        PlanChip(Icons.Default.Gesture, stringResource(R.string.loop_redraw), NeonAmber, "preview_redraw", onRedraw, outlined = true)
                    }
                    // A drawn loop: how long it may be, and drawing it again, first.
                    goal.loop?.takeIf { it.area != null }?.let { loop ->
                        LoopMaxChip(loop.maxKm, onLoopMax)
                        PlanChip(Icons.Default.Gesture, stringResource(R.string.loop_redraw), NeonAmber, "preview_redraw", onRedraw, outlined = true)
                    }
                    PlanChip(
                        Icons.Default.TripOrigin,
                        goal.start?.let { stringResource(R.string.from_place, it.name) } ?: stringResource(R.string.from_here),
                        if (goal.start != null) ElectricCyan else TextSecondary, "preview_start_chip", onEditStart
                    )
                    // Not the quickest way there: a longer ride through an area drawn on the map.
                    if (goal.loop == null && goal.adventure == null && goal.fixedLine == null && goal.stops.isEmpty()) {
                        PlanChip(Icons.Default.Explore, stringResource(R.string.adventure_chip), NeonAmber, "preview_adventure", onAdventure, outlined = true)
                    }
                    // "+ Stop" next to the start, so it stays in sight however many stops the trip has.
                    // Changing the route: a stop from the search, or on the map (points, dragging the line).
                    Box {
                        var adjust by remember { mutableStateOf(false) }
                        PlanChip(Icons.Default.Tune, stringResource(R.string.adjust_route), NeonAmber, "preview_adjust_route", { adjust = true }, outlined = true)
                        DropdownMenu(adjust, onDismissRequest = { adjust = false }, modifier = Modifier.background(CockpitSurface)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_stop), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Default.AddLocationAlt, null, tint = NeonAmber) },
                                onClick = { adjust = false; onAddStop() }, modifier = Modifier.testTag("preview_add_stop")
                            )
                            if (preview.selectedRoute != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit_on_map), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Default.Gesture, null, tint = ElectricCyan) },
                                onClick = { adjust = false; onEditOnMap() }, modifier = Modifier.testTag("preview_edit_on_map")
                            )
                        }
                    }
                    var number = 0
                    goal.stops.forEachIndexed { i, s ->
                        val leg = legs?.getOrNull(i)?.let { sec -> " · %d:%02d".format((sec / 3600).toInt(), ((sec % 3600) / 60).toInt()) } ?: ""
                        // Points the route only runs through (GPX, or dragged on the map) are not stops: no chip.
                        if (!s.via) PlanChip(null, "${++number}. ${s.name}$leg", NeonAmber, "preview_stop_$i", onClick = { stopMenu = i })
                    }
                }
                if (goal.start != null) Text(stringResource(R.string.preview_planned_hint), color = TextMuted, fontSize = 12.sp, maxLines = 1)
                stopMenu?.let { i ->
                    StopMenuDialog(
                        name = goal.stops.getOrNull(i)?.name ?: "",
                        canMoveEarlier = i > 0, canMoveLater = i < goal.stops.lastIndex,
                        onMove = { d -> stopMenu = null; onMoveStop(i, d) },
                        onRemove = { stopMenu = null; onRemoveStop(i) },
                        onDismiss = { stopMenu = null },
                        onChangePlace = { stopMenu = null; onChangeStop(i) },
                        onAddBefore = { stopMenu = null; onAddStopBefore(i) }
                    )
                }
            }

            // The alternatives
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.candidates.forEachIndexed { i, c ->
                    CandidateCard(c, selected = i == preview.selected, single = preview.candidates.size == 1,
                        showArrival = (goal as? NavigationGoal.Destination)?.start == null, modifier = Modifier.weight(1f)) { onSelect(i) }
                }
            }

            // Ferries switched off but the route still has one: say so plainly, and why.
            val ferries = preview.selectedRoute?.ferryCount ?: 0
            if (options.avoidFerries && ferries > 0) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NeonAmber.copy(alpha = 0.16f))
                        .border(1.dp, NeonAmber, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("preview_ferry_warning"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DirectionsBoat, null, tint = NeonAmber, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        pluralStringResource(R.plurals.preview_includes_ferries, ferries, ferries) + " " + when (goal) {
                            is NavigationGoal.Roundtrip -> stringResource(R.string.preview_ferry_loop)
                            else -> stringResource(R.string.preview_ferry_road)
                        },
                        color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tarmac asked for but the route still has gravel: the router only takes it when there's no
            // tarmac road there short of a huge detour, and a cruiser can't ride it, so say so loudly.
            preview.selectedRoute?.let { route -> if (options.surface == SurfacePreference.PAVED) ForcedGravelWarning(route) }
            preview.fetching?.let { Text(it, color = ElectricCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }

        // Options + GO; upright on a phone GO gets a row of its own, full width.
        val chips: @Composable (Modifier) -> Unit = { m ->
            RouteOptionChips(
                surface = options.surface, avoidFerries = options.avoidFerries, avoidMotorways = options.avoidMotorways,
                onSurface = onSurface, onToggleFerries = onToggleFerries, onMotorways = onMotorways, avoidTrunk = options.avoidTrunk, modifier = m,
                gravelReachKm = options.gravelReachKm, onGravelReach = onGravelReach
            )
        }
        if (isNarrowScreen()) {
            chips(Modifier.horizontalScroll(rememberScrollState()))
            GoButton(ready = preview.selectedRoute != null, onGo = onGo, modifier = Modifier.fillMaxWidth())
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips(Modifier.weight(1f).horizontalScroll(rememberScrollState()))
            GoButton(ready = preview.selectedRoute != null, onGo = onGo)
        }
    }
}

/** Less than this much gravel on a tarmac route is noise (a bit of yard or an untagged track crossing). */
private const val FORCED_GRAVEL_MIN_METERS = 100.0

private fun com.curveon.routing.Route.forcedGravel() = unpavedMeters >= FORCED_GRAVEL_MIN_METERS

@Composable
private fun ForcedGravelWarning(route: com.curveon.routing.Route) {
    if (!route.forcedGravel()) return
    val first = route.points.zipWithNext().firstOrNull { it.second.unpaved }?.first?.distanceFromStart ?: 0.0
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NeonAmber.copy(alpha = 0.22f))
            .border(2.dp, NeonAmber, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("preview_gravel_warning"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, null, tint = NeonAmber, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(
                R.string.preview_forced_gravel,
                shortDistance(route.unpavedMeters),
                if (first < 500) stringResource(R.string.preview_gravel_from_start) else stringResource(R.string.preview_gravel_after, shortDistance(first))
            ),
            color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
    }
}

/** 300 m, 2.4 km, 35 km. */
@Composable
private fun shortDistance(meters: Double): String = when {
    meters < 1000 -> stringResource(R.string.distance_m_int, (Math.round(meters / 50) * 50).toInt().coerceAtLeast(50))
    meters < 10_000 -> stringResource(R.string.distance_km, meters / 1000)
    else -> stringResource(R.string.distance_km_whole, meters / 1000)
}

/** The sheet folded down: where to and the chosen route's time and distance; tap it to open again. */
@Composable
private fun MinimizedPreview(preview: RoutePreview, onRestore: () -> Unit, onGo: () -> Unit, modifier: Modifier) {
    val goal = preview.goal
    val route = preview.selectedRoute
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
    Row(
        modifier
            .graphicsLayer {
                scaleX = 0.6f + 0.4f * grow.value; scaleY = 0.6f + 0.4f * grow.value; alpha = grow.value.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(22.dp))
            .background(CockpitSurface.copy(alpha = 0.97f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(22.dp))
            .clickable(onClick = onRestore)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("route_preview_minimized"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ExpandLess, stringResource(R.string.expand), tint = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (goal) {
                    is NavigationGoal.Destination -> when {
                        goal.adventure != null -> stringResource(R.string.preview_adventure_title, goal.name)
                        goal.loop?.area != null -> stringResource(R.string.preview_area_roundtrip_title)
                        goal.loop != null -> stringResource(R.string.preview_loop_from, goal.name)
                        else -> goal.name
                    }
                    is NavigationGoal.Roundtrip -> if (goal.area != null) stringResource(R.string.preview_area_roundtrip_title)
                        else stringResource(R.string.preview_roundtrip_title, goal.distanceKm)
                    is NavigationGoal.Adventure -> stringResource(R.string.preview_adventure_title, goal.name)
                    is NavigationGoal.Track -> goal.name
                },
                color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (route != null) {
                val min = (route.durationSeconds / 60).toInt()
                val gravel = goal.options.surface == SurfacePreference.PAVED && route.forcedGravel()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%d:%02d · ".format(min / 60, min % 60) + stringResource(R.string.distance_km_whole, route.distanceMeters / 1000),
                        color = TextSecondary, fontSize = 13.sp, maxLines = 1
                    )
                    if (gravel) {
                        Icon(Icons.Default.Warning, null, tint = NeonAmber, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                        Text(" " + stringResource(R.string.preview_gravel_amount, shortDistance(route.unpavedMeters)), color = NeonAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        GoButton(ready = route != null, onGo = onGo)
    }
}

/** Starts the ride on the chosen route: arrow and "GO" when there's room, just the arrow when squeezed. */
@Composable
private fun GoButton(ready: Boolean, onGo: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.preview_go)
    val labelStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Black)
    val labelWidth = rememberTextMeasurer().measure(label, labelStyle, maxLines = 1).size.width
    val tint = if (ready) OnCyan else TextMuted
    BoxWithConstraints(
        modifier.height(56.dp).widthIn(min = 96.dp).clip(RoundedCornerShape(28.dp))
            .background(if (ready) ElectricCyan else CockpitSurfaceVariant)
            .clickable(enabled = ready, onClick = onGo).padding(horizontal = 22.dp).testTag("preview_go_btn"),
        contentAlignment = Alignment.Center
    ) {
        val fits = constraints.maxWidth >= labelWidth + with(LocalDensity.current) { (24 + 8).dp.roundToPx() }
        if (fits) Row(Modifier.widthIn(min = 96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Navigation, null, tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(label, color = tint, style = labelStyle, maxLines = 1)
        } else Icon(Icons.Default.Navigation, label, tint = tint, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun CandidateCard(c: RouteCandidate, selected: Boolean, single: Boolean, showArrival: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val route = c.route
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) ElectricCyan.copy(alpha = 0.14f) else CockpitSurfaceVariant)
            .border(if (selected) 2.dp else 1.dp, if (selected) ElectricCyan else CockpitBorder, RoundedCornerShape(16.dp))
            .clickable(enabled = route != null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (!single) Text(
            when (c.style) { RoadStyle.FASTEST -> stringResource(R.string.style_fastest); RoadStyle.CURVY -> stringResource(R.string.style_curvy); RoadStyle.VERY_CURVY -> stringResource(R.string.style_super_curvy); null -> "" },
            color = if (selected) ElectricCyan else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
        when {
            route != null -> {
                val min = (route.durationSeconds / 60).toInt()
                Text("%d:%02d".format(min / 60, min % 60), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(stringResource(R.string.distance_km_whole, route.distanceMeters / 1000), color = TextSecondary, fontSize = 13.sp)
                if (showArrival) {
                    val arriveAt = route.points.last()
                    // Leaving now: when you get there, and whether it will be dark by then.
                    val arrival = System.currentTimeMillis() + (route.durationSeconds * 1000).toLong()
                    val dark = !com.curveon.routing.Sun.isDay(arriveAt.lat, arriveAt.lon, arrival)
                    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(arrival))
                    Text(
                        stringResource(R.string.preview_arrive, clock) + if (dark) " · " + stringResource(R.string.preview_after_dark) else "",
                        color = if (dark) NeonAmber else TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                CurvinessBar(route.curviness)
                val gravel = stringResource(R.string.preview_km_gravel, route.unpavedMeters / 1000)
                val ferries = pluralStringResource(R.plurals.preview_ferries, route.ferryCount, route.ferryCount)
                val notes = buildList {
                    if (route.unpavedMeters > 1000) add(gravel)
                    if (route.ferryCount > 0) add(ferries)
                }
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = NeonAmber, fontSize = 11.sp, maxLines = 1)
            }
            c.error != null -> Text(c.error, color = WarningRed, fontSize = 12.sp, maxLines = 3)
            else -> Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ElectricCyan, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Five little segments: how twisty the route is. */
@Composable
private fun CurvinessBar(curviness: Int) {
    val filled = ((curviness + 10) / 20).coerceIn(1, 5)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            Box(Modifier.size(width = 14.dp, height = 5.dp).clip(RoundedCornerShape(3.dp))
                .background(if (i < filled) NeonAmber else CockpitBorder))
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.Gesture, stringResource(R.string.preview_curviness), tint = TextMuted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = TextSecondary) }
}

/** Seconds from the start of [route] to each stop (stops in order along it). */
private fun stopTimes(stops: List<com.curveon.data.routing.Stop>, route: com.curveon.routing.Route): List<Double> =
    com.curveon.data.routing.alongDistances(stops, route).map { com.curveon.routing.Routes.secondsAt(route, it) }

@Composable
private fun PlanChip(icon: ImageVector?, text: String, color: Color, tag: String, onClick: () -> Unit, outlined: Boolean = false) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (outlined) Color.Transparent else color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = if (outlined) 1f else 0.4f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * A previewed stop: move it earlier or later in the trip, pick another place for it (a wrong tap
 * on the map), put a new stop before it, or drop it.
 */
@Composable
fun StopMenuDialog(
    name: String, canMoveEarlier: Boolean, canMoveLater: Boolean, onMove: (Int) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
    onChangePlace: (() -> Unit)? = null, onAddBefore: (() -> Unit)? = null,
    /** "Remove point" for a point the route was dragged or tapped through, the start or the destination. */
    removeLabel: String = stringResource(R.string.preview_remove_stop)
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(CockpitSurface).border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp).widthIn(max = 760.dp).testTag("stop_menu"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canMoveEarlier) PillButton(Icons.Default.ArrowBack, stringResource(R.string.move_earlier), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f)) { onMove(-1) }
                if (canMoveLater) PillButton(Icons.Default.ArrowForward, stringResource(R.string.move_later), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f)) { onMove(1) }
                PillButton(Icons.Default.Close, removeLabel, WarningRed.copy(alpha = 0.25f), WarningRed, Modifier.weight(1f).testTag("stop_menu_remove"), onRemove)
            }
            if (onChangePlace != null || onAddBefore != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onChangePlace != null) PillButton(Icons.Default.EditLocationAlt, stringResource(R.string.change_place), CockpitSurfaceVariant, TextPrimary,
                    Modifier.weight(1f).testTag("stop_menu_change"), onChangePlace)
                if (onAddBefore != null) PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop_before), NeonAmber.copy(alpha = 0.2f), NeonAmber,
                    Modifier.weight(1f).testTag("stop_menu_add_before"), onAddBefore)
            }
        }
    }
}

/** Closing a planned trip: throw it away, save it first, or carry on with it. */
@Composable
private fun DiscardTripDialog(onDiscard: () -> Unit, onSave: () -> Unit, onKeep: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onKeep,
        containerColor = CockpitSurface,
        title = { HideSystemBars(); Text(stringResource(R.string.preview_discard_title), color = TextPrimary, fontWeight = FontWeight.Black) },
        text = { Text(stringResource(R.string.preview_discard_text), color = TextSecondary) },
        confirmButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onSave, modifier = Modifier.testTag("discard_trip_save")) {
                    Text(stringResource(R.string.save), color = ElectricCyan, fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.TextButton(onClick = onDiscard, modifier = Modifier.testTag("discard_trip_confirm")) {
                    Text(stringResource(R.string.preview_discard), color = WarningRed, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onKeep, modifier = Modifier.testTag("discard_trip_keep")) {
                Text(stringResource(R.string.preview_keep), color = TextSecondary)
            }
        }
    )
}
