package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.curveon.data.routing.RoutingStatus
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.NavigationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remaining distance / arrival time with a stop button, shown at the bottom of the map. Next stop
 * and fuel appear only when there is something worth knowing; tapping fuel adds it as a stop.
 */
@Composable
fun NavigationSummaryBar(
    summary: NavigationSummary,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    fuel: com.curveon.data.routing.FuelHint? = null,
    onFuel: () -> Unit = {}
) {
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
        summary.nextStop?.let { (name, km) ->
            Text("${name.take(14)} %.0f km".format(km), color = NeonAmber, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (fuel != null && (fuel.isLongGap || fuel.distanceMeters < 60_000)) {
            val color = if (fuel.isLongGap) NeonAmber else EcoGreen
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f))
                    .clickable(enabled = fuel.point.lat != 0.0, onClick = onFuel).padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("fuel_chip"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocalGasStation, fuel.name, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (fuel.point.lat == 0.0) "none ahead" else "%.0f km".format(fuel.distanceMeters / 1000),
                    color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
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

/**
 * While riding, a picked place can be a stop on the way or a new destination. Two big buttons,
 * nothing else.
 */
@Composable
fun StopOrDestinationDialog(name: String, onAddStop: () -> Unit, onNewDestination: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Default.AddLocationAlt, "Add stop", NeonAmber, androidx.compose.ui.graphics.Color.Black,
                    Modifier.weight(1f).testTag("add_stop_btn"), onAddStop)
                PillButton(Icons.Default.Flag, "Go here", ElectricCyan, androidx.compose.ui.graphics.Color(0xFF00363D),
                    Modifier.weight(1f), onNewDestination)
            }
        }
    }
}
