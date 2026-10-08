package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
    onToggleMotorways: () -> Unit,
    onRemoveStop: (Int) -> Unit,
    onShuffle: () -> Unit,
    onAddStop: () -> Unit = {},
    onMoveStop: (index: Int, delta: Int) -> Unit = { _, _ -> },
    onEditStart: () -> Unit = {},
    onReverse: () -> Unit = {},
    /** Pick another place for stop i (search or map). */
    onChangeStop: (Int) -> Unit = {},
    /** Put a new stop just before stop i. */
    onAddStopBefore: (Int) -> Unit = {},
    /** Edit the trip by dragging it on the map. */
    onEditOnMap: () -> Unit = {},
    onSave: () -> Unit,
    onGo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val goal = preview.goal
    val options = goal.options
    Column(
        modifier = modifier
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
                    is NavigationGoal.Destination -> goal.name
                    is NavigationGoal.Roundtrip -> stringResource(R.string.preview_roundtrip_title, goal.distanceKm)
                    is NavigationGoal.Track -> goal.name
                },
                color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (goal is NavigationGoal.Roundtrip) SmallIcon(Icons.Default.Shuffle, stringResource(R.string.preview_another_loop), onShuffle)
            if (goal is NavigationGoal.Track) SmallIcon(Icons.Default.SwapHoriz, stringResource(R.string.preview_reverse), onReverse)
            if (goal is NavigationGoal.Destination && preview.selectedRoute != null) SmallIcon(Icons.Default.Gesture, stringResource(R.string.edit_on_map), onEditOnMap)
            if (preview.selectedRoute != null) SmallIcon(Icons.Default.BookmarkBorder, stringResource(R.string.preview_save), onSave)
            SmallIcon(Icons.Default.Close, stringResource(R.string.cancel), onClose)
        }

        // A→B: where from (the planner), the stops with how long to each, and "+ Stop".
        if (goal is NavigationGoal.Destination) {
            val route = preview.selectedRoute
            val legs = remember(route, goal.stops) { route?.let { stopTimes(goal.stops, it) } }
            var stopMenu by remember { mutableStateOf<Int?>(null) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlanChip(
                    Icons.Default.TripOrigin,
                    goal.start?.let { stringResource(R.string.from_place, it.name) } ?: stringResource(R.string.from_here),
                    if (goal.start != null) ElectricCyan else TextSecondary, "preview_start_chip", onEditStart
                )
                var number = 0
                goal.stops.forEachIndexed { i, s ->
                    val leg = legs?.getOrNull(i)?.let { sec -> " · %d:%02d".format((sec / 3600).toInt(), ((sec % 3600) / 60).toInt()) } ?: ""
                    // Points the route was dragged through: a quieter chip, no number.
                    if (s.via) PlanChip(Icons.Default.Gesture, s.name, TextSecondary, "preview_stop_$i", onClick = { stopMenu = i })
                    else PlanChip(null, "${++number}. ${s.name}$leg", NeonAmber, "preview_stop_$i", onClick = { stopMenu = i })
                }
                PlanChip(Icons.Default.AddLocationAlt, stringResource(R.string.stop_short), NeonAmber, "preview_add_stop", onAddStop, outlined = true)
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

        // Options + GO
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteOptionChips(
                surface = options.surface, avoidFerries = options.avoidFerries, avoidMotorways = options.avoidMotorways,
                onSurface = onSurface, onToggleFerries = onToggleFerries, onToggleMotorways = onToggleMotorways
            )
            Spacer(Modifier.weight(1f))
            val ready = preview.selectedRoute != null
            Row(
                Modifier.height(56.dp).widthIn(min = 140.dp).clip(RoundedCornerShape(28.dp))
                    .background(if (ready) ElectricCyan else CockpitSurfaceVariant)
                    .clickable(enabled = ready, onClick = onGo).padding(horizontal = 22.dp).testTag("preview_go_btn"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Navigation, null, tint = if (ready) OnCyan else TextMuted)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.preview_go), color = if (ready) OnCyan else TextMuted, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
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
    onChangePlace: (() -> Unit)? = null, onAddBefore: (() -> Unit)? = null
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
                PillButton(Icons.Default.Close, stringResource(R.string.preview_remove_stop), WarningRed.copy(alpha = 0.25f), WarningRed, Modifier.weight(1f).testTag("stop_menu_remove"), onRemove)
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
