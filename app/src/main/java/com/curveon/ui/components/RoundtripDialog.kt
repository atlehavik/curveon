package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.DialogProperties
import com.curveon.data.model.CompassDirection
import com.curveon.data.model.GroundPreference
import com.curveon.data.model.RoadTypePreference
import com.curveon.data.model.RoundtripConfig
import com.curveon.routing.SurfacePreference
import com.curveon.ui.theme.*

private val OnCyan = Color(0xFF00363D)

/**
 * GoRide-style roundtrip: pick a direction on the compass and a rough distance. Route options
 * are the same chips as in the route preview.
 */
@Composable
fun RoundtripDialog(
    initialConfig: RoundtripConfig,
    onDismiss: () -> Unit,
    onGenerate: (RoundtripConfig) -> Unit
) {
    var config by remember { mutableStateOf(initialConfig.copy(avoidTolls = false)) }
    val direction = config.selectedDirections.singleOrNull() ?: CompassDirection.NORTH

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.86f).clip(RoundedCornerShape(24.dp)).background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(24.dp)).padding(20.dp).testTag("roundtrip_dialog"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Explore, null, tint = NeonAmber, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Roundtrip", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("A loop from where you are, heading roughly that way", color = TextSecondary, fontSize = 13.sp)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = TextSecondary) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CompassRose(direction) { config = config.copy(selectedDirections = setOf(it)) }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label("Distance")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(40, 60, 100, 150, 220, 300).forEach { km ->
                            val on = config.radiusKm == km
                            Box(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) NeonAmber else CockpitSurfaceVariant)
                                    .clickable { config = config.copy(radiusKm = km) }.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) { Text("$km km", color = if (on) Color.Black else TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        }
                    }
                    Label("Roads")
                    Segmented(
                        RoadTypePreference.entries, config.roadType,
                        label = { when (it) { RoadTypePreference.FASTEST -> "Fastest"; RoadTypePreference.CURVY -> "Curvy"; RoadTypePreference.VERY_CURVY -> "Super curvy" } },
                        onSelect = { config = config.copy(roadType = it) }
                    )
                    RouteOptionChips(
                        surface = SurfacePreference.entries[config.groundPreference.ordinal],
                        avoidFerries = config.avoidFerries,
                        avoidMotorways = config.avoidHighways,
                        onSurface = { config = config.copy(groundPreference = GroundPreference.entries[it.ordinal]) },
                        onToggleFerries = { config = config.copy(avoidFerries = !config.avoidFerries) },
                        onToggleMotorways = { config = config.copy(avoidHighways = !config.avoidHighways) }
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(Icons.Default.Explore, "Plan ${config.radiusKm} km ${direction.code}", NeonAmber, Color.Black,
                    Modifier.widthIn(min = 260.dp).testTag("roundtrip_plan_btn")) {
                    onGenerate(config.copy(selectedDirections = setOf(direction)))
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)

/** Eight directions around a centre; tap one. */
@Composable
private fun CompassRose(selected: CompassDirection, onSelect: (CompassDirection) -> Unit) {
    val grid = listOf(
        listOf(CompassDirection.NORTH_WEST, CompassDirection.NORTH, CompassDirection.NORTH_EAST),
        listOf(CompassDirection.WEST, null, CompassDirection.EAST),
        listOf(CompassDirection.SOUTH_WEST, CompassDirection.SOUTH, CompassDirection.SOUTH_EAST)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        grid.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d ->
                    if (d == null) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(CockpitBackground), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(ElectricCyan))
                        }
                    } else {
                        val on = d == selected
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(if (on) NeonAmber else CockpitSurfaceVariant)
                                .border(1.dp, if (on) NeonAmber else CockpitBorder, CircleShape).clickable { onSelect(d) },
                            contentAlignment = Alignment.Center
                        ) { Text(d.code, color = if (on) Color.Black else TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
    }
}
