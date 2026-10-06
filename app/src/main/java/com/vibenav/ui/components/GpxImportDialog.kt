package com.vibenav.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vibenav.data.gpx.GpxOverlay
import com.vibenav.data.gpx.PoiCategory
import com.vibenav.data.model.GpxTrack
import com.vibenav.ui.theme.*

/**
 * GPX manager: import and delete tracks, show them on the map in a chosen colour, show their
 * waypoints, and ride one. Tapping a row shows/hides the track; everything else is at the row end.
 */
@Composable
fun GpxManagerDialog(
    tracks: List<GpxTrack>,
    message: String?,
    poiCategories: Set<PoiCategory>,
    autoFollow: Boolean,
    onImport: () -> Unit,
    onToggleOnMap: (GpxTrack, Boolean) -> Unit,
    onColor: (GpxTrack, Int) -> Unit,
    onTogglePois: (GpxTrack, Boolean) -> Unit,
    onTogglePoiCategory: (PoiCategory) -> Unit,
    onAutoFollow: (Boolean) -> Unit,
    onRide: (GpxTrack) -> Unit,
    onDelete: (GpxTrack) -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf<GpxTrack?>(null) }
    var colorFor by remember { mutableStateOf<Long?>(null) }
    val poiCounts = remember(tracks) { tracks.associate { it.id to GpxOverlay.poiCount(it) } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(24.dp))
                .padding(18.dp)
                .testTag("gpx_manager"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GPX", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Button(
                    onClick = onImport,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    modifier = Modifier.height(48.dp).testTag("gpx_import_btn")
                ) {
                    Icon(Icons.Default.FileOpen, null, tint = Color(0xFF00363D))
                    Spacer(Modifier.width(8.dp))
                    Text("Import", color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = TextSecondary) }
            }
            if (message != null) Text(message, color = NeonAmber, fontSize = 13.sp)

            // Settings that apply to all tracks
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-follow", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Start navigation when you ride along a track shown on the map", color = TextMuted, fontSize = 12.sp)
                }
                Switch(checked = autoFollow, onCheckedChange = onAutoFollow, colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        Text(c.label, color = if (on) TextPrimary else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "No tracks yet. Import a .gpx file, or copy files into the tablet's\nAndroid/data/com.vibenav.tablet/files/gpx folder over USB.",
                        color = TextMuted, fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tracks, key = { it.id }) { t ->
                        val color = Color(GpxOverlay.colorOf(t))
                        val pois = poiCounts[t.id] ?: 0
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (t.showOnMap) color.copy(alpha = 0.10f) else CockpitSurfaceVariant)
                                .border(1.dp, if (t.showOnMap) color.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp))
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onToggleOnMap(t, !t.showOnMap) }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (t.showOnMap) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    if (t.showOnMap) "Shown on map" else "Hidden",
                                    tint = if (t.showOnMap) color else TextMuted, modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(
                                            "%.0f km".format(t.totalDistanceKm),
                                            t.elevationGainMeters.takeIf { it > 0 }?.let { "↑ $it m" },
                                            pois.takeIf { it > 0 }?.let { "$it waypoints" }
                                        ).joinToString(" · "),
                                        color = TextSecondary, fontSize = 12.sp, maxLines = 1
                                    )
                                }
                                // Colour
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape).clickable { colorFor = if (colorFor == t.id) null else t.id },
                                    contentAlignment = Alignment.Center
                                ) { Box(Modifier.size(22.dp).clip(CircleShape).background(color).border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)) }
                                if (pois > 0) RowIcon(Icons.Default.Place, "Waypoints", if (t.showPois) NeonAmber else TextMuted) { onTogglePois(t, !t.showPois) }
                                RowIcon(Icons.Default.DeleteOutline, "Delete", TextMuted) { confirmDelete = t }
                                Box(
                                    Modifier.padding(start = 4.dp).size(44.dp).clip(CircleShape).background(ElectricCyan).clickable { onRide(t) },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Default.Navigation, "Ride", tint = Color(0xFF00363D)) }
                            }
                            if (colorFor == t.id) {
                                Row(Modifier.padding(start = 50.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GpxOverlay.PALETTE.forEach { c ->
                                        Box(
                                            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                                .border(if (c == GpxOverlay.colorOf(t)) 3.dp else 1.dp, Color.White, CircleShape)
                                                .clickable { onColor(t, c); colorFor = null }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${t.name}?") },
            confirmButton = { TextButton(onClick = { onDelete(t); confirmDelete = null }) { Text("Delete", color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RowIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint)
    }
}

/** Details of a waypoint tapped on the map, with one action: go there. */
@Composable
fun PoiDialog(poi: com.vibenav.data.gpx.GpxPoi, onGo: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(CockpitSurface).border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp).widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(poi.category.color)))
                Spacer(Modifier.width(8.dp))
                Text(poi.category.label.uppercase(), color = Color(poi.category.color), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(poi.name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            if (poi.description.isNotBlank() && poi.description != poi.name) Text(poi.description, color = TextSecondary, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) }
                Spacer(Modifier.width(8.dp))
                PillButton(Icons.Default.Navigation, "Take me there", ElectricCyan, Color(0xFF00363D), onClick = onGo)
            }
        }
    }
}
