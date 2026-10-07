package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
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

    CockpitDialog(
        title = stringResource(R.string.roundtrip), icon = Icons.Default.Explore, accent = NeonAmber, onDismiss = onDismiss,
        tag = "roundtrip_dialog", subtitle = stringResource(R.string.roundtrip_subtitle)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            CompassRose(direction) { config = config.copy(selectedDirections = setOf(it)) }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Label(stringResource(R.string.roundtrip_distance))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(40, 60, 100, 150, 220, 300).forEach { km ->
                        val on = config.radiusKm == km
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) NeonAmber else CockpitSurfaceVariant)
                                .clickable { config = config.copy(radiusKm = km) }.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) { Text(stringResource(R.string.distance_km_int, km), color = if (on) Color.Black else TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                    }
                }
                Label(stringResource(R.string.roundtrip_roads))
                Segmented(
                    RoadTypePreference.entries, config.roadType,
                    label = { stringResource(when (it) { RoadTypePreference.FASTEST -> R.string.style_fastest; RoadTypePreference.CURVY -> R.string.style_curvy; RoadTypePreference.VERY_CURVY -> R.string.style_super_curvy }) },
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

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(Icons.Default.Explore, stringResource(R.string.roundtrip_plan, config.radiusKm, compassPoint(direction)), NeonAmber, Color.Black,
                Modifier.widthIn(min = 260.dp).testTag("roundtrip_plan_btn")) {
                onGenerate(config.copy(selectedDirections = setOf(direction)))
            }
        }
    }
}

/** "N", "NE"… in the rider's language. */
@Composable
private fun compassPoint(d: CompassDirection) = stringResource(R.string.compass_points).split(',')[d.ordinal]

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
                        ) { Text(compassPoint(d), color = if (on) Color.Black else TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
    }
}
