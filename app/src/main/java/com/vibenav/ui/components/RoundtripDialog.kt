package com.vibenav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.vibenav.data.model.*
import com.vibenav.ui.theme.*

@Composable
fun RoundtripDialog(
    initialConfig: RoundtripConfig,
    onDismiss: () -> Unit,
    onGenerate: (RoundtripConfig) -> Unit
) {
    var selectedDistanceKm by remember { mutableIntStateOf(initialConfig.radiusKm) }
    var selectedDirections by remember { mutableStateOf(initialConfig.selectedDirections) }
    var selectedRoadType by remember { mutableStateOf(initialConfig.roadType) }
    var selectedGround by remember { mutableStateOf(initialConfig.groundPreference) }
    var avoidHighways by remember { mutableStateOf(initialConfig.avoidHighways) }
    var avoidFerries by remember { mutableStateOf(initialConfig.avoidFerries) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(28.dp))
                .background(CockpitSurface)
                .border(1.5.dp, CockpitBorder, RoundedCornerShape(28.dp))
                .padding(20.dp)
                .testTag("roundtrip_dialog")
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
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
                                .background(NeonAmber.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = "Roundtrip",
                                tint = NeonAmber,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "GORIDE ROUNDTRIP",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Loop from & to current location",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("roundtrip_dialog_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Content
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Compass Direction Selector (Inspired by GoRide)
                    item {
                        Column {
                            Text(
                                text = "1. CHOOSE DIRECTION (COMPASS)",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // 8-way Compass Rose grid
                            val allDirs = CompassDirection.entries.toTypedArray()
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CompassButton(CompassDirection.NORTH_WEST, selectedDirections) { d ->
                                        selectedDirections = toggleDirection(selectedDirections, d)
                                    }
                                    CompassButton(CompassDirection.NORTH, selectedDirections) { d ->
                                        selectedDirections = toggleDirection(selectedDirections, d)
                                    }
                                    CompassButton(CompassDirection.NORTH_EAST, selectedDirections) { d ->
                                        selectedDirections = toggleDirection(selectedDirections, d)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CompassButton(CompassDirection.WEST, selectedDirections) { d ->
                                        selectedDirections = toggleDirection(selectedDirections, d)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .background(CockpitSurfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.TwoWheeler,
                                            contentDescription = "Motorcycle",
                                            tint = NeonAmber,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    CompassButton(CompassDirection.EAST, selectedDirections) { d ->
                                        selectedDirections = toggleDirection(selectedDirections, d)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CompassButton(CompassDirection.SOUTH_WEST, selectedDirections) { d ->
                                        selectedDirections = toggleDirection(selectedDirections, d)
                                    }
                                    CompassButton(CompassDirection.SOUTH, selectedDirections) { d ->
                                        selectedDirections = toggleDirection(selectedDirections, d)
                                    }
                                    CompassButton(CompassDirection.SOUTH_EAST, selectedDirections) { d ->
                                        selectedDirections = toggleDirection(selectedDirections, d)
                                    }
                                }
                            }
                        }
                    }

                    // 2. Distance Selector
                    item {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "2. APPROXIMATE DISTANCE",
                                    color = ElectricCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "$selectedDistanceKm km",
                                    color = NeonAmber,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Slider(
                                value = selectedDistanceKm.toFloat(),
                                onValueChange = { selectedDistanceKm = it.toInt() },
                                valueRange = 25f..250f,
                                steps = 8,
                                colors = SliderDefaults.colors(
                                    thumbColor = NeonAmber,
                                    activeTrackColor = NeonAmber,
                                    inactiveTrackColor = CockpitSurfaceVariant
                                ),
                                modifier = Modifier.testTag("roundtrip_distance_slider")
                            )

                            // Quick preset chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                listOf(35, 60, 100, 150, 220).forEach { km ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selectedDistanceKm == km) NeonAmber else CockpitSurfaceVariant)
                                            .clickable { selectedDistanceKm = km }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "$km km",
                                            color = if (selectedDistanceKm == km) Color.Black else TextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Road Curviness Selection
                    item {
                        Column {
                            Text(
                                text = "3. ROAD CURVINESS",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RoadTypePreference.entries.forEach { type ->
                                    val isSelected = selectedRoadType == type
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (isSelected) ElectricCyan.copy(alpha = 0.2f) else CockpitSurfaceVariant)
                                            .border(
                                                1.5.dp,
                                                if (isSelected) ElectricCyan else CockpitBorder,
                                                RoundedCornerShape(14.dp)
                                            )
                                            .clickable { selectedRoadType = type }
                                            .padding(vertical = 10.dp, horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = type.label,
                                                color = if (isSelected) ElectricCyan else TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = when (type) {
                                                    RoadTypePreference.FASTEST -> "Direct"
                                                    RoadTypePreference.CURVY -> "Sweepers"
                                                    RoadTypePreference.VERY_CURVY -> "Hairpins"
                                                },
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Ground Preference (Road, Gravel)
                    item {
                        Column {
                            Text(
                                text = "4. GROUND / TERRAIN",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GroundPreference.entries.forEach { ground ->
                                    val isSelected = selectedGround == ground
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (isSelected) EcoGreen.copy(alpha = 0.2f) else CockpitSurfaceVariant)
                                            .border(
                                                1.5.dp,
                                                if (isSelected) EcoGreen else CockpitBorder,
                                                RoundedCornerShape(14.dp)
                                            )
                                            .clickable { selectedGround = ground }
                                            .padding(vertical = 10.dp, horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = ground.label,
                                            color = if (isSelected) EcoGreen else TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 5. Avoidances
                    item {
                        Column {
                            Text(
                                text = "5. ROUTE RESTRICTIONS",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Avoid Motorways / Highways", color = TextSecondary, fontSize = 13.sp)
                                Switch(
                                    checked = avoidHighways,
                                    onCheckedChange = { avoidHighways = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Include Ferry Crossings", color = TextSecondary, fontSize = 13.sp)
                                Switch(
                                    checked = !avoidFerries,
                                    onCheckedChange = { avoidFerries = !it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action: Generate Button
                Button(
                    onClick = {
                        val config = RoundtripConfig(
                            radiusKm = selectedDistanceKm,
                            selectedDirections = if (selectedDirections.isEmpty()) setOf(CompassDirection.NORTH) else selectedDirections,
                            roadType = selectedRoadType,
                            groundPreference = selectedGround,
                            avoidHighways = avoidHighways,
                            avoidFerries = avoidFerries
                        )
                        onGenerate(config)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("roundtrip_generate_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonAmber),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AltRoute,
                        contentDescription = "Generate Route",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "CALCULATE CURVY ROUNDTRIP",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

private fun toggleDirection(
    current: Set<CompassDirection>,
    target: CompassDirection
): Set<CompassDirection> {
    return if (current.contains(target)) {
        if (current.size > 1) current - target else current
    } else {
        current + target
    }
}

@Composable
private fun CompassButton(
    dir: CompassDirection,
    selected: Set<CompassDirection>,
    onToggle: (CompassDirection) -> Unit
) {
    val isSelected = selected.contains(dir)
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) ElectricCyan else CockpitSurfaceVariant)
            .border(
                1.5.dp,
                if (isSelected) ElectricCyan else CockpitBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable { onToggle(dir) },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = dir.code,
                color = if (isSelected) Color(0xFF00363D) else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}
