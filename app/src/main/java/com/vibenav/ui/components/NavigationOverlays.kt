package com.vibenav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
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
import com.vibenav.data.model.GeoPoint
import com.vibenav.data.model.GroundPreference
import com.vibenav.data.model.RoadTypePreference
import com.vibenav.data.routing.RoutingStatus
import com.vibenav.ui.theme.*
import com.vibenav.ui.viewmodel.NavigationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Remaining distance / arrival time with a stop button, shown at the bottom of the map. */
@Composable
fun NavigationSummaryBar(summary: NavigationSummary, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val eta = remember(summary.arrivalTimeMillis / 60_000) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(summary.arrivalTimeMillis))
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CockpitSurface.copy(alpha = 0.95f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("nav_summary_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(eta, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(
            "%.0f km · %d:%02d".format(summary.remainingKm, summary.remainingMinutes / 60, summary.remainingMinutes % 60),
            color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        IconButton(
            onClick = onStop,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(WarningRed.copy(alpha = 0.2f)).testTag("nav_stop_btn")
        ) {
            Icon(Icons.Default.Close, "Stop navigation", tint = WarningRed)
        }
    }
}

/** "Calculating…" spinner or an error message with a dismiss button. */
@Composable
fun RoutingStatusCard(status: RoutingStatus, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    when (status) {
        RoutingStatus.Idle -> Unit
        is RoutingStatus.Calculating -> Row(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(CockpitSurface.copy(alpha = 0.95f))
                .border(1.dp, ElectricCyan, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(color = ElectricCyan, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(status.message, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        is RoutingStatus.Failed -> Row(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(CockpitSurface.copy(alpha = 0.95f))
                .border(1.dp, WarningRed, RoundedCornerShape(14.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = WarningRed)
            Spacer(Modifier.width(12.dp))
            Text(status.message, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Close, "Dismiss", tint = TextSecondary)
        }
    }
}

/** Route options chosen before starting navigation to a point. */
data class RouteChoice(
    val roadType: RoadTypePreference = RoadTypePreference.CURVY,
    val ground: GroundPreference = GroundPreference.PAVED,
    val avoidFerries: Boolean = false,
    val avoidMotorways: Boolean = false
)

/** Shown after a long-press on the map: ride there with the chosen style. */
@Composable
fun NavigateHereDialog(
    target: GeoPoint,
    initial: RouteChoice,
    onDismiss: () -> Unit,
    onGo: (RouteChoice) -> Unit,
    title: String = "Navigate here",
    subtitle: String = "%.5f, %.5f".format(target.latitude, target.longitude)
) {
    var choice by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)

            ChoiceRow("Roads", RoadTypePreference.entries, choice.roadType, { it.label }) { choice = choice.copy(roadType = it) }
            ChoiceRow("Surface", GroundPreference.entries, choice.ground, { it.label }) { choice = choice.copy(ground = it) }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleChip("Avoid ferries", choice.avoidFerries) { choice = choice.copy(avoidFerries = it) }
                ToggleChip("Avoid motorways", choice.avoidMotorways) { choice = choice.copy(avoidMotorways = it) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onGo(choice) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    modifier = Modifier.height(48.dp).testTag("navigate_here_go")
                ) { Text("GO", color = Color(0xFF00363D), fontWeight = FontWeight.Black, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(label: String, values: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { v ->
                val on = v == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) ElectricCyan else CockpitSurfaceVariant)
                        .clickable { onSelect(v) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(text(v), color = if (on) Color(0xFF00363D) else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) NeonAmber.copy(alpha = 0.25f) else CockpitSurfaceVariant)
            .border(1.dp, if (on) NeonAmber else CockpitBorder, RoundedCornerShape(10.dp))
            .clickable { onChange(!on) }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (on) NeonAmber else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
