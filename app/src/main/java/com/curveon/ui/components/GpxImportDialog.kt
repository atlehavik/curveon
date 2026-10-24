@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import com.curveon.R
import com.curveon.data.gpx.GpxGroups
import com.curveon.data.gpx.GpxOverlay
import com.curveon.data.gpx.GpxWriter
import com.curveon.data.gpx.Mix
import com.curveon.data.model.GpxGroup
import com.curveon.data.gpx.PoiCategory
import com.curveon.data.model.GpxTrack
import com.curveon.ui.theme.*

/** "Import": pick a GPX file to add. */
@Composable
fun GpxImportButton(onClick: () -> Unit) {
    // Upright on a phone only the icon: the title next to it needs the room.
    if (isNarrowScreen()) return Box(Modifier.testTag("gpx_import_btn")) {
        RoundButton(Icons.Default.FileOpen, stringResource(R.string.gpx_import), 48.dp, ElectricCyan, Color(0xFF00363D), onClick)
    }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
        modifier = Modifier.height(48.dp).testTag("gpx_import_btn")
    ) {
        Icon(Icons.Default.FileOpen, null, tint = Color(0xFF00363D))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.gpx_import), color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
    }
}

/**
 * GPX manager: import tracks, sort them into groups (like layer groups), show them on the map in
 * a chosen colour and direction, show their waypoints, export, and ride one. Tapping a track row
 * shows/hides it; tapping a group row opens or collapses it. Everything else is at the row end.
 */
