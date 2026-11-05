@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import com.curveon.data.routing.RoutingStatus
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.NavigationSummary
import com.curveon.ui.viewmodel.StopAhead
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arrival time, the arrival at the next stop smaller in orange under it (none without a stop), and the end button (asks first), at
 * the top of the map beside the speed. Tapping it folds it out: distance and time left, then each stop (opens
 * the route menu: stops, save) with its own, fuel when worth knowing (tapping it asks whether to go there)
 * and add stop.
 */
@Composable
fun NavigationSummaryBar(
    summary: NavigationSummary,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** The height of the speed card it sits next to: the folded bar is as high as that. */
    foldedHeight: androidx.compose.ui.unit.Dp = 0.dp,
    fuel: com.curveon.data.routing.FuelHint? = null,
    onFuel: () -> Unit = {},
    onAddStop: () -> Unit = {},
    /** Takes the stop at this index of the trip out (after the rider has said yes). */
    onRemoveStop: (Int) -> Unit = {},
    onOpen: () -> Unit = {}
) {
    val eta = remember(summary.arrivalTimeMillis / 60_000) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(summary.arrivalTimeMillis))
    }
    // Folded: the arrival time, and smaller in orange under it the arrival at the next stop (none without one).
    // A tap folds it out: the destination's distance and time left, then each stop with its own, all in the same columns.
    var open by remember { mutableStateOf(false) }
    var askFuel by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf<StopAhead?>(null) }
    var askEnd by remember { mutableStateOf(false) }
    val next = summary.stops.firstOrNull()
    // The next stop's arrival: the destination's, less the time from the stop on to it.
    val nextEta = next?.let { s ->
        val at = summary.arrivalTimeMillis - (summary.remainingMinutes - s.minutes).coerceAtLeast(0) * 60_000L
        remember(at / 60_000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at)) }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CockpitSurface.copy(alpha = 0.95f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(16.dp))
            .clickable { open = !open }
            .testTag("nav_summary_bar")
    ) {
        // As wide as its widest line: the dividers stretch to that, not to all the room there is.
        Column(Modifier.width(IntrinsicSize.Max).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = (foldedHeight - 12.dp).coerceAtLeast(0.dp)),
                verticalAlignment = Alignment.CenterVertically,
                // Well apart, so reaching for the card (to add a stop) doesn't hit the end button.
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // The end button keeps its place, at the right edge; the text gives way if it must.
                Column(Modifier.weight(1f)) {
                    Text(eta, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                    if (!open && nextEta != null) Text(
                        nextEta, color = NeonAmber, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false, modifier = Modifier.testTag("nav_next_eta")
                    )
                }
                IconButton(
                    onClick = { askEnd = true },
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(WarningRed.copy(alpha = 0.2f)).testTag("nav_stop_btn")
                ) {
                    Icon(Icons.Default.Close, stringResource(R.string.nav_stop), tint = WarningRed, modifier = Modifier.size(24.dp))
                }
            }
            if (open) {
                // The destination, then each stop: name, and under it distance and time in the same columns.
                TripLeg(tripDistance(summary.remainingKm), summary.remainingMinutes, TextSecondary, Modifier.testTag("nav_remaining"))
                for (stop in summary.stops) {
                    HorizontalDivider(color = CockpitBorder)
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).testTag("nav_route_menu_btn"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stop.name, color = NeonAmber, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (stop.removable) IconButton(onClick = { askRemove = stop }, modifier = Modifier.padding(end = 8.dp).size(28.dp).testTag("nav_stop_remove")) {
                                Icon(Icons.Default.Close, stringResource(R.string.remove_stop), tint = WarningRed, modifier = Modifier.size(16.dp))
                            }
                        }
                        TripLeg(tripDistance(stop.km), stop.minutes, NeonAmber, Modifier.testTag("nav_remaining_stop"))
                    }
                }
                // Without stops the route menu (stops, save, end) is still one tap away.
                if (summary.stops.isEmpty()) Text(
                    stringResource(R.string.nav_route), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(vertical = 4.dp).testTag("nav_route_menu_btn")
                )
                if (fuel != null && (fuel.isLongGap || fuel.distanceMeters < 60_000)) {
                    val color = if (fuel.isLongGap) NeonAmber else EcoGreen
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f))
                            .clickable(enabled = fuel.point.lat != 0.0) { askFuel = true }.padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("fuel_chip"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocalGasStation, fuel.name.ifEmpty { stringResource(R.string.fuel_none_on_route) }, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (fuel.point.lat == 0.0) stringResource(R.string.fuel_none_ahead) else stringResource(R.string.distance_km_whole, fuel.distanceMeters / 1000),
                            color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(NeonAmber.copy(alpha = 0.15f))
                        .clickable(onClick = onAddStop).padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("nav_add_stop_btn"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AddLocationAlt, null, tint = NeonAmber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_stop), color = NeonAmber, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                // The whole route (save it, share it with the group), with or without stops.
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(ElectricCyan.copy(alpha = 0.15f))
                        .clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("nav_route_chip"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Route, null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nav_route), color = ElectricCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
    if (askEnd) AlertDialog(
        onDismissRequest = { askEnd = false },
        containerColor = CockpitSurface,
        title = { HideSystemBars(); Text(stringResource(R.string.nav_end_ask), color = TextPrimary, fontWeight = FontWeight.Black) },
        text = if (summary.stops.isEmpty()) null else ({ Text(stringResource(R.string.nav_end_ask_stops), color = TextSecondary) }),
        confirmButton = { TextButton(onClick = { askEnd = false; onStop() }, modifier = Modifier.testTag("nav_end_yes")) { Text(stringResource(R.string.nav_end), color = WarningRed) } },
        dismissButton = { TextButton(onClick = { askEnd = false }, modifier = Modifier.testTag("nav_end_no")) { Text(stringResource(R.string.cancel)) } }
    )
    askRemove?.let { stop ->
        AlertDialog(
            onDismissRequest = { askRemove = null },
            containerColor = CockpitSurface,
            title = { HideSystemBars(); Text(stringResource(R.string.delete_named, stop.name), color = TextPrimary, fontWeight = FontWeight.Black) },
            confirmButton = { TextButton(onClick = { askRemove = null; onRemoveStop(stop.index) }, modifier = Modifier.testTag("remove_stop_yes")) { Text(stringResource(R.string.delete), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { askRemove = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (askFuel && fuel != null) {
        val name = fuel.name.ifEmpty { stringResource(R.string.poi_fuel) }
        AlertDialog(
            onDismissRequest = { askFuel = false },
            containerColor = CockpitSurface,
            title = { HideSystemBars(); Text(stringResource(R.string.fuel_go_to, name), color = TextPrimary, fontWeight = FontWeight.Black) },
            text = { Text(stringResource(R.string.fuel_go_to_body, tripDistance(fuel.distanceMeters / 1000)), color = TextSecondary) },
            confirmButton = { TextButton(onClick = { askFuel = false; onFuel() }, modifier = Modifier.testTag("fuel_go_yes")) { Text(stringResource(R.string.yes), color = EcoGreen) } },
            dismissButton = { TextButton(onClick = { askFuel = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

/** Distance and time to somewhere, in two columns that line up from one leg to the next. */
@Composable
private fun TripLeg(distance: String, minutes: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(distance, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.width(64.dp))
        Text("%d:%02d".format(minutes / 60, minutes % 60), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** Whole kilometres; metres under one. */
@Composable
private fun tripDistance(km: Double): String =
    if (km < 1.0) stringResource(R.string.distance_m_int, (km * 100).toInt() * 10) else stringResource(R.string.distance_km_whole, km)

/** A stop on the way is reached: its name, big, for a few seconds (tap to put it away). */
@Composable
fun StopReachedBanner(name: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.clip(shape).background(CockpitSurface).border(2.dp, NeonAmber, shape).clickable(onClick = onDismiss)
            .padding(horizontal = 18.dp, vertical = 12.dp).testTag("stop_reached"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Flag, null, tint = NeonAmber, modifier = Modifier.size(34.dp))
        Column {
            Text(stringResource(R.string.stop_reached), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
            Text(name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/**
 * Riding again soon after a ride finished: go on logging that ride, or start a new one (the
 * dashboard picks the new one itself after a while if nothing is pressed). With [heading], [resumeLabel] and [newLabel]
 * it asks the same of a trip that was being navigated when Android ended Curveon.
 */
@Composable
fun RideResumeCard(
    title: String, onResume: () -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier,
    heading: String = stringResource(R.string.ride_resume_title), resumeLabel: String = stringResource(R.string.ride_resume_continue),
    newLabel: String = stringResource(R.string.ride_resume_new),
    tag: String = "ride_resume",
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(CockpitSurface).border(2.dp, ElectricCyan, shape).padding(horizontal = 18.dp, vertical = 12.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(heading, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
        if (title.isNotEmpty()) Text(title, color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onResume, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black),
                modifier = Modifier.height(52.dp)) { Text(resumeLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onNew, modifier = Modifier.height(52.dp)) {
                Text(newLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
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
            Icon(Icons.Default.Close, stringResource(R.string.dismiss), tint = TextSecondary)
        }
    }
}
