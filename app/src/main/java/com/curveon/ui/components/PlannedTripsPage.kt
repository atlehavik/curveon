package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
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
import com.curveon.ui.theme.*

/**
 * Planned trips: routes saved from the planner or copied from the ride log, kept in named groups
 * ("Trips for the weekend") like GPX tracks. A trip can be ridden as it is or the other way round.
 */
@Composable
fun ColumnScope.PlannedTripsPage(
    trips: List<RouteItem>,
    groups: List<RouteGroup>,
    /** Plan the trip on the map; true = the other way round. */
    onRide: (RouteItem, Boolean) -> Unit,
    onRename: (RouteItem, String) -> Unit,
    /** (trip, existing group or null, new group name or null). */
    onMove: (RouteItem, Long?, String?) -> Unit,
    onDelete: (RouteItem) -> Unit,
    onRenameGroup: (RouteGroup, String) -> Unit,
    /** (group, keep its trips as loose ones). */
    onDeleteGroup: (RouteGroup, Boolean) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(listOf<Long>()) }
    /** Trips set to be ridden the other way. */
    var reversed by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var renaming by remember { mutableStateOf<Any?>(null) }
    var moving by remember { mutableStateOf<RouteItem?>(null) }
    var confirmDelete by remember { mutableStateOf<RouteItem?>(null) }
    var confirmDeleteGroup by remember { mutableStateOf<RouteGroup?>(null) }
    val byGroup = remember(trips, groups) { trips.filter { t -> groups.any { it.id == t.groupId } }.groupBy { it.groupId!! } }
    val rows = remember(trips, groups, expanded) {
        groups.flatMap { g -> listOf<Any>(g) + (if (g.id in expanded) byGroup[g.id].orEmpty() else emptyList()) } +
            trips.filter { t -> groups.none { it.id == t.groupId } }
    }

    if (trips.isEmpty() && groups.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.planned_empty), color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        }
    } else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(rows, key = { if (it is RouteGroup) "g${it.id}" else "t${(it as RouteItem).id}" }) { row ->
            if (row is RouteGroup) {
                val members = byGroup[row.id].orEmpty()
                val open = row.id in expanded
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant)
                        .border(1.dp, CockpitBorder, RoundedCornerShape(14.dp)).clickable { expanded = if (open) expanded - row.id else expanded + row.id }
                        .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag("planned_group"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(if (open) R.string.collapse else R.string.expand), tint = TextSecondary)
                    Icon(if (open) Icons.Default.FolderOpen else Icons.Default.Folder, null, tint = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp).size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(R.plurals.planned_trips_count, members.size, members.size) + " · " +
                                stringResource(R.string.distance_km_whole, members.sumOf { it.totalDistanceKm.toDouble() }),
                            color = TextSecondary, fontSize = 12.sp, maxLines = 1
                        )
                    }
                    RowMenu(listOf(
                        MenuItem(Icons.Default.Edit, stringResource(R.string.rename)) { renaming = row },
                        MenuItem(Icons.Default.FolderOff, stringResource(R.string.gpx_ungroup)) { onDeleteGroup(row, true) },
                        MenuItem(Icons.Default.DeleteOutline, stringResource(R.string.gpx_delete_group)) { confirmDeleteGroup = row }
                    ))
                }
            } else {
                val t = row as RouteItem
                val inGroup = t.groupId != null
                val other = t.id in reversed
                Row(
                    Modifier.fillMaxWidth().padding(start = if (inGroup) 28.dp else 0.dp).clip(RoundedCornerShape(14.dp))
                        .background(CockpitSurfaceVariant).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("planned_trip"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Route, null, tint = ElectricCyan, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val minutes = t.estimatedMinutes
                        Text(
                            stringResource(R.string.distance_km_whole, t.totalDistanceKm.toDouble()) + " · %d:%02d".format(minutes / 60, minutes % 60),
                            color = TextSecondary, fontSize = 12.sp, maxLines = 1
                        )
                    }
                    // The way it will be ridden: as saved (→) or the other way (← in amber); tap to change.
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { reversed = if (other) reversed - t.id else reversed + t.id }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.TrendingFlat, stringResource(if (other) R.string.planned_reversed else R.string.gpx_as_recorded),
                            tint = if (other) NeonAmber else TextMuted, modifier = if (other) Modifier.rotate(180f) else Modifier)
                    }
                    RowMenu(listOf(
                        MenuItem(Icons.Default.Edit, stringResource(R.string.rename)) { renaming = t },
                        MenuItem(Icons.Default.DriveFileMove, stringResource(R.string.gpx_move_to_group)) { moving = t },
                        MenuItem(Icons.Default.DeleteOutline, stringResource(R.string.delete)) { confirmDelete = t }
                    ))
                    Box(
                        Modifier.padding(start = 4.dp).size(44.dp).clip(CircleShape).background(ElectricCyan).clickable { onRide(t, other) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Navigation, stringResource(R.string.ride), tint = Color(0xFF00363D)) }
                }
            }
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { HideSystemBars(); Text(stringResource(R.string.delete_named, t.title)) },
            confirmButton = { TextButton(onClick = { onDelete(t); confirmDelete = null }) { Text(stringResource(R.string.delete), color = WarningRed) } },
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
}