@Composable
fun GpxManagerDialog(
    tracks: List<GpxTrack>,
    groups: List<GpxGroup>,
    message: String?,
    poiCategories: Set<PoiCategory>,
    autoFollow: Boolean,
    actions: GpxActions,
    onTogglePoiCategory: (PoiCategory) -> Unit,
    onAutoFollow: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Page menu when shown inside the GPX & rides dialog. */
    sideBar: (@Composable () -> Unit)? = null,
    /** Only the page, for a frame that is somebody else's (the GPX / Rides / Planned screen). */
    embedded: Boolean = false
) {
    var confirmDelete by remember { mutableStateOf<GpxTrack?>(null) }
    var confirmDeleteGroup by remember { mutableStateOf<GpxGroup?>(null) }
    var renaming by remember { mutableStateOf<Any?>(null) }
    var moving by remember { mutableStateOf<GpxTrack?>(null) }
    /** "t<id>" or "g<id>": whose colour palette is open. */
    var colorFor by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val poiCounts = remember(tracks) { tracks.associate { it.id to GpxOverlay.poiCount(it) } }
    val groupById = remember(groups) { groups.associateBy { it.id } }
    val byGroup = remember(tracks, groups) { tracks.filter { it.groupId in groupById }.groupBy { it.groupId!! } }

    var exporting by remember { mutableStateOf<Pair<GpxGroup?, GpxTrack?>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        exporting?.let { (g, t) -> if (uri != null) actions.onExport(g, t, uri) }
        exporting = null
    }
    fun export(g: GpxGroup?, t: GpxTrack?) {
        exporting = g to t
        exportLauncher.launch(GpxWriter.fileName(t?.name ?: g!!.name))
    }

    // Groups and loose tracks side by side, by name; a group's tracks follow it when it is open.
    val rows = remember(tracks, groups, expanded) {
        val top = groups.map { it.name to (it as Any) } + tracks.filter { it.groupId !in groupById }.map { it.name to (it as Any) }
        top.sortedBy { it.first.lowercase() }.flatMap { (_, item) ->
            if (item is GpxGroup) listOf(item) + (if (item.id in expanded) byGroup[item.id].orEmpty() else emptyList())
            else listOf(item)
        }
    }

    val importButton: @Composable RowScope.() -> Unit = { GpxImportButton(actions.onImport) }
    val body: @Composable ColumnScope.() -> Unit = {
      // Everything scrolls together, so a short screen (a phone held sideways) still has room for the tracks.
      LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message != null) item { Text(message, color = NeonAmber, fontSize = 13.sp) }

        // Settings that apply to all tracks
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.gpx_auto_follow), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.gpx_auto_follow_hint), color = TextMuted, fontSize = 12.sp)
            }
            CockpitSwitch(checked = autoFollow, onCheckedChange = onAutoFollow)
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PoiCategory.entries.forEach { c ->
                val on = c in poiCategories
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (on) Color(c.color).copy(alpha = 0.22f) else CockpitSurfaceVariant)
                        .border(1.dp, if (on) Color(c.color) else CockpitBorder, RoundedCornerShape(16.dp))
                        .clickable { onTogglePoiCategory(c) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color)))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(c.label), color = if (on) TextPrimary else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } }

        if (tracks.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.gpx_empty),
                        color = TextMuted, fontSize = 14.sp
                    )
                }
            }
        } else {
            run {
                items(rows, key = { if (it is GpxGroup) "g${it.id}" else "t${(it as GpxTrack).id}" }) { row ->
                    if (row is GpxGroup) {
                        val members = byGroup[row.id].orEmpty()
                        val open = row.id in expanded
                        GroupRow(
                            g = row, tracks = members, open = open, paletteOpen = colorFor == "g${row.id}",
                            onOpen = { expanded = if (open) expanded - row.id else expanded + row.id },
                            onToggleVisible = { actions.onGroupVisibility(row) },
                            onColorClick = { colorFor = if (colorFor == "g${row.id}") null else "g${row.id}" },
                            onColor = { actions.onGroupColor(row, it); colorFor = null },
                            onDirection = { actions.onGroupDirection(row) },
                            menu = listOf(
                                MenuItem(Icons.Default.Edit, stringResource(R.string.rename)) { renaming = row },
                                MenuItem(Icons.Default.IosShare, stringResource(R.string.gpx_export)) { export(row, null) },
                                MenuItem(Icons.Default.FolderOff, stringResource(R.string.gpx_ungroup)) { actions.onDeleteGroup(row, true) },
                                MenuItem(Icons.Default.DeleteOutline, stringResource(R.string.gpx_delete_group)) { confirmDeleteGroup = row }
                            )
                        )
                    } else {
                        val t = row as GpxTrack
                        val g = t.groupId?.let(groupById::get)
                        TrackRow(
                            t = t, group = g, pois = poiCounts[t.id] ?: 0, paletteOpen = colorFor == "t${t.id}",
                            onColorClick = { colorFor = if (colorFor == "t${t.id}") null else "t${t.id}" },
                            onColor = { actions.onColor(t, it); colorFor = null },
                            actions = actions,
                            menu = listOfNotNull(
                                MenuItem(Icons.Default.Edit, stringResource(R.string.rename)) { renaming = t },
                                MenuItem(Icons.Default.IosShare, stringResource(R.string.gpx_export)) { export(null, t) },
                                actions.onSendToTablet?.let { send -> MenuItem(Icons.Default.TabletAndroid, stringResource(R.string.send_to_tablet)) { send(t) } },
                                actions.onShareWithGroup?.let { share -> MenuItem(Icons.Default.Groups, stringResource(R.string.share_with_group)) { share(t) } },
                                actions.onToPlanned?.let { add -> MenuItem(Icons.Default.EventNote, stringResource(R.string.add_to_planned)) { add(t) } },
                                MenuItem(Icons.Default.DriveFileMove, stringResource(R.string.gpx_move_to_group)) { moving = t },
                                MenuItem(Icons.Default.DeleteOutline, stringResource(R.string.delete)) { confirmDelete = t }
                            )
                        )
                    }
                }
            }
        }
      }
    }
    // Embedded, the frame's header holds the import button.
    if (embedded) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
    else CockpitDialog(
        title = "GPX", icon = Icons.Default.Route, accent = ElectricCyan, onDismiss = onDismiss, tag = "gpx_manager", sideBar = sideBar,
        actions = importButton, content = body
    )

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { HideSystemBars(); Text(stringResource(R.string.delete_named, t.name)) },
            confirmButton = { TextButton(onClick = { actions.onDelete(t); confirmDelete = null }) { Text(stringResource(R.string.delete), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    confirmDeleteGroup?.let { g ->
        AlertDialog(
            onDismissRequest = { confirmDeleteGroup = null },
            title = { HideSystemBars(); Text(stringResource(R.string.delete_named, g.name)) },
            text = { Text(byGroup[g.id].orEmpty().size.let { pluralStringResource(R.plurals.gpx_delete_group_text, it, it) }) },
            confirmButton = { TextButton(onClick = { actions.onDeleteGroup(g, false); confirmDeleteGroup = null }) { Text(stringResource(R.string.delete), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDeleteGroup = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    when (val r = renaming) {
        is GpxTrack -> RenameDialog(stringResource(R.string.gpx_rename_track), r.name, { actions.onRenameTrack(r, it) }, { renaming = null })
        is GpxGroup -> RenameDialog(stringResource(R.string.gpx_rename_group), r.name, { actions.onRenameGroup(r, it) }, { renaming = null })
    }
    moving?.let { t ->
        GroupPickerDialog(stringResource(R.string.gpx_move_to, t.name), groups, t.groupId, { id, name ->
            actions.onMove(t, id, name)
            // Show where it went.
            if (id != null) expanded = expanded + id
        }, { moving = null })
    }
}

/** What the GPX manager can do; implemented by the view model. */
class GpxActions(
    val onImport: () -> Unit,
    val onToggleOnMap: (GpxTrack, Boolean) -> Unit,
    val onColor: (GpxTrack, Int) -> Unit,
    val onReverse: (GpxTrack, Boolean) -> Unit,
    val onTogglePois: (GpxTrack, Boolean) -> Unit,
    val onRide: (GpxTrack) -> Unit,
    /** The track made into an ordinary trip to adjust. */
    val onToRoute: (GpxTrack) -> Unit,
    val onDelete: (GpxTrack) -> Unit,
    val onRenameTrack: (GpxTrack, String) -> Unit,
    val onMove: (GpxTrack, Long?, String?) -> Unit,
    val onGroupVisibility: (GpxGroup) -> Unit,
    val onGroupDirection: (GpxGroup) -> Unit,
    /** null: tracks back to their own colours. */
    val onGroupColor: (GpxGroup, Int?) -> Unit,
    val onRenameGroup: (GpxGroup, String) -> Unit,
    /** true: ungroup (keep the tracks). */
    val onDeleteGroup: (GpxGroup, Boolean) -> Unit,
    /** A group (track null) or one track, to the picked file. */
    val onExport: (GpxGroup?, GpxTrack?, android.net.Uri) -> Unit,
    /** Send the track to the tablet through Curveon Companion (null: no companion on this phone). */
    val onSendToTablet: ((GpxTrack) -> Unit)? = null,
    /** Share the track with the group ride on now. */
    val onShareWithGroup: ((GpxTrack) -> Unit)? = null,
    /** Keep the track among the planned trips too (and so on the web planner). */
    val onToPlanned: ((GpxTrack) -> Unit)? = null
)

internal class MenuItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
private fun GroupRow(
    g: GpxGroup,
    tracks: List<GpxTrack>,
    open: Boolean,
    paletteOpen: Boolean,
    onOpen: () -> Unit,
    onToggleVisible: () -> Unit,
    onColorClick: () -> Unit,
    onColor: (Int?) -> Unit,
    onDirection: () -> Unit,
    menu: List<MenuItem>
) {
    val visible = GpxGroups.mix(tracks.map { GpxGroups.visible(it, g) })
    val colors = tracks.map { GpxGroups.color(it, g) }
    val accent = Color(if (g.sameColor) GpxGroups.groupColor(g) else colors.firstOrNull() ?: GpxGroups.groupColor(g))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (visible != Mix.NONE) accent.copy(alpha = 0.08f) else CockpitSurfaceVariant)
            .border(1.dp, if (visible != Mix.NONE) accent.copy(alpha = 0.5f) else CockpitBorder, RoundedCornerShape(14.dp))
            .testTag("gpx_group")
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(if (open) R.string.collapse else R.string.expand), tint = TextSecondary)
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onToggleVisible), contentAlignment = Alignment.Center) {
                VisibilityIcon(visible, accent)
            }
            Spacer(Modifier.width(4.dp))
            Icon(if (open) Icons.Default.FolderOpen else Icons.Default.Folder, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.gpx_tracks_count, tracks.size, tracks.size) + " · " + stringResource(R.string.distance_km_whole, tracks.sumOf { it.totalDistanceKm.toDouble() }),
                    color = TextSecondary, fontSize = 12.sp, maxLines = 1
                )
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onColorClick), contentAlignment = Alignment.Center) { ColorSwatch(colors) }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDirection), contentAlignment = Alignment.Center) {
                DirectionIcon(GpxGroups.mix(tracks.map { GpxGroups.reversed(it, g) }))
            }
            RowMenu(menu)
        }
        if (paletteOpen) Palette(selected = if (g.sameColor) GpxGroups.groupColor(g) else null, onColor = onColor, ownColors = true)
    }
}

