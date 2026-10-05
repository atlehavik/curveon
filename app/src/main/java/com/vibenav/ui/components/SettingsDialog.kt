package com.vibenav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vibenav.ui.theme.*

@Composable
fun SettingsDialog(
    useMph: Boolean,
    onToggleUnits: () -> Unit,
    isObdEnabled: Boolean,
    onToggleObd: () -> Unit,
    offlineCacheCount: Int,
    offlineCacheSizeMb: Float,
    onClearCache: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(28.dp))
                .background(CockpitSurface)
                .border(1.5.dp, CockpitBorder, RoundedCornerShape(28.dp))
                .padding(20.dp)
                .testTag("settings_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(CockpitSurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = ElectricCyan,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "COCKPIT SETTINGS",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Motorcycle Display & Offline Maps",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("settings_dialog_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = "DISPLAY & SPEEDOMETER",
                            color = ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CockpitSurfaceVariant)
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "Speed Units", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (useMph) "Imperial (MPH, Miles)" else "Metric (KM/H, Kilometers)",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = onToggleUnits,
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("toggle_units_btn")
                            ) {
                                Text(
                                    text = if (useMph) "SWITCH TO KM/H" else "SWITCH TO MPH",
                                    color = Color(0xFF00363D),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = "OBD2 TELEMETRY GAUGE",
                            color = ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CockpitSurfaceVariant)
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Show OBD2 Telemetry", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "Engine RPM, Coolant temp, Gear, Battery volts",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = isObdEnabled,
                                onCheckedChange = { onToggleObd() },
                                colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan),
                                modifier = Modifier.testTag("toggle_obd_switch")
                            )
                        }
                    }

                    item {
                        Text(
                            text = "OFFLINE OPENSTREETMAP TILES",
                            color = ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CockpitSurfaceVariant)
                                .padding(14.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Cached Offline Tiles", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "$offlineCacheCount tiles (%.1f MB)".format(offlineCacheSizeMb),
                                        color = EcoGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = onClearCache,
                                    colors = ButtonDefaults.buttonColors(containerColor = WarningRed.copy(alpha = 0.2f)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, WarningRed),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Clear Offline Map Cache", color = WarningRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
