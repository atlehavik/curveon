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
    gpxGroups: List<com.curveon.data.model.GpxGroup>,
    /** Keep the ride in the GPX collection: (ride, existing group or null, new group name or null). */
    onSaveToGpx: (Ride, Long?, String?) -> Unit,
    /** Group every finished ride is saved into, or null when that is off. */
    autoSaveGroup: Long?,
    /** (existing group or null, new group name or null); both null turns auto-saving off. */
    onAutoSave: (Long?, String?) -> Unit,
    onDelete: (Collection<Long>) -> Unit,
    onDismiss: () -> Unit,
    /** Page menu when shown inside the GPX & rides dialog. */
    sideBar: (@Composable () -> Unit)? = null
) {
    var expanded by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf<Ride?>(null) }
    var pickingAutoGroup by remember { mutableStateOf(false) }
    /** Rides picked for deleting; null when not selecting. */
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var confirmDelete by remember { mutableStateOf<Set<Long>?>(null) }
    val history = rides.filter { it.id != current?.id }
    val autoGroup = gpxGroups.firstOrNull { it.id == autoSaveGroup }

    CockpitDialog(
        title = stringResource(R.string.rides), icon = Icons.Default.TwoWheeler, accent = ElectricCyan, onDismiss = onDismiss, tag = "rides_dialog", sideBar = sideBar,
        subtitle = selection?.let { pluralStringResource(R.plurals.rides_selected, it.size, it.size) },
        actions = {
            val sel = selection
            if (sel != null) {
                val all = sel.size == history.size
                PillButton(if (all) Icons.Default.RemoveDone else Icons.Default.DoneAll,
                    stringResource(if (all) R.string.rides_select_none else R.string.rides_select_all), CockpitSurfaceVariant, TextPrimary) {
                    selection = if (all) emptySet() else history.map { it.id }.toSet()
                }
                if (sel.isNotEmpty()) RoundButton(Icons.Default.DeleteOutline, stringResource(R.string.delete), 48.dp, WarningRed, TextPrimary) { confirmDelete = sel }
            }
            if (history.isNotEmpty()) RoundButton(
                Icons.Default.Checklist, stringResource(R.string.rides_select), 48.dp,
                if (sel != null) ElectricCyan else CockpitSurfaceVariant, if (sel != null) Color(0xFF00363D) else TextPrimary
            ) { selection = if (sel == null) emptySet() else null }
        }
    ) {
        // Auto-save: off, or the group every finished ride goes into (tap to change).
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CockpitSurfaceVariant)
                .clickable { pickingAutoGroup = true }.padding(horizontal = 14.dp, vertical = 8.dp).testTag("rides_auto_save"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Route, null, tint = if (autoGroup != null) ElectricCyan else TextMuted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rides_auto_save), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (autoGroup != null) stringResource(R.string.rides_auto_save_group, autoGroup.name) else stringResource(R.string.rides_auto_save_off),
                    color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            CockpitSwitch(
                checked = autoGroup != null,
                onCheckedChange = { on -> if (on) pickingAutoGroup = true else onAutoSave(null, null) })
        }

        if (current != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ElectricCyan.copy(alpha = 0.1f))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Text(stringResource(R.string.rides_recording, current.title.ifEmpty { stringResource(R.string.rides_this_ride) }), color = ElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Stats(current)
            }
        }

        if (history.isEmpty() && current == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.rides_empty), color = TextMuted, fontSize = 15.sp)
            }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it.id }) { r ->
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
                            PillButton(Icons.Default.Route, stringResource(R.string.rides_save_to_gpx), CockpitSurface, TextPrimary, Modifier.weight(1f)) { saving = r }
                            RoundButton(Icons.Default.DeleteOutline, stringResource(R.string.delete), 56.dp, CockpitSurface, TextMuted) { onDelete(listOf(r.id)) }
                        }
                    }
                }
            }
        }
    }

    saving?.let { r ->
        GroupPickerDialog(stringResource(R.string.rides_save_to_group), gpxGroups, current = null, onPick = { id, name -> onSaveToGpx(r, id, name) }, onDismiss = { saving = null })
    }
    if (pickingAutoGroup) {
        GroupPickerDialog(stringResource(R.string.rides_auto_save_pick), gpxGroups, current = autoGroup?.id, allowNone = false,
            onPick = { id, name -> onAutoSave(id, name) }, onDismiss = { pickingAutoGroup = false })
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
        if (r.maxLeanLeftDeg > 0 || r.maxLeanRightDeg > 0) Stat("%.0f° / %.0f°".format(r.maxLeanLeftDeg, r.maxLeanRightDeg), stringResource(R.string.stat_lean))
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
    val pts = remember(r.id, r.track.length) { RideRecorder.parseTrack(r.track) }
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(CockpitSurface)) {
        if (pts.size < 2) return@Canvas
        val k = cos(Math.toRadians(pts[0].lat))
        val minX = pts.minOf { it.lon * k }; val maxX = pts.maxOf { it.lon * k }
        val minY = pts.minOf { it.lat }; val maxY = pts.maxOf { it.lat }
        val span = maxOf(maxX - minX, maxY - minY, 1e-6)
        val pad = size.minDimension * 0.12f
        val scale = (size.minDimension - 2 * pad) / span
        val ox = (size.width - (maxX - minX) * scale) / 2
        val oy = (size.height - (maxY - minY) * scale) / 2
        val path = Path()
        pts.forEachIndexed { i, p ->
            val o = Offset((ox + (p.lon * k - minX) * scale).toFloat(), (size.height - oy - (p.lat - minY) * scale).toFloat())
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, ElectricCyan, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun dateText(r: Ride): String =
    SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(r.startMs))
