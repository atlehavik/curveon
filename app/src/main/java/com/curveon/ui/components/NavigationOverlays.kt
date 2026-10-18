package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.curveon.data.routing.RoutingStatus
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.NavigationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arrival time, distance left and the end button, at the top of the map beside the speed. Tapping it
 * folds out a second line: the time left, next stop (opens the route menu: stops, save), fuel
 * when worth knowing (tapping it adds it as a stop) and add stop.
 */
@Composable
fun NavigationSummaryBar(
    summary: NavigationSummary,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** The height of the speed card it sits next to: the folded bar is as high as that. */
    foldedHeight: androidx.compose.ui.unit.Dp = 0.dp,
    fuel: com.curveon.data.routing.FuelHint? = null,
    onFuel: () -> Unit = {},
    onAddStop: () -> Unit = {},
    onOpen: () -> Unit = {}
) {
    val eta = remember(summary.arrivalTimeMillis / 60_000) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(summary.arrivalTimeMillis))
    }
    // Folded: arrival, distance left and the end button, small enough to sit beside the speed.
    // A tap opens a second line below with the next stop, fuel and add stop.
    var open by remember { mutableStateOf(false) }
    // Next to a wide speed card (over 100 km/h, or a phone held upright) there is little room: the
    // numbers and buttons shrink, and the folded out line wraps instead of running over.
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CockpitSurface.copy(alpha = 0.95f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(16.dp))
            .clickable { open = !open }
            .testTag("nav_summary_bar")
    ) {
        val narrow = maxWidth < 200.dp
        val button = if (narrow) 36.dp else 44.dp
        Column {
            Row(
                Modifier.heightIn(min = foldedHeight).padding(start = if (narrow) 10.dp else 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 12.dp)
            ) {
                // The end button keeps its place; the distance gives way if it must.
                Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 12.dp)) {
                    Text(eta, color = TextPrimary, fontSize = if (narrow) 18.sp else 22.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                    Text(stringResource(R.string.distance_km_whole, summary.remainingKm), color = TextSecondary, fontSize = if (narrow) 14.sp else 16.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(button).clip(CircleShape).background(WarningRed.copy(alpha = 0.2f)).testTag("nav_stop_btn")
                ) {
                    Icon(Icons.Default.Close, stringResource(R.string.nav_stop), tint = WarningRed)
                }
            }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            if (open) FlowRow(
                Modifier.padding(start = if (narrow) 10.dp else 14.dp, end = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 10.dp)
            ) {
                // The time left: only shown when asked for (the distance is always on show).
                Text(
                    "%d:%02d".format(summary.remainingMinutes / 60, summary.remainingMinutes % 60),
                    color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.padding(vertical = 8.dp).testTag("nav_remaining")
                )
                // The next stop (or just "Route") opens the route's menu: stops, save, end.
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(vertical = 4.dp)
                        .testTag("nav_route_menu_btn"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val next = summary.nextStop
                    Text(
                        if (next != null) next.first.take(14) + " " + stringResource(R.string.distance_km_whole, next.second) else stringResource(R.string.nav_route),
                        color = if (next != null) NeonAmber else TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
                if (fuel != null && (fuel.isLongGap || fuel.distanceMeters < 60_000)) {
                    val color = if (fuel.isLongGap) NeonAmber else EcoGreen
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f))
                            .clickable(enabled = fuel.point.lat != 0.0, onClick = onFuel).padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("fuel_chip"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocalGasStation, fuel.name.ifEmpty { stringResource(R.string.fuel_none_on_route) }, tint = color, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (fuel.point.lat == 0.0) stringResource(R.string.fuel_none_ahead) else stringResource(R.string.distance_km_whole, fuel.distanceMeters / 1000),
                            color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(
                    onClick = onAddStop,
                    modifier = Modifier.size(button).clip(CircleShape).background(NeonAmber.copy(alpha = 0.2f)).testTag("nav_add_stop_btn")
                ) {
                    Icon(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop), tint = NeonAmber)
                }
            }
        }
    }
}

