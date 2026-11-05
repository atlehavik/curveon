@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.curveon.ui.theme.*

/**
 * A picked place while riding or planning: a stop on the way, the destination, or (planning) where
 * the trip starts. Big buttons, nothing else.
 */
@Composable
fun PlaceActionDialog(
    name: String,
    /** The road there (held on the map): shown under the name; null while looked up, or none. */
    road: com.curveon.data.routing.RoadInfo? = null,
    /** The rider rides in mph: the road's speed limit sign in mph too. */
    useMph: Boolean = false,
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
            road?.let { RoadInfoRow(it, useMph) }
            tracks.forEach { (track, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("place_track")) {
                    Icon(Icons.Default.Route, null, tint = runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(TextSecondary), modifier = Modifier.size(22.dp))
                    Text(track, color = TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            // Wraps onto a second line rather than running off the edge where a phone is narrow or the text large.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onAddStop != null) PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop), NeonAmber, Color.Black,
                    Modifier.testTag("add_stop_btn"), onAddStop)
                PillButton(Icons.Default.Flag, stringResource(R.string.go_here), ElectricCyan, OnCyanDark,
                    Modifier.testTag("go_here_btn"), onGoHere)
                if (onPointByPoint != null) Box {
                    var asking by remember { mutableStateOf(false) }
                    PillButton(Icons.Default.Timeline, stringResource(R.string.plan_point_by_point), CockpitSurfaceVariant, ElectricCyan,
                        Modifier.testTag("point_by_point_btn")) { asking = true }
                    androidx.compose.material3.DropdownMenu(expanded = asking, onDismissRequest = { asking = false }, modifier = Modifier.remotePopup(), containerColor = CockpitSurface) {
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

internal val OnCyanDark = Color(0xFF00363D)

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
    /** Share the trip with the active group (null: no group, or this is not a trip with stops, or it is shared already). */
    onShare: (() -> Unit)? = null,
    /** Shared with the group already: says so where the share button would be. */
    shared: Boolean = false,
    /** A trip that could be shared but there is no group yet: says so, and the button goes to Group ride. */
    onFindGroup: (() -> Unit)? = null,
    /** Send a copy to some friends (null: a track, which can't be): with it, Share offers the group or friends. */
    onShareFriends: (() -> Unit)? = null,
    /** Open the trip in the full planner to change it (null: a track, which can't be). */
    onEdit: (() -> Unit)? = null,
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
            if (onFindGroup != null && onShareFriends == null) Text(stringResource(R.string.share_trip_needs_group), color = TextMuted, fontSize = 14.sp)
            val buttons = listOfNotNull<@Composable (Modifier) -> Unit>(
                { m -> PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop), NeonAmber, Color.Black, m.testTag("menu_add_stop"), onAddStop) },
                { m -> PillButton(Icons.Default.BookmarkBorder, stringResource(R.string.preview_save), CockpitSurfaceVariant, TextPrimary, m.testTag("menu_save"), onSave) },
                when {
                    onShareFriends != null -> { m -> ShareChoice(m, shared, onShare, onFindGroup, onShareFriends) }
                    shared -> { m -> PillButton(Icons.Default.Check, stringResource(R.string.share_trip_done), EcoGreen.copy(alpha = 0.15f), EcoGreen, m.testTag("menu_share")) {} }
                    onShare != null -> { m -> PillButton(Icons.Default.Share, stringResource(R.string.share_trip_short), CockpitSurfaceVariant, TextPrimary, m.testTag("menu_share"), onShare) }
                    onFindGroup != null -> { m -> PillButton(Icons.Default.Share, stringResource(R.string.share_trip_short), CockpitSurfaceVariant, TextSecondary, m.testTag("menu_share"), onFindGroup) }
                    else -> null
                },
                { m -> PillButton(Icons.Default.Close, stringResource(R.string.nav_end), WarningRed.copy(alpha = 0.25f), WarningRed, m.testTag("menu_end"), onEnd) },
                onEdit?.let { edit -> { m: Modifier -> PillButton(Icons.Default.EditRoad, stringResource(R.string.route_edit), ElectricCyan.copy(alpha = 0.18f), ElectricCyan, m.testTag("menu_edit"), edit) } }
            )
            // Two to a row on a phone, so every label fits; three when there are five, so none gets squeezed.
            buttons.chunked(if (isNarrowScreen()) 2 else if (buttons.size > 4) 3 else 4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { it(Modifier.weight(1f)) } }
            }
        }
    }
}

/** Share in the route menu: a choice of the group (shared already, share, or start one first) or some friends. */
@Composable
private fun ShareChoice(modifier: Modifier, shared: Boolean, onGroup: (() -> Unit)?, onFindGroup: (() -> Unit)?, onFriends: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        PillButton(Icons.Default.Share, stringResource(R.string.share_trip_short), CockpitSurfaceVariant, TextPrimary, Modifier.fillMaxWidth().testTag("menu_share")) { open = true }
        DropdownMenu(open, { open = false }, modifier = Modifier.remotePopup(), containerColor = CockpitSurfaceVariant) {
            when {
                shared -> DropdownMenuItem({ Text(stringResource(R.string.share_trip_done_group), color = EcoGreen) }, {}, enabled = false,
                    leadingIcon = { Icon(Icons.Default.Check, null, tint = EcoGreen) })
                onGroup != null -> DropdownMenuItem({ Text(stringResource(R.string.share_trip_with_group), color = TextPrimary) }, { open = false; onGroup() },
                    leadingIcon = { Icon(Icons.Default.Groups, null, tint = TextSecondary) }, modifier = Modifier.testTag("menu_share_group"))
                onFindGroup != null -> DropdownMenuItem({ Text(stringResource(R.string.share_trip_start_group), color = TextSecondary) }, { open = false; onFindGroup() },
                    leadingIcon = { Icon(Icons.Default.Groups, null, tint = TextMuted) }, modifier = Modifier.testTag("menu_share_group"))
            }
            DropdownMenuItem({ Text(stringResource(R.string.share_trip_with_friends), color = TextPrimary) }, { open = false; onFriends() },
                leadingIcon = { Icon(Icons.Default.People, null, tint = TextSecondary) }, modifier = Modifier.testTag("menu_share_friends"))
        }
    }
}
