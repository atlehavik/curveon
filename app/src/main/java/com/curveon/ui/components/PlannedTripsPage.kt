package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.model.GpxGroup
import com.curveon.data.model.RouteGroup
import com.curveon.data.model.RouteItem
import com.curveon.data.routing.SavedRoutes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.curveon.ui.theme.*

/**
 * Planned trips: routes saved from the planner or copied from the ride log, kept in named groups
 * ("Trips for the weekend") like GPX tracks. A trip can be ridden as it is or the other way round.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.PlannedTripsPage(
    trips: List<RouteItem>,
    groups: List<RouteGroup>,
    /** GPX groups, for "Save to GPX". */
    gpxGroups: List<GpxGroup>,
    /** Plan the trip on the map; true = the other way round. */
    onRide: (RouteItem, Boolean) -> Unit,
    /** Keep the trip's line as a GPX track: (trip, existing group or null, new group name or null). */
    onSaveToGpx: (RouteItem, Long?, String?) -> Unit,
    onRename: (RouteItem, String) -> Unit,
    /** (trip, existing group or null, new group name or null). */
    onMove: (RouteItem, Long?, String?) -> Unit,
    /** Takes trips out of the planned trips. */
    onDelete: (Collection<RouteItem>) -> Unit,
    onRenameGroup: (RouteGroup, String) -> Unit,
    /** (group, keep its trips as loose ones). */
    onDeleteGroup: (RouteGroup, Boolean) -> Unit,
    /** Send the trip to the tablet through Curveon Companion (null: no companion on this phone). */
    onSendToTablet: ((RouteItem) -> Unit)? = null,
    /** Share the trip with the group ride on now. */
    onShareWithGroup: ((RouteItem) -> Unit)? = null,
    /** Trips planned on the web planner: how to connect it, and fetching (null: not offered). */
    onFromWeb: (() -> Unit)? = null
) {
    var expandedGroups by rememberSaveable { mutableStateOf(listOf<Long>()) }
    /** The trip whose details are open. */
    var openTrip by rememberSaveable { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<Any?>(null) }
    var moving by remember { mutableStateOf<RouteItem?>(null) }
    var savingToGpx by remember { mutableStateOf<RouteItem?>(null) }
    /** Trips picked for deleting; null when not selecting (long-press a trip, or the select button, as in Rides). */
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var confirmDelete by remember { mutableStateOf<Collection<RouteItem>?>(null) }
    var confirmDeleteGroup by remember { mutableStateOf<RouteGroup?>(null) }
    val byGroup = remember(trips, groups) { trips.filter { t -> groups.any { it.id == t.groupId } }.groupBy { it.groupId!! } }
    val rows = remember(trips, groups, expandedGroups) {
        groups.flatMap { g -> listOf<Any>(g) + (if (g.id in expandedGroups) byGroup[g.id].orEmpty() else emptyList()) } +
            trips.filter { t -> groups.none { it.id == t.groupId } }
    }

    if (trips.isEmpty() && groups.isEmpty()) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.planned_empty), color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
            onFromWeb?.let { PillButton(Icons.Default.Public, stringResource(R.string.web_plan_button), CockpitSurfaceVariant, TextPrimary, Modifier.testTag("planned_from_web"), onClick = it) }
        }
    } else {
        // Fixed row on a phone held upright, the frame's title bar on a wide screen: never scrolls away.
        val pickedNow = selection
        if (trips.isNotEmpty() || onFromWeb != null) PageToolbar(pickedNow?.let { pluralStringResource(R.plurals.rides_selected, it.size, it.size) }) {
            val sel = pickedNow
            if (sel == null) onFromWeb?.let { PillButton(Icons.Default.Public, stringResource(R.string.web_plan_button), CockpitSurfaceVariant, TextPrimary, Modifier.testTag("planned_from_web"), onClick = it) }
            if (sel != null) {
                val all = sel.size == trips.size
                PillButton(if (all) Icons.Default.RemoveDone else Icons.Default.DoneAll,
                    stringResource(if (all) R.string.rides_select_none else R.string.rides_select_all), CockpitSurfaceVariant, TextPrimary) {
                    selection = if (all) emptySet() else trips.map { it.id }.toSet()
                }
                if (sel.isNotEmpty()) RoundButton(Icons.Default.DeleteOutline, stringResource(R.string.delete), 48.dp, WarningRed, TextPrimary) {
                    confirmDelete = trips.filter { it.id in sel }
                }
            }
            if (trips.isNotEmpty()) RoundButton(
                Icons.Default.Checklist, stringResource(R.string.rides_select), 48.dp,
                if (sel != null) ElectricCyan else CockpitSurfaceVariant, if (sel != null) Color(0xFF00363D) else TextPrimary
            ) { selection = if (sel == null) emptySet() else null }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { if (it is RouteGroup) "g${it.id}" else "t${(it as RouteItem).id}" }) { row ->
            if (row is RouteGroup) {
                val members = byGroup[row.id].orEmpty()
                val open = row.id in expandedGroups
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expandedGroups = if (open) expandedGroups - row.id else expandedGroups + row.id }
                        .padding(horizontal = 6.dp, vertical = 8.dp).testTag("planned_group"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(if (open) R.string.collapse else R.string.expand),
                        tint = ElectricCyan, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(if (open) Icons.Default.FolderOpen else Icons.Default.Folder, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(row.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        pluralStringResource(R.plurals.planned_trips_count, members.size, members.size) + " · " +
                            stringResource(R.string.distance_km_whole, members.sumOf { it.totalDistanceKm.toDouble() }),
                        color = TextMuted, fontSize = 12.sp
                    )
                    RowMenu(listOf(
                        MenuItem(Icons.Default.Edit, stringResource(R.string.rename)) { renaming = row },
                        MenuItem(Icons.Default.FolderOff, stringResource(R.string.gpx_ungroup)) { onDeleteGroup(row, true) },
                        MenuItem(Icons.Default.DeleteOutline, stringResource(R.string.gpx_delete_group)) { confirmDeleteGroup = row }
                    ))
                }
            } else {
                val t = row as RouteItem
                val sel = selection
                val picked = sel != null && t.id in sel
                val open = sel == null && openTrip == t.id
                val line = remember(t.id, t.polylineJson.length) { SavedRoutes.decode(t.polylineJson).map { it.lat to it.lon } }
                Column(
                    Modifier.fillMaxWidth().padding(start = if (t.groupId != null) 24.dp else 0.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (picked) ElectricCyan.copy(alpha = 0.15f) else CockpitSurfaceVariant)
                        .border(1.dp, if (picked) ElectricCyan else Color.Transparent, RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = {
                                if (sel != null) selection = if (picked) sel - t.id else sel + t.id
                                else openTrip = if (open) null else t.id
                            },
                            onLongClick = { selection = (sel ?: emptySet()) + t.id }
                        ).padding(14.dp).testTag("planned_trip")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sel != null) {
                            Icon(if (picked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (picked) ElectricCyan else TextMuted, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        RouteThumbnail(line, Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t.timestamp)), color = TextSecondary, fontSize = 12.sp)
                        }
                        Text(stringResource(R.string.distance_km_whole, t.totalDistanceKm.toDouble()), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    if (open) {
                        Spacer(Modifier.height(12.dp))
                        // What a trip not ridden yet can tell: how far, how long it should take, the climb and the curves.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            PlannedStat("%d:%02d".format(t.estimatedMinutes / 60, t.estimatedMinutes % 60), stringResource(R.string.stat_estimated))
                            if (t.elevationGainMeters > 0) PlannedStat("%d".format(t.elevationGainMeters), stringResource(R.string.stat_climb))
                            if (t.curvinessScore > 0) PlannedStat("%d".format(t.curvinessScore), stringResource(R.string.stat_curves))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(Icons.Default.Navigation, stringResource(R.string.ride_now), ElectricCyan, Color(0xFF00363D), Modifier.weight(1f)) { onRide(t, false) }
                            PillButton(Icons.Default.SwapHoriz, stringResource(R.string.rides_ride_reversed), CockpitSurface, TextPrimary, Modifier.weight(1f)) { onRide(t, true) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PillButton(Icons.Default.Route, stringResource(R.string.rides_save_to_gpx), CockpitSurface, TextPrimary, Modifier.weight(1f)) { savingToGpx = t }
                            RowMenu(listOfNotNull(
                                MenuItem(Icons.Default.Edit, stringResource(R.string.rename)) { renaming = t },
                                MenuItem(Icons.Default.DriveFileMove, stringResource(R.string.gpx_move_to_group)) { moving = t },
                                onSendToTablet?.let { send -> MenuItem(Icons.Default.TabletAndroid, stringResource(R.string.send_to_tablet)) { send(t) } },
                                onShareWithGroup?.let { share -> MenuItem(Icons.Default.Groups, stringResource(R.string.share_with_group)) { share(t) } }
                            ))
                            // Taken out of the planned trips at once, as a ride is from the log.
                            RoundButton(Icons.Default.DeleteOutline, stringResource(R.string.delete), 56.dp, CockpitSurface, TextMuted) { onDelete(listOf(t)) }
                        }
                    }
                }
            }
        }
        }
    }

    confirmDelete?.let { picked ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { HideSystemBars(); Text(pluralStringResource(R.plurals.planned_delete_title, picked.size, picked.size)) },
            confirmButton = {
                TextButton(onClick = { onDelete(picked); confirmDelete = null; selection = null }) { Text(stringResource(R.string.delete), color = WarningRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    confirmDeleteGroup?.let { g ->
        AlertDialog(
            onDismissRequest = { confirmDeleteGroup = null },
            title = { HideSystemBars(); Text(stringResource(R.string.delete_named, g.name)) },
            text = { Text(byGroup[g.id].orEmpty().size.let { pluralStringResource(R.plurals.planned_delete_group_text, it, it) }) },
            confirmButton = { TextButton(onClick = { onDeleteGroup(g, false); confirmDeleteGroup = null }) { Text(stringResource(R.string.delete), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDeleteGroup = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    when (val r = renaming) {
        is RouteItem -> RenameDialog(stringResource(R.string.planned_rename), r.title, { onRename(r, it) }, { renaming = null })
        is RouteGroup -> RenameDialog(stringResource(R.string.gpx_rename_group), r.name, { onRenameGroup(r, it) }, { renaming = null })
        else -> Unit
    }
    moving?.let { t ->
        GroupPickerDialog(stringResource(R.string.gpx_move_to_group), groups.map { GpxGroup(id = it.id, name = it.name) }, current = t.groupId,
            onPick = { id, name -> onMove(t, id, name) }, onDismiss = { moving = null })
    }
    savingToGpx?.let { t ->
        GroupPickerDialog(stringResource(R.string.rides_save_to_group), gpxGroups, current = null,
            onPick = { id, name -> onSaveToGpx(t, id, name) }, onDismiss = { savingToGpx = null })
    }
}

@Composable
private fun PlannedStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}