/** A stop on the way is reached: its name, big, for a few seconds (tap to put it away). */
@Composable
fun StopReachedBanner(name: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.clip(shape).background(CockpitSurface).border(2.dp, NeonAmber, shape).clickable(onClick = onDismiss)
            .padding(horizontal = 18.dp, vertical = 12.dp).testTag("stop_reached"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Flag, null, tint = NeonAmber, modifier = Modifier.size(34.dp))
        Column {
            Text(stringResource(R.string.stop_reached), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
            Text(name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** "Calculating…" spinner or an error message with a dismiss button. */
@Composable
fun RoutingStatusCard(status: RoutingStatus, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    when (status) {
        RoutingStatus.Idle -> Unit
        is RoutingStatus.Calculating -> Row(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(CockpitSurface.copy(alpha = 0.95f))
                .border(1.dp, ElectricCyan, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(color = ElectricCyan, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(status.message, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        is RoutingStatus.Failed -> Row(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(CockpitSurface.copy(alpha = 0.95f))
                .border(1.dp, WarningRed, RoundedCornerShape(14.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = WarningRed)
            Spacer(Modifier.width(12.dp))
            Text(status.message, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Close, stringResource(R.string.dismiss), tint = TextSecondary)
        }
    }
}

/**
 * A picked place while riding or planning: a stop on the way, the destination, or (planning) where
 * the trip starts. Big buttons, nothing else.
 */
@Composable
fun PlaceActionDialog(
    name: String,
    /** The road there (held on the map): shown under the name; null while looked up, or none. */
    road: com.curveon.data.routing.RoadInfo? = null,
    /** GPX tracks drawn where the finger was held: name and colour ("#RRGGBB"). */
    tracks: List<Pair<String, String>> = emptyList(),
    /** Null when there is no trip to add it to. */
    onAddStop: (() -> Unit)?,
    onGoHere: () -> Unit,
    onStartHere: (() -> Unit)?,
    /**
     * Not riding or planning: build a trip point by point on the map; null otherwise. [fromHere]:
     * this place is the first point, ridden to from where the rider is; else the trip starts here
     * (planning somewhere far away without routing all the way there first).
     */
    onPointByPoint: ((fromHere: Boolean) -> Unit)? = null,
    /** Keep the place as a favourite: as Home, or under the name given. */
    onFavorite: (name: String, home: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) return FavoriteDialog(name, onSave = onFavorite, onDismiss = onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .widthIn(max = 760.dp)
                .testTag("place_action_dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2)
            road?.let { RoadInfoRow(it) }
            tracks.forEach { (track, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("place_track")) {
                    Icon(Icons.Default.Route, null, tint = runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(TextSecondary), modifier = Modifier.size(22.dp))
                    Text(track, color = TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onAddStop != null) PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop), NeonAmber, Color.Black,
                    Modifier.testTag("add_stop_btn"), onAddStop)
                PillButton(Icons.Default.Flag, stringResource(R.string.go_here), ElectricCyan, OnCyanDark,
                    Modifier.testTag("go_here_btn"), onGoHere)
                if (onPointByPoint != null) Box {
                    var asking by remember { mutableStateOf(false) }
                    PillButton(Icons.Default.Timeline, stringResource(R.string.plan_point_by_point), CockpitSurfaceVariant, ElectricCyan,
                        Modifier.testTag("point_by_point_btn")) { asking = true }
                    androidx.compose.material3.DropdownMenu(expanded = asking, onDismissRequest = { asking = false }, containerColor = CockpitSurface) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Column {
                                Text(stringResource(R.string.point_by_point_start_here), color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.point_by_point_start_here_hint), color = TextSecondary, fontSize = 13.sp)
                            } },
                            leadingIcon = { Icon(Icons.Default.TripOrigin, null, tint = ElectricCyan) },
                            onClick = { asking = false; onPointByPoint(false) },
                            modifier = Modifier.testTag("point_by_point_start_here")
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Column {
                                Text(stringResource(R.string.point_by_point_from_me), color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.point_by_point_from_me_hint), color = TextSecondary, fontSize = 13.sp)
                            } },
                            leadingIcon = { Icon(Icons.Default.MyLocation, null, tint = TextSecondary) },
                            onClick = { asking = false; onPointByPoint(true) },
                            modifier = Modifier.testTag("point_by_point_from_me")
                        )
                    }
                }
                if (onStartHere != null) PillButton(Icons.Default.TripOrigin, stringResource(R.string.start_here), CockpitSurfaceVariant, TextPrimary,
                    Modifier.testTag("start_here_btn"), onStartHere)
                PillButton(Icons.Default.StarBorder, stringResource(R.string.save_favorite), CockpitSurfaceVariant, NeonAmber,
                    Modifier.testTag("favorite_btn")) { naming = true }
            }
        }
    }
}

/** Save a place as a favourite: Home with one tap, or under a name (the place's, to change). */
@Composable
private fun FavoriteDialog(placeName: String, onSave: (name: String, home: Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(placeName) }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .widthIn(max = 640.dp)
                .testTag("favorite_dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.save_favorite), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            PillButton(Icons.Default.Home, stringResource(R.string.favorite_home), NeonAmber, Color.Black,
                Modifier.fillMaxWidth().testTag("favorite_home_btn")) { onSave(placeName, true) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.favorite_name)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricCyan, unfocusedBorderColor = CockpitBorder, cursorColor = ElectricCyan
                    ),
                    modifier = Modifier.weight(1f).testTag("favorite_name_field")
                )
                PillButton(Icons.Default.Star, stringResource(R.string.save), ElectricCyan, OnCyanDark,
                    Modifier.testTag("favorite_save_btn")) { if (name.isNotBlank()) onSave(name.trim(), false) }
            }
        }
    }
}

