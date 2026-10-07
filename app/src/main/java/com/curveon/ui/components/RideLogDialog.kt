package com.curveon.ui.components

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

/** Ride history: the ride in progress on top, earlier rides below. Tap a ride for its actions. */
@Composable
fun RideLogDialog(
    current: Ride?,
    rides: List<Ride>,
    onRideAgain: (Ride) -> Unit,
    gpxGroups: List<com.curveon.data.model.GpxGroup>,
    /** Keep the ride in the GPX collection: (ride, existing group or null, new group name or null). */
    onSaveToGpx: (Ride, Long?, String?) -> Unit,
    onDelete: (Ride) -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf<Ride?>(null) }
    val history = rides.filter { it.id != current?.id }

    CockpitDialog(title = "Rides", icon = Icons.Default.TwoWheeler, accent = ElectricCyan, onDismiss = onDismiss, tag = "rides_dialog") {
        if (current != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ElectricCyan.copy(alpha = 0.1f))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Text("● Recording · ${current.title.ifEmpty { "this ride" }}", color = ElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Stats(current)
            }
        }

        if (history.isEmpty() && current == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Rides are recorded automatically once you get going.", color = TextMuted, fontSize = 15.sp)
            }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it.id }) { r ->
                val open = expanded == r.id
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CockpitSurfaceVariant)
                        .clickable { expanded = if (open) null else r.id }.padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackThumbnail(r, Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title.ifEmpty { "Ride" }, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(dateText(r), color = TextSecondary, fontSize = 12.sp)
                        }
                        Text("%.0f km".format(r.distanceMeters / 1000), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    if (open) {
                        Spacer(Modifier.height(12.dp))
                        Stats(r)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(Icons.Default.Replay, "Ride again", ElectricCyan, androidx.compose.ui.graphics.Color(0xFF00363D),
                                Modifier.weight(1f)) { onRideAgain(r) }
                            PillButton(Icons.Default.Route, "Save to GPX", CockpitSurface, TextPrimary, Modifier.weight(1f)) { saving = r }
                            RoundButton(Icons.Default.DeleteOutline, "Delete", 56.dp, CockpitSurface, TextMuted) { onDelete(r) }
                        }
                    }
                }
            }
        }
    }

    saving?.let { r ->
        GroupPickerDialog("Save to GPX group", gpxGroups, current = null, onPick = { id, name -> onSaveToGpx(r, id, name) }, onDismiss = { saving = null })
    }
}

@Composable
private fun Stats(r: Ride) {
    val minutes = (r.movingMillis / 60_000).toInt()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("%.1f".format(r.distanceMeters / 1000), "km")
        Stat("%d:%02d".format(minutes / 60, minutes % 60), "riding")
        Stat("%.0f".format(r.averageSpeedKmh), "avg km/h")
        Stat("%.0f".format(r.maxSpeedKmh), "max km/h")
        Stat("%.0f".format(r.ascentMeters), "m climb")
        if (r.maxLeanLeftDeg > 0 || r.maxLeanRightDeg > 0) Stat("%.0f° / %.0f°".format(r.maxLeanLeftDeg, r.maxLeanRightDeg), "lean L / R")
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
