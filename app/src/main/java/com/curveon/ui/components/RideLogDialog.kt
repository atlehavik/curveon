package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.model.Ride
import com.curveon.data.ride.RideRecorder
import com.curveon.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

/**
 * Ride history: the ride in progress on top, earlier rides below. Tap a ride for its actions;
 * long-press (or the select button) to pick several and delete them at once.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RideLogDialog(
    current: Ride?,
    rides: List<Ride>,
    onRideAgain: (Ride) -> Unit,
    /** The same ride the other way round. */
    onRideReversed: (Ride) -> Unit = {},
    /** Planned-trip groups (as GPX groups, for the group picker). */
    plannedGroups: List<com.curveon.data.model.GpxGroup> = emptyList(),
    gpxGroups: List<com.curveon.data.model.GpxGroup>,
    /** Keep the ride under [name] as a planned trip and/or a GPX track, as the save dialog says; it starts from [saveGroups]. */
    onSave: (Ride, String, RouteSaveTargets) -> Unit,
    saveGroups: RouteSaveTargets = RouteSaveTargets(),
    /** Whether new rides are kept at all; off also means no GPX auto-save. Only affects future rides. */
    logRides: Boolean,
    onLogRides: (Boolean) -> Unit,
    /** Group every finished ride is saved into, or null when that is off. */
    autoSaveGroup: Long?,
    /** (existing group or null, new group name or null); both null turns auto-saving off. */
    onAutoSave: (Long?, String?) -> Unit,
    onDelete: (Collection<Long>) -> Unit,
    /** Ends the ride under way now. */
    onStopRecording: () -> Unit = {},
    /** Joins the picked rides (two or more) into one. */
    onMerge: (Collection<Long>) -> Unit = {},
    /** Send the ride to the tablet through Curveon Companion (null: no companion on this phone). */
    onSendToTablet: ((Ride) -> Unit)? = null,
    /** Share the ride's line with the group ride on now. */
    onShareWithGroup: ((Ride) -> Unit)? = null,
    onDismiss: () -> Unit,
    /** Page menu when shown inside the GPX & rides dialog. */
    sideBar: (@Composable () -> Unit)? = null,
    /** Only the page, for a frame that is somebody else's (the GPX / Rides / Planned screen). */
    embedded: Boolean = false
) {
    var expanded by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf<Ride?>(null) }
    var pickingAutoGroup by remember { mutableStateOf(false) }
    /** Rides picked for deleting; null when not selecting. */
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var confirmDelete by remember { mutableStateOf<Set<Long>?>(null) }
    var confirmMerge by remember { mutableStateOf<Set<Long>?>(null) }
    val history = rides.filter { it.id != current?.id }
    val autoGroup = gpxGroups.firstOrNull { it.id == autoSaveGroup }?.takeIf { logRides }
    val groups = rememberRideGroups(history)
    /** Groups the user opened or closed by hand; the rest follow the default (only the newest is open). */
    var toggled by remember { mutableStateOf(mapOf<String, Boolean>()) }

    val subtitle = selection?.let { pluralStringResource(R.plurals.rides_selected, it.size, it.size) }
    val toolbar: @Composable RowScope.() -> Unit = {
            val sel = selection
            if (sel != null) {
                val all = sel.size == history.size
                PillButton(if (all) Icons.Default.RemoveDone else Icons.Default.DoneAll,
                    stringResource(if (all) R.string.rides_select_none else R.string.rides_select_all), CockpitSurfaceVariant, TextPrimary) {
                    selection = if (all) emptySet() else history.map { it.id }.toSet()
                }
                if (sel.size >= 2) RoundButton(Icons.Default.CallMerge, stringResource(R.string.rides_merge), 48.dp, CockpitSurfaceVariant, TextPrimary) { confirmMerge = sel }
                if (sel.isNotEmpty()) RoundButton(Icons.Default.DeleteOutline, stringResource(R.string.delete), 48.dp, WarningRed, TextPrimary) { confirmDelete = sel }
            }
            if (history.isNotEmpty()) RoundButton(
                Icons.Default.Checklist, stringResource(R.string.rides_select), 48.dp,
                if (sel != null) ElectricCyan else CockpitSurfaceVariant, if (sel != null) Color(0xFF00363D) else TextPrimary
            ) { selection = if (sel == null) emptySet() else null }
    }
    val body: @Composable ColumnScope.() -> Unit = {
      // Everything scrolls together: with a short screen (a phone held sideways) the lists would
      // otherwise be left a line or two between the switches.
      // The select buttons stay put (a fixed row, or in the frame's title bar), not in the list.
      if (embedded && history.isNotEmpty()) PageToolbar(subtitle, toolbar)
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Keep rides at all? Off also turns the GPX auto-save off (it is greyed out below).
        item { Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CockpitSurfaceVariant)
                .clickable { onLogRides(!logRides) }.padding(horizontal = 14.dp, vertical = 8.dp).testTag("rides_log"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.History, null, tint = if (logRides) ElectricCyan else TextMuted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rides_log), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(if (logRides) R.string.rides_log_on else R.string.rides_log_off),
                    color = TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            CockpitSwitch(checked = logRides, onCheckedChange = onLogRides)
        } }

        // Auto-save: off, or the group every finished ride goes into (tap to change).
        item { Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CockpitSurfaceVariant)
                .clickable(enabled = logRides) { pickingAutoGroup = true }.alpha(if (logRides) 1f else 0.4f)
                .padding(horizontal = 14.dp, vertical = 8.dp).testTag("rides_auto_save"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Route, null, tint = if (autoGroup != null) ElectricCyan else TextMuted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rides_auto_save), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        // Greyed out: say why, so it doesn't look broken.
                        !logRides -> stringResource(R.string.rides_auto_save_needs_log)
                        autoGroup != null -> stringResource(R.string.rides_auto_save_group, autoGroup.name)
                        else -> stringResource(R.string.rides_auto_save_off)
                    },
                    color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            CockpitSwitch(
                checked = autoGroup != null,
                onCheckedChange = { on -> if (logRides) { if (on) pickingAutoGroup = true else onAutoSave(null, null) } })
        } }

        if (current != null) item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ElectricCyan.copy(alpha = 0.1f))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rides_recording, current.title.ifEmpty { stringResource(R.string.rides_this_ride) }), color = ElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    PillButton(Icons.Default.Stop, stringResource(R.string.rides_stop), CockpitSurface, WarningRed, onClick = onStopRecording)
                }
                Spacer(Modifier.height(8.dp))
                Stats(current)
            }
        }

        if (history.isEmpty() && current == null) item {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.rides_empty), color = TextMuted, fontSize = 15.sp)
            }
        } else run {
            groups.forEachIndexed { index, g ->
                val groupOpen = toggled[g.key] ?: (index == 0)
                item(key = "group-${g.key}") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { toggled = toggled + (g.key to !groupOpen) }
                            .padding(horizontal = 6.dp, vertical = 8.dp).testTag("rides_group_${g.key}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (groupOpen) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = ElectricCyan, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(g.label(), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.rides_group_summary, g.rides.size, (g.rides.sumOf { it.distanceMeters } / 1000).toInt()),
                            color = TextMuted, fontSize = 12.sp)
                    }
                }
                if (groupOpen) items(g.rides, key = { it.id }) { r ->
                val sel = selection
                val picked = sel != null && r.id in sel
                val open = sel == null && expanded == r.id
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (picked) ElectricCyan.copy(alpha = 0.15f) else CockpitSurfaceVariant)
                        .border(1.dp, if (picked) ElectricCyan else Color.Transparent, RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = {
                                if (sel != null) selection = if (picked) sel - r.id else sel + r.id
                                else expanded = if (open) null else r.id
                            },
                            onLongClick = { selection = (sel ?: emptySet()) + r.id }
                        ).padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sel != null) {
                            Icon(if (picked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (picked) ElectricCyan else TextMuted, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        TrackThumbnail(r, Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title.ifEmpty { stringResource(R.string.ride) }, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(dateText(r), color = TextSecondary, fontSize = 12.sp)
                        }
                        Text(stringResource(R.string.distance_km_whole, r.distanceMeters / 1000), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    if (open) {
                        Spacer(Modifier.height(12.dp))
                        Stats(r)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(Icons.Default.Replay, stringResource(R.string.rides_ride_again), ElectricCyan, Color(0xFF00363D),
                                Modifier.weight(1f)) { onRideAgain(r) }
                            PillButton(Icons.Default.SwapHoriz, stringResource(R.string.rides_ride_reversed), CockpitSurface, TextPrimary,
                                Modifier.weight(1f)) { onRideReversed(r) }
                        }
                        Spacer(Modifier.height(10.dp))
                        // Three round buttons beside Save: smaller on a phone, so Save keeps room for its label.
                        val round = if (isNarrowScreen()) 48.dp else 56.dp
                        Row(horizontalArrangement = Arrangement.spacedBy(if (isNarrowScreen()) 8.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PillButton(Icons.Default.BookmarkBorder, stringResource(R.string.rides_save), CockpitSurface, TextPrimary, Modifier.weight(1f)) { saving = r }
                            onSendToTablet?.let { send ->
                                Box(Modifier.testTag("send_to_tablet")) {
                                    RoundButton(Icons.Default.TabletAndroid, stringResource(R.string.send_to_tablet), round, CockpitSurface, TextPrimary) { send(r) }
                                }
                            }
                            onShareWithGroup?.let { share ->
                                RoundButton(Icons.Default.Groups, stringResource(R.string.share_with_group), round, CockpitSurface, TextPrimary) { share(r) }
                            }
                            RoundButton(Icons.Default.DeleteOutline, stringResource(R.string.delete), round, CockpitSurface, TextMuted) { onDelete(listOf(r.id)) }
                        }
                    }
                }
            }
            }
        }
      }
    }
    if (embedded) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), content = body) else CockpitDialog(
        title = stringResource(R.string.rides), icon = Icons.Default.History, accent = ElectricCyan, onDismiss = onDismiss, tag = "rides_dialog", sideBar = sideBar,
        subtitle = subtitle, actions = toolbar, content = body
    )

    saving?.let { r ->
        SaveRouteDialog(
            title = stringResource(R.string.rides_save_title),
            initialName = r.title.ifEmpty { stringResource(R.string.ride) } + " · " + dateText(r),
            initial = saveGroups, plannedGroups = plannedGroups, gpxGroups = gpxGroups,
            onSave = { name, t -> onSave(r, name, t); saving = null }, onDismiss = { saving = null }
        )
    }
    if (pickingAutoGroup) {
        GroupPickerDialog(stringResource(R.string.rides_auto_save_pick), gpxGroups, current = autoGroup?.id, allowNone = false,
            onPick = { id, name -> onAutoSave(id, name) }, onDismiss = { pickingAutoGroup = false })
    }
    confirmMerge?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmMerge = null },
            title = { HideSystemBars(); Text(stringResource(R.string.rides_merge_title, ids.size)) },
            text = { Text(stringResource(R.string.rides_merge_text)) },
            confirmButton = {
                TextButton(onClick = { onMerge(ids); confirmMerge = null; selection = null }) { Text(stringResource(R.string.rides_merge), color = ElectricCyan) }
            },
            dismissButton = { TextButton(onClick = { confirmMerge = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    confirmDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { HideSystemBars(); Text(pluralStringResource(R.plurals.rides_delete_title, ids.size, ids.size)) },
            text = { Text(stringResource(R.string.rides_delete_text)) },
            confirmButton = {
                TextButton(onClick = { onDelete(ids); confirmDelete = null; selection = null }) { Text(stringResource(R.string.delete), color = WarningRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun Stats(r: Ride) {
    val minutes = (r.movingMillis / 60_000).toInt()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("%.1f".format(r.distanceMeters / 1000), stringResource(R.string.stat_km))
        Stat("%d:%02d".format(minutes / 60, minutes % 60), stringResource(R.string.stat_riding))
        Stat("%.0f".format(r.averageSpeedKmh), stringResource(R.string.stat_avg_speed))
        Stat("%.0f".format(r.maxSpeedKmh), stringResource(R.string.stat_max_speed))
        Stat("%.0f".format(r.ascentMeters), stringResource(R.string.stat_climb))
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

/** The ride's shape, so rides are recognisable at a glance. */
@Composable
private fun TrackThumbnail(r: Ride, modifier: Modifier) {
    val pts = remember(r.id, r.track.length) { RideRecorder.parseTrack(r.track).map { it.lat to it.lon } }
    RouteThumbnail(pts, modifier)
}

/** A line's shape ((lat, lon) points) in a small square, for rides and planned trips alike. */
@Composable
fun RouteThumbnail(pts: List<Pair<Double, Double>>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(CockpitSurface)) {
        if (pts.size < 2) return@Canvas
        val k = cos(Math.toRadians(pts[0].first))
        val minX = pts.minOf { it.second * k }; val maxX = pts.maxOf { it.second * k }
        val minY = pts.minOf { it.first }; val maxY = pts.maxOf { it.first }
        val span = maxOf(maxX - minX, maxY - minY, 1e-6)
        val pad = size.minDimension * 0.12f
        val scale = (size.minDimension - 2 * pad) / span
        val ox = (size.width - (maxX - minX) * scale) / 2
        val oy = (size.height - (maxY - minY) * scale) / 2
        val path = Path()
        pts.forEachIndexed { i, p ->
            val o = Offset((ox + (p.second * k - minX) * scale).toFloat(), (size.height - oy - (p.first - minY) * scale).toFloat())
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, ElectricCyan, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun dateText(r: Ride): String =
    SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(r.startMs))

private class RideGroup(val key: String, val label: @Composable () -> String, val rides: List<Ride>)

/** This week, last week (ISO weeks, Monday first), then one group per month; newest first. */
@Composable
private fun rememberRideGroups(rides: List<Ride>): List<RideGroup> {
    val thisWeek = stringResource(R.string.rides_this_week)
    val lastWeek = stringResource(R.string.rides_last_week)
    return remember(rides, thisWeek, lastWeek) {
        val cal = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val weekStart = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
        val lastWeekStart = cal.timeInMillis
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.US)
        val monthLabel = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
        rides.sortedByDescending { it.startMs }.groupBy {
            when {
                it.startMs >= weekStart -> "week"
                it.startMs >= lastWeekStart -> "lastweek"
                else -> monthKey.format(Date(it.startMs))
            }
        }.map { (key, list) ->
            val label = when (key) {
                "week" -> thisWeek
                "lastweek" -> lastWeek
                else -> monthLabel.format(Date(list.first().startMs)).replaceFirstChar { it.uppercase() }
            }
            RideGroup(key, { label }, list)
        }
    }
}