private val OnCyanDark = Color(0xFF00363D)

/**
 * Where a route is saved: the planned trips (in [plannedGroup] or a new group [newPlannedGroup]) and/or the
 * GPX collection (in [gpxGroup] or [newGpxGroup]). Nothing is ticked to begin with, so a stray tap saves nothing.
 */
data class RouteSaveTargets(
    val toWhereTo: Boolean = false,
    val toPlanned: Boolean = false, val plannedGroup: Long? = null, val newPlannedGroup: String? = null,
    val toGpx: Boolean = false, val gpxGroup: Long? = null, val newGpxGroup: String? = null
)

/**
 * Name a route (or a ride) before saving it; [initialName] is a suggestion. It can go to the planned
 * trips and/or the GPX collection, each in a group of its own; [initial] only gives the groups to
 * start from (the last ones used), never what is ticked.
 */
@Composable
fun SaveRouteDialog(
    initialName: String,
    initial: RouteSaveTargets,
    plannedGroups: List<com.curveon.data.model.GpxGroup>,
    gpxGroups: List<com.curveon.data.model.GpxGroup>,
    onSave: (String, RouteSaveTargets) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.save_route_title)
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    // A remembered group that has been deleted since: no group.
    var targets by remember {
        mutableStateOf(RouteSaveTargets(
            plannedGroup = initial.plannedGroup?.takeIf { id -> plannedGroups.any { it.id == id } },
            gpxGroup = initial.gpxGroup?.takeIf { id -> gpxGroups.any { it.id == id } }
        ))
    }
    var pickingPlanned by remember { mutableStateOf(false) }
    var pickingGpx by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CockpitSurface,
        title = { HideSystemBars(); Text(title, color = TextPrimary, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.save_route_name)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricCyan, unfocusedBorderColor = CockpitBorder, cursorColor = ElectricCyan
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("save_route_name")
                )
                SaveTargetRow(Icons.Default.Search, stringResource(R.string.save_route_to_where_to), null, targets.toWhereTo,
                    Modifier.testTag("save_route_to_where_to")) { targets = targets.copy(toWhereTo = !targets.toWhereTo) }
                val plannedName = targets.newPlannedGroup ?: plannedGroups.firstOrNull { it.id == targets.plannedGroup }?.name ?: stringResource(R.string.gpx_no_group)
                SaveTargetRow(Icons.Default.EventNote, stringResource(R.string.save_route_to_planned), plannedName.takeIf { targets.toPlanned }, targets.toPlanned,
                    Modifier.testTag("save_route_to_planned"), onDetail = { pickingPlanned = true }) { targets = targets.copy(toPlanned = !targets.toPlanned) }
                val gpxName = targets.newGpxGroup ?: gpxGroups.firstOrNull { it.id == targets.gpxGroup }?.name ?: stringResource(R.string.gpx_no_group)
                SaveTargetRow(Icons.Default.Route, stringResource(R.string.save_route_to_gpx), gpxName.takeIf { targets.toGpx }, targets.toGpx,
                    Modifier.testTag("save_route_to_gpx"), onDetail = { pickingGpx = true }) { targets = targets.copy(toGpx = !targets.toGpx) }
            }
        },
        confirmButton = {
            val can = name.isNotBlank() && (targets.toWhereTo || targets.toPlanned || targets.toGpx)
            TextButton(onClick = { onSave(name.trim(), targets) }, enabled = can, modifier = Modifier.testTag("save_route_confirm")) {
                Text(stringResource(R.string.save), color = if (can) ElectricCyan else TextMuted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = TextSecondary) } }
    )
    if (pickingPlanned) {
        GroupPickerDialog(stringResource(R.string.save_route_planned_group), plannedGroups, current = targets.plannedGroup.takeIf { targets.newPlannedGroup == null },
            onPick = { id, newName -> targets = targets.copy(plannedGroup = id, newPlannedGroup = newName) }, onDismiss = { pickingPlanned = false })
    }
    if (pickingGpx) {
        GroupPickerDialog(stringResource(R.string.rides_save_to_group), gpxGroups, current = targets.gpxGroup.takeIf { targets.newGpxGroup == null },
            onPick = { id, newName -> targets = targets.copy(gpxGroup = id, newGpxGroup = newName) }, onDismiss = { pickingGpx = false })
    }
}

