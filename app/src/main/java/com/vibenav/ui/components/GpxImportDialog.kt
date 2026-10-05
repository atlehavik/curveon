package com.vibenav.ui.components

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vibenav.data.model.GpxTrack
import com.vibenav.data.model.GroundPreference
import com.vibenav.ui.theme.*

/** Saved GPX tracks: import a file, follow a track (optionally reversed) or delete it. */
@Composable
fun GpxImportDialog(
    tracks: List<GpxTrack>,
    message: String?,
    onImport: () -> Unit,
    onFollow: (GpxTrack, reverse: Boolean, ground: GroundPreference) -> Unit,
    onDelete: (GpxTrack) -> Unit,
    onDismiss: () -> Unit
) {
    var reverse by remember { mutableStateOf(false) }
    var ground by remember { mutableStateOf(GroundPreference.MIXED) }
    var confirmDelete by remember { mutableStateOf<GpxTrack?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
                .testTag("gpx_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GPX TRACKS", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("Ride a track you planned or downloaded", color = TextSecondary, fontSize = 12.sp)
                }
                Button(
                    onClick = onImport,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    modifier = Modifier.height(48.dp).testTag("gpx_import_btn")
                ) {
                    Icon(Icons.Default.FileOpen, null, tint = Color(0xFF00363D))
                    Spacer(Modifier.width(8.dp))
                    Text("Import .gpx", color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = TextSecondary) }
            }

            if (message != null) Text(message, color = NeonAmber, fontSize = 13.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GroundPreference.entries.forEach { g ->
                    val on = g == ground
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (on) ElectricCyan else CockpitSurfaceVariant)
                            .clickable { ground = g }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(g.label, color = if (on) Color(0xFF00363D) else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.weight(1f))
                Text("Reverse", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Switch(checked = reverse, onCheckedChange = { reverse = it }, colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan))
            }

            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "No tracks yet. Import a .gpx file from the tablet's storage\n(e.g. Downloads, or copied over USB).",
                        color = TextMuted, fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks, key = { it.id }) { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CockpitSurfaceVariant)
                                .clickable { onFollow(t, reverse, ground) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Route, null, tint = ElectricCyan, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "%.1f km · ↑ %d m · %s".format(t.totalDistanceKm, t.elevationGainMeters, t.filename),
                                    color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { confirmDelete = t }) { Icon(Icons.Default.DeleteOutline, "Delete", tint = TextMuted) }
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(ElectricCyan),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.Navigation, "Ride", tint = Color(0xFF00363D)) }
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