@Composable
private fun TrackRow(
    t: GpxTrack,
    group: GpxGroup?,
    pois: Int,
    paletteOpen: Boolean,
    onColorClick: () -> Unit,
    onColor: (Int) -> Unit,
    actions: GpxActions,
    menu: List<MenuItem>
) {
    val shown = GpxGroups.visible(t, group)
    val reversed = GpxGroups.reversed(t, group)
    val colorInt = GpxGroups.color(t, group)
    val color = Color(colorInt)
    Column(
        Modifier.fillMaxWidth().padding(start = if (group != null) 28.dp else 0.dp).clip(RoundedCornerShape(14.dp))
            .background(if (shown) color.copy(alpha = 0.10f) else CockpitSurfaceVariant)
            .border(1.dp, if (shown) color.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { actions.onToggleOnMap(t, !shown) }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VisibilityIcon(if (shown) Mix.ALL else Mix.NONE, color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        stringResource(R.string.distance_km_whole, t.totalDistanceKm),
                        t.elevationGainMeters.takeIf { it > 0 }?.let { "↑ $it m" },
                        pois.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.gpx_waypoints_count, it, it) }
                    ).joinToString(" · "),
                    color = TextSecondary, fontSize = 12.sp, maxLines = 1
                )
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onColorClick), contentAlignment = Alignment.Center) {
                ColorSwatch(listOf(colorInt))
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { actions.onReverse(t, !reversed) }, contentAlignment = Alignment.Center) {
                DirectionIcon(if (reversed) Mix.ALL else Mix.NONE)
            }
            if (pois > 0) RowIcon(Icons.Default.Place, stringResource(R.string.gpx_waypoints), if (t.showPois) NeonAmber else TextMuted) { actions.onTogglePois(t, !t.showPois) }
            RowMenu(menu)
            var rideMenu by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier.padding(start = 4.dp).size(44.dp).clip(CircleShape).background(ElectricCyan).clickable { rideMenu = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Navigation, stringResource(R.string.ride), tint = Color(0xFF00363D)) }
                DropdownMenu(expanded = rideMenu, onDismissRequest = { rideMenu = false }, containerColor = CockpitSurface) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.gpx_ride_from_here), color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Navigation, null, tint = TextSecondary) },
                        onClick = { rideMenu = false; actions.onRide(t) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.gpx_to_route), color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.EditRoad, null, tint = TextSecondary) },
                        onClick = { rideMenu = false; actions.onToRoute(t) }
                    )
                }
            }
        }
        if (paletteOpen) Palette(selected = colorInt, onColor = { if (it != null) onColor(it) })
    }
}

/**
 * The colour choices; with [ownColors] (a group) the first choice is "each track its own colour",
 * always drawn in many colours so it reads as that even while the group is all one colour.
 */
@Composable
private fun Palette(selected: Int?, onColor: (Int?) -> Unit, ownColors: Boolean = false) {
    Row(
        Modifier.padding(start = 50.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        if (ownColors) {
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .border(if (selected == null) 3.dp else 1.dp, Color.White, CircleShape).clickable { onColor(null) },
                contentAlignment = Alignment.Center
            ) { ColorSwatch(GpxOverlay.PALETTE, 24.dp) }
        }
        GpxOverlay.PALETTE.forEach { c ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, Color.White, CircleShape)
                    .clickable { onColor(c) }
            )
        }
    }
}

@Composable
internal fun RowMenu(items: List<MenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        RowIcon(Icons.Default.MoreVert, stringResource(R.string.more), TextSecondary) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = CockpitSurface) {
            items.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label, color = if (m.icon == Icons.Default.DeleteOutline) WarningRed else TextPrimary) },
                    leadingIcon = { Icon(m.icon, null, tint = TextSecondary) },
                    onClick = { open = false; m.onClick() }
                )
            }
        }
    }
}

@Composable
internal fun RowIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint)
    }
}