/** One place to save to, ticked or not; [detail] (the GPX group) opens its own choice with [onDetail]. */
@Composable
private fun SaveTargetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String?,
    on: Boolean,
    modifier: Modifier = Modifier,
    onDetail: () -> Unit = {},
    onToggle: () -> Unit
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (on) ElectricCyan.copy(alpha = 0.12f) else CockpitSurfaceVariant)
            .clickable(onClick = onToggle).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (on) ElectricCyan else TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (detail != null) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onDetail).padding(horizontal = 8.dp, vertical = 8.dp)
                    .testTag("save_route_gpx_group"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.FolderOpen, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(detail, color = ElectricCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            }
        }
        Checkbox(checked = on, onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = ElectricCyan, checkmarkColor = OnCyanDark, uncheckedColor = TextMuted))
    }
}

/** The active route: its stops (remove one), add a stop, save it, end navigation. */
@Composable
fun RouteMenuDialog(
    title: String,
    stops: List<Pair<String, Double?>>,
    /** Null: the stops are part of a fixed line and can't be taken out one by one. */
    onRemoveStop: ((Int) -> Unit)?,
    onAddStop: () -> Unit,
    onSave: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .widthIn(max = 760.dp)
                .testTag("route_menu_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title.ifEmpty { stringResource(R.string.route_menu_title) }, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
            if (stops.isEmpty()) Text(stringResource(R.string.route_stops_none), color = TextMuted, fontSize = 14.sp)
            stops.forEachIndexed { i, (name, km) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NeonAmber.copy(alpha = 0.12f))
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${i + 1}. $name", color = NeonAmber, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                    if (km != null) Text(stringResource(R.string.distance_km_whole, km), color = TextSecondary, fontSize = 14.sp)
                    if (onRemoveStop != null) IconButton(onClick = { onRemoveStop(i) }) { Icon(Icons.Default.Close, stringResource(R.string.preview_remove_stop), tint = NeonAmber) }
                    else Spacer(Modifier.width(10.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop), NeonAmber, Color.Black, Modifier.weight(1f).testTag("menu_add_stop"), onAddStop)
                PillButton(Icons.Default.BookmarkBorder, stringResource(R.string.preview_save), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f).testTag("menu_save"), onSave)
                PillButton(Icons.Default.Close, stringResource(R.string.nav_end), WarningRed.copy(alpha = 0.25f), WarningRed, Modifier.weight(1f).testTag("menu_end"), onEnd)
            }
        }
    }
}

/**
 * Off the route with no quick way back. Two big buttons; the "carry on" one counts down and is
 * chosen by itself if the rider keeps riding. On a trip with stops left, carrying on asks once
 * more whether to keep them.
 */
@Composable
fun WrongTurnCard(
    wrongTurn: com.curveon.data.routing.WrongTurn,
    onTurnBack: () -> Unit,
    onCarryOn: (keepStops: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var askStops by remember(wrongTurn) { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(wrongTurn) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val secondsLeft = ((wrongTurn.autoAtMillis - now + 999) / 1000).coerceAtLeast(0).toInt()
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CockpitSurface.copy(alpha = 0.97f))
            .border(2.dp, NeonAmber, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .widthIn(max = 600.dp)
            .testTag("wrong_turn_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WrongLocation, null, tint = NeonAmber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(R.string.wrong_turn_title), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    if (askStops) androidx.compose.ui.res.pluralStringResource(R.plurals.wrong_turn_stops_left, wrongTurn.stopsLeft, wrongTurn.stopsLeft)
                    else wrongTurn.aheadExtraMinutes?.let { stringResource(R.string.wrong_turn_trip, it) } ?: stringResource(R.string.wrong_turn_no_ahead),
                    color = TextSecondary, fontSize = 14.sp
                )
            }
        }
        // Counts down while riding on; at 0 it waits for the rider to ride on (or answer).
        val carryOn = stringResource(if (wrongTurn.isTrip) R.string.wrong_turn_new_route else R.string.wrong_turn_rejoin_ahead) +
            if (secondsLeft > 0) " · " + stringResource(R.string.wrong_turn_auto, secondsLeft) else ""
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (askStops) {
                PillButton(Icons.Default.Place, stringResource(R.string.wrong_turn_keep_stops), ElectricCyan, OnCyanDark,
                    Modifier.weight(1f).testTag("wrong_turn_keep_stops")) { onCarryOn(true) }
                PillButton(Icons.Default.SkipNext, stringResource(R.string.wrong_turn_skip_stops), CockpitSurfaceVariant, TextPrimary,
                    Modifier.weight(1f).testTag("wrong_turn_skip_stops")) { onCarryOn(false) }
            } else {
                PillButton(Icons.Default.UTurnLeft, stringResource(R.string.wrong_turn_back), NeonAmber, Color.Black,
                    Modifier.weight(1f).testTag("wrong_turn_back"), onTurnBack)
                // Not offered on a loop/track when there is no way ahead: carrying on means nothing then.
                if (wrongTurn.isTrip || wrongTurn.aheadExtraMinutes != null) PillButton(
                    Icons.Default.Navigation, carryOn, ElectricCyan, OnCyanDark, Modifier.weight(1.4f).testTag("wrong_turn_carry_on")
                ) { if (wrongTurn.isTrip && wrongTurn.stopsLeft > 0) askStops = true else onCarryOn(true) }
            }
        }
    }
}

/**
 * What the search dialog shrinks into for "Pick on map": a small bar at the bottom of the map.
 * Tap the map to drop a pin (tap again to move it); then the bar names the place and offers to
 * use it. The search icon grows the dialog back; ✕ gives up.
 *
 * Point by point (a destination or stops while planning): each tap on the map rides the trip on
 * to there straight away, so a trip is built A → B → C → D without confirming each point; the bar
 * shows the trip so far, Undo takes the last point back and the tick finishes.
 */
@Composable
fun MapPickBar(
    purpose: SearchPurpose,
    picked: String?,
    onConfirm: () -> Unit,
    onBackToSearch: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Point by point instead of one place (not for the start, a stop while riding, or one put before/instead of another). */
    pointByPoint: Boolean = false,
    /** Point by point: the trip so far ("0:40 · 38 km"), and whether its route is being found. */
    tripSummary: String? = null,
    busy: Boolean = false,
    canUndo: Boolean = false,
    onUndo: () -> Unit = {},
    /** Point by point from "+ Stop" on a planned trip: the first point tapped, asking stop on the way or new end. */
    asking: String? = null,
    onAlongTheWay: () -> Unit = {},
    onNewEnd: () -> Unit = {},
    /** The first point tapped becomes a via point (a white dot to shape the route), not a stop. */
    onShapingPoint: () -> Unit = {},
    /** Give up the tapped point (a mistaken tap). */
    onDismissAsk: () -> Unit = {},
    /** Why the trip's route couldn't be found, instead of the hint. */
    error: String? = null,
    /** Marking a part of the route to change (on a long trip, so the rest stays as it is); null = no trip to mark on yet. */
    marking: Boolean? = null,
    onMark: () -> Unit = {},
    /** The route locked against dragging (panning only); null = no trip to lock yet. */
    locked: Boolean? = null,
    onLock: () -> Unit = {}
) {
    // Grows out of the shrinking dialog (bottom centre).
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f)) }
    val chaining = pointByPoint
    val accent = if (purpose == SearchPurpose.STOP && !chaining) NeonAmber else ElectricCyan
    Row(
        modifier
            .graphicsLayer {
                scaleX = 0.6f + 0.4f * grow.value; scaleY = 0.6f + 0.4f * grow.value; alpha = grow.value.coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(30.dp)).background(CockpitSurface.copy(alpha = 0.97f))
            .border(2.dp, if (error != null && chaining) WarningRed else accent, RoundedCornerShape(30.dp))
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("map_pick_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!(chaining && asking != null)) Box(Modifier.testTag("map_pick_search")) {
            RoundButton(Icons.Default.Search, stringResource(R.string.back_to_search), 48.dp, CockpitSurfaceVariant, accent, onBackToSearch)
        }
        if (chaining && asking != null) {
            // Two rows so it fits a phone: the tapped place with ✕ above, the three answers below.
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.testTag("map_pick_dismiss")) {
                        RoundButton(Icons.Default.Close, stringResource(R.string.cancel), 40.dp, CockpitSurfaceVariant, TextPrimary, onDismissAsk)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(asking, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(stringResource(R.string.pick_ask_stop_or_end), color = TextSecondary, fontSize = 13.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton(Icons.Default.RadioButtonUnchecked, stringResource(R.string.pick_shaping_point), Color.White, Color.Black,
                        Modifier.testTag("map_pick_via"), onShapingPoint)
                    PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.pick_along_the_way), NeonAmber, Color.Black,
                        Modifier.testTag("map_pick_along"), onAlongTheWay)
                    PillButton(Icons.Default.Flag, stringResource(R.string.pick_new_end), ElectricCyan, OnCyanDark,
                        Modifier.testTag("map_pick_new_end"), onNewEnd)
                }
            }
        } else if (chaining) {
            Row(Modifier.weight(1f, fill = false).widthIn(max = 340.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(color = ElectricCyan, strokeWidth = 3.dp, modifier = Modifier.padding(end = 10.dp).size(20.dp))
                Column {
                    Text(
                        error ?: stringResource(when {
                            marking == true -> R.string.edit_route_mark_hint
                            locked == true -> R.string.edit_route_locked_hint
                            else -> R.string.pick_hint_next_point
                        }),
                        color = if (error != null) WarningRed else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (tripSummary != null && error == null) Text(tripSummary, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
            }
            if (marking != null) Box(Modifier.testTag("route_edit_mark")) {
                RoundButton(Icons.Default.LinearScale, stringResource(R.string.edit_route_mark), 48.dp,
                    if (marking) ElectricCyan else CockpitSurfaceVariant, if (marking) OnCyanDark else TextPrimary, onMark)
            }
            if (locked != null && marking != true) Box(Modifier.testTag("route_edit_lock")) {
                RoundButton(if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    stringResource(if (locked) R.string.edit_route_unlock else R.string.edit_route_lock), 48.dp,
                    if (locked) NeonAmber else CockpitSurfaceVariant, if (locked) Color.Black else TextPrimary, onLock)
            }
            Box(Modifier.testTag("map_pick_undo").graphicsLayer { alpha = if (canUndo) 1f else 0.35f }) {
                RoundButton(Icons.Default.Undo, stringResource(R.string.undo), 48.dp, CockpitSurfaceVariant, TextPrimary) { if (canUndo) onUndo() }
            }
            Box(Modifier.testTag("map_pick_done")) {
                RoundButton(Icons.Default.Check, stringResource(R.string.done), 56.dp, ElectricCyan, OnCyanDark, onCancel)
            }
        } else {
            Text(
                picked ?: stringResource(when (purpose) {
                    SearchPurpose.DESTINATION -> R.string.pick_hint_destination
                    SearchPurpose.STOP -> R.string.pick_hint_stop
                    SearchPurpose.START -> R.string.pick_hint_start
                }),
                color = if (picked != null) TextPrimary else TextSecondary,
                fontSize = if (picked != null) 17.sp else 15.sp, fontWeight = if (picked != null) FontWeight.Black else FontWeight.Bold,
                maxLines = if (picked != null) 1 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 340.dp).padding(horizontal = 4.dp)
            )
            if (picked != null) {
                val (icon, label) = when (purpose) {
                    SearchPurpose.DESTINATION -> Icons.Default.Flag to R.string.go_here
                    SearchPurpose.STOP -> Icons.Default.AddLocationAlt to R.string.add_stop
                    SearchPurpose.START -> Icons.Default.TripOrigin to R.string.start_here
                }
                PillButton(icon, stringResource(label), accent, if (purpose == SearchPurpose.STOP) Color.Black else OnCyanDark,
                    Modifier.testTag("map_pick_confirm"), onConfirm)
            }
            Box(Modifier.testTag("map_pick_cancel")) {
                RoundButton(Icons.Default.Close, stringResource(R.string.cancel), 48.dp, CockpitSurfaceVariant, TextPrimary, onCancel)
            }
        }
    }
}


/**
 * Drawing the area for a roundtrip: what to do, about how long the loop round it gets once a
 * finger is drawing ([km]), where it starts if not here, and a way out.
 */
@Composable
fun SketchBar(
    onCancel: () -> Unit, modifier: Modifier = Modifier, km: Int? = null, startName: String? = null,
    /** Chosen before drawing: how long the loop may be, and on what surface. */
    maxKm: Int? = null, onMaxKm: (Int?) -> Unit = {},
    surface: com.curveon.routing.SurfacePreference = com.curveon.routing.SurfacePreference.PAVED,
    onSurface: (com.curveon.routing.SurfacePreference) -> Unit = {},
    /** Gravel/Offroad: how far off the ordinary road for gravel (null = any distance). */
    gravelReachKm: Int? = null,
    onGravelReach: (Int?) -> Unit = {},
    /** Drawing the area for an adventure to a destination: how long it may take, null = no limit (instead of the loop's max length). */
    adventure: Boolean = false,
    adventureMinutes: Int? = null,
    onAdventureMinutes: (Int?) -> Unit = {}
) {
    Column(
        modifier
            .clip(RoundedCornerShape(26.dp)).background(CockpitSurface.copy(alpha = 0.97f))
            .border(2.dp, NeonAmber, RoundedCornerShape(26.dp))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 10.dp)
            .testTag("sketch_bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Gesture, null, tint = NeonAmber)
        Column(Modifier.weight(1f, fill = false).widthIn(max = 420.dp)) {
            Text(
                if (adventure) stringResource(R.string.adventure_draw_hint)
                else if (km != null) stringResource(R.string.roundtrip_draw_km, km) else stringResource(R.string.roundtrip_draw_hint),
                color = TextPrimary, fontSize = if (km != null && !adventure) 20.sp else 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (startName != null) Text(stringResource(R.string.from_place, startName), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
        Box(Modifier.testTag("sketch_cancel")) {
            RoundButton(Icons.Default.Close, stringResource(R.string.cancel), 48.dp, CockpitSurfaceVariant, TextPrimary, onCancel)
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (adventure) AdventureTimeChip(adventureMinutes, onAdventureMinutes) else LoopMaxChip(maxKm, onMaxKm)
        SurfaceChip(surface, onSurface)
        if (surface >= com.curveon.routing.SurfacePreference.GRAVEL) GravelReachChip(gravelReachKm, onGravelReach)
    }
    }
}

/** The road under a held finger: its speed limit sign, name, class and surface. Information only. */
@Composable
fun RoadInfoRow(road: com.curveon.data.routing.RoadInfo) {
    val kind = road.highway?.let { roadKind(it) }
    val surface = roadSurface(road)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("road_info")) {
        road.speedKmh?.let { SpeedLimitSign(it, 44.dp) }
        road.ref?.let { RoadShield(it, 30.dp) }
        if (road.priority) PriorityRoadSign(34.dp)
        Column {
            Text(road.name ?: kind ?: "", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val details = listOfNotNull(kind.takeIf { road.name != null }, surface).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, color = if (road.unpaved) NeonAmber else TextSecondary, fontSize = 14.sp, maxLines = 1)
            if (road.speedKmh != null) Text(
                stringResource(if (road.officialSpeed) R.string.road_speed_official else R.string.road_speed_osm),
                color = TextMuted, fontSize = 12.sp, maxLines = 1
            )
            if (road.needsMap) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("road_needs_map")) {
                Icon(Icons.Default.Download, null, tint = NeonAmber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (road.mapArea != null) stringResource(R.string.road_needs_map_for, road.mapArea) else stringResource(R.string.road_needs_map),
                    color = NeonAmber, fontSize = 13.sp, maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun roadKind(highway: String): String? = when (highway.removeSuffix("_link")) {
    "motorway" -> stringResource(R.string.road_kind_motorway)
    "trunk", "primary" -> stringResource(R.string.road_kind_main)
    "secondary", "tertiary", "unclassified" -> stringResource(R.string.road_kind_country)
    "residential", "living_street" -> stringResource(R.string.road_kind_residential)
    "service" -> stringResource(R.string.road_kind_service)
    "track" -> stringResource(R.string.road_kind_track)
    "path", "footway", "cycleway", "bridleway", "pedestrian" -> stringResource(R.string.road_kind_path)
    else -> null
}

@Composable
private fun roadSurface(road: com.curveon.data.routing.RoadInfo): String? = when (road.surface) {
    "asphalt", "paved", "concrete", "concrete:plates", "paving_stones", "chipseal" -> stringResource(R.string.road_surface_asphalt)
    "sett", "cobblestone", "unhewn_cobblestone" -> stringResource(R.string.road_surface_cobbles)
    "gravel", "fine_gravel", "compacted", "pebblestone", "unpaved" -> stringResource(R.string.road_surface_gravel)
    "dirt", "ground", "earth", "soil", "mud", "sand", "grass", "clay" -> stringResource(R.string.road_surface_dirt)
    null -> when {
        road.highway == null -> null
        road.highway == "track" -> stringResource(R.string.road_surface_unknown_track)
        else -> stringResource(R.string.road_surface_unknown)
    }
    else -> road.surface
}?.let { s -> road.tracktype?.removePrefix("grade")?.let { g -> stringResource(R.string.road_track_grade, s, g) } ?: s }

/**
 * How planning on the map works, in three short lines over the pick bar: shown each time the rider
 * plans point by point, until they say not to show it again.
 */
@Composable
fun PointByPointHelp(onOk: () -> Unit, onNever: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 440.dp).clip(RoundedCornerShape(20.dp)).background(CockpitSurface.copy(alpha = 0.97f))
            .border(1.5.dp, ElectricCyan, RoundedCornerShape(20.dp)).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp)
            .testTag("pbp_help"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.pbp_help_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
        @Composable fun line(icon: androidx.compose.ui.graphics.vector.ImageVector, text: Int) = Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = ElectricCyan, modifier = Modifier.padding(top = 1.dp, end = 10.dp).size(18.dp))
            Text(stringResource(text), color = TextSecondary, fontSize = 14.sp)
        }
        line(Icons.Default.TouchApp, R.string.pbp_help_tap)
        line(Icons.Default.Lock, R.string.pbp_help_lock)
        line(Icons.Default.LinearScale, R.string.pbp_help_mark)
        line(Icons.Default.Check, R.string.pbp_help_done)
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onNever) { Text(stringResource(R.string.pbp_help_never), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = onOk) { Text(stringResource(R.string.ok), color = ElectricCyan, fontSize = 15.sp, fontWeight = FontWeight.Black) }
        }
    }
}
