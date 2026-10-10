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
import androidx.compose.ui.graphics.graphicsLayer
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
 * Remaining distance / arrival time with add-stop and stop buttons, shown at the bottom of the map.
 * Next stop and fuel appear only when there is something worth knowing; tapping fuel adds it as a
 * stop. Tapping the bar opens the route menu (stops, save).
 */
@Composable
fun NavigationSummaryBar(
    summary: NavigationSummary,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    fuel: com.curveon.data.routing.FuelHint? = null,
    onFuel: () -> Unit = {},
    onAddStop: () -> Unit = {},
    onOpen: () -> Unit = {}
) {
    val eta = remember(summary.arrivalTimeMillis / 60_000) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(summary.arrivalTimeMillis))
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CockpitSurface.copy(alpha = 0.95f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("nav_summary_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(eta, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(
            stringResource(R.string.distance_km_whole, summary.remainingKm) + " · %d:%02d".format(summary.remainingMinutes / 60, summary.remainingMinutes % 60),
            color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        summary.nextStop?.let { (name, km) ->
            Text(name.take(14) + " " + stringResource(R.string.distance_km_whole, km), color = NeonAmber, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (fuel != null && (fuel.isLongGap || fuel.distanceMeters < 60_000)) {
            val color = if (fuel.isLongGap) NeonAmber else EcoGreen
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f))
                    .clickable(enabled = fuel.point.lat != 0.0, onClick = onFuel).padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("fuel_chip"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocalGasStation, fuel.name.ifEmpty { stringResource(R.string.fuel_none_on_route) }, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (fuel.point.lat == 0.0) stringResource(R.string.fuel_none_ahead) else stringResource(R.string.distance_km_whole, fuel.distanceMeters / 1000),
                    color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        IconButton(
            onClick = onAddStop,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(NeonAmber.copy(alpha = 0.2f)).testTag("nav_add_stop_btn")
        ) {
            Icon(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop), tint = NeonAmber)
        }
        IconButton(
            onClick = onStop,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(WarningRed.copy(alpha = 0.2f)).testTag("nav_stop_btn")
        ) {
            Icon(Icons.Default.Close, stringResource(R.string.nav_stop), tint = WarningRed)
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

/**
 * A picked place while riding or planning: a stop on the way, the destination, or (planning) where
 * the trip starts. Big buttons, nothing else.
 */
@Composable
fun PlaceActionDialog(
    name: String,
    onAddStop: () -> Unit,
    onGoHere: () -> Unit,
    onStartHere: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .widthIn(max = 760.dp)
                .testTag("place_action_dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop), NeonAmber, Color.Black,
                    Modifier.weight(1f).testTag("add_stop_btn"), onAddStop)
                PillButton(Icons.Default.Flag, stringResource(R.string.go_here), ElectricCyan, OnCyanDark,
                    Modifier.weight(1f).testTag("go_here_btn"), onGoHere)
                if (onStartHere != null) PillButton(Icons.Default.TripOrigin, stringResource(R.string.start_here), CockpitSurfaceVariant, TextPrimary,
                    Modifier.weight(1f).testTag("start_here_btn"), onStartHere)
            }
        }
    }
}

private val OnCyanDark = Color(0xFF00363D)

/** Name the route before saving it; [initialName] is a suggestion from the places it passes. */
@Composable
fun SaveRouteDialog(initialName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CockpitSurface,
        title = { HideSystemBars(); Text(stringResource(R.string.save_route_title), color = TextPrimary, fontWeight = FontWeight.Black) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.save_route_name)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = ElectricCyan, unfocusedBorderColor = CockpitBorder, cursorColor = ElectricCyan
                ),
                modifier = Modifier.fillMaxWidth().testTag("save_route_name")
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("save_route_confirm")) {
                Text(stringResource(R.string.save), color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = TextSecondary) } }
    )
}

/** The active route: its stops (remove one), add a stop, save it, end navigation. */
@Composable
fun RouteMenuDialog(
    title: String,
    stops: List<Pair<String, Double?>>,
    onRemoveStop: (Int) -> Unit,
    onAddStop: () -> Unit,
    onSave: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .widthIn(max = 760.dp)
                .testTag("route_menu_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title.ifEmpty { stringResource(R.string.route_menu_title) }, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
            if (stops.isEmpty()) Text(stringResource(R.string.route_stops_none), color = TextMuted, fontSize = 14.sp)
            stops.forEachIndexed { i, (name, km) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NeonAmber.copy(alpha = 0.12f))
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${i + 1}. $name", color = NeonAmber, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                    if (km != null) Text(stringResource(R.string.distance_km_whole, km), color = TextSecondary, fontSize = 14.sp)
                    IconButton(onClick = { onRemoveStop(i) }) { Icon(Icons.Default.Close, stringResource(R.string.preview_remove_stop), tint = NeonAmber) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.add_stop), NeonAmber, Color.Black, Modifier.weight(1f).testTag("menu_add_stop"), onAddStop)
                PillButton(Icons.Default.BookmarkBorder, stringResource(R.string.preview_save), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f).testTag("menu_save"), onSave)
                PillButton(Icons.Default.Close, stringResource(R.string.nav_end), WarningRed.copy(alpha = 0.25f), WarningRed, Modifier.weight(1f).testTag("menu_end"), onEnd)
            }
        }
    }
}

/**
 * Off the route with no quick way back. Two big buttons; the "carry on" one counts down and is
 * chosen by itself if the rider keeps riding. On a trip with stops left, carrying on asks once
 * more whether to keep them.
 */
@Composable
fun WrongTurnCard(
    wrongTurn: com.curveon.data.routing.WrongTurn,
    onTurnBack: () -> Unit,
    onCarryOn: (keepStops: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var askStops by remember(wrongTurn) { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(wrongTurn) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val secondsLeft = ((wrongTurn.autoAtMillis - now + 999) / 1000).coerceAtLeast(0).toInt()
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CockpitSurface.copy(alpha = 0.97f))
            .border(2.dp, NeonAmber, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .widthIn(max = 600.dp)
            .testTag("wrong_turn_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WrongLocation, null, tint = NeonAmber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(R.string.wrong_turn_title), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    if (askStops) androidx.compose.ui.res.pluralStringResource(R.plurals.wrong_turn_stops_left, wrongTurn.stopsLeft, wrongTurn.stopsLeft)
                    else wrongTurn.aheadExtraMinutes?.let { stringResource(R.string.wrong_turn_trip, it) } ?: stringResource(R.string.wrong_turn_no_ahead),
                    color = TextSecondary, fontSize = 14.sp
                )
            }
        }
        // Counts down while riding on; at 0 it waits for the rider to ride on (or answer).
        val carryOn = stringResource(if (wrongTurn.isTrip) R.string.wrong_turn_new_route else R.string.wrong_turn_rejoin_ahead) +
            if (secondsLeft > 0) " · " + stringResource(R.string.wrong_turn_auto, secondsLeft) else ""
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (askStops) {
                PillButton(Icons.Default.Place, stringResource(R.string.wrong_turn_keep_stops), ElectricCyan, OnCyanDark,
                    Modifier.weight(1f).testTag("wrong_turn_keep_stops")) { onCarryOn(true) }
                PillButton(Icons.Default.SkipNext, stringResource(R.string.wrong_turn_skip_stops), CockpitSurfaceVariant, TextPrimary,
                    Modifier.weight(1f).testTag("wrong_turn_skip_stops")) { onCarryOn(false) }
            } else {
                PillButton(Icons.Default.UTurnLeft, stringResource(R.string.wrong_turn_back), NeonAmber, Color.Black,
                    Modifier.weight(1f).testTag("wrong_turn_back"), onTurnBack)
                // Not offered on a loop/track when there is no way ahead: carrying on means nothing then.
                if (wrongTurn.isTrip || wrongTurn.aheadExtraMinutes != null) PillButton(
                    Icons.Default.Navigation, carryOn, ElectricCyan, OnCyanDark, Modifier.weight(1.4f).testTag("wrong_turn_carry_on")
                ) { if (wrongTurn.isTrip && wrongTurn.stopsLeft > 0) askStops = true else onCarryOn(true) }
            }
        }
    }
}

/**
 * What the search dialog shrinks into for "Pick on map": a small bar at the bottom of the map.
 * Tap the map to drop a pin (tap again to move it); then the bar names the place and offers to
 * use it. The search icon grows the dialog back; ✕ gives up.
 */
@Composable
fun MapPickBar(
    purpose: SearchPurpose,
    picked: String?,
    onConfirm: () -> Unit,
    onBackToSearch: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Grows out of the shrinking dialog (bottom centre).
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f)) }
    val accent = if (purpose == SearchPurpose.STOP) NeonAmber else ElectricCyan
    Row(
        modifier
            .graphicsLayer {
                scaleX = 0.6f + 0.4f * grow.value; scaleY = 0.6f + 0.4f * grow.value; alpha = grow.value.coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(30.dp)).background(CockpitSurface.copy(alpha = 0.97f))
            .border(2.dp, accent, RoundedCornerShape(30.dp))
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("map_pick_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.testTag("map_pick_search")) {
            RoundButton(Icons.Default.Search, stringResource(R.string.back_to_search), 48.dp, CockpitSurfaceVariant, accent, onBackToSearch)
        }
        Text(
            picked ?: stringResource(when (purpose) {
                SearchPurpose.DESTINATION -> R.string.pick_hint_destination
                SearchPurpose.STOP -> R.string.pick_hint_stop
                SearchPurpose.START -> R.string.pick_hint_start
            }),
            color = if (picked != null) TextPrimary else TextSecondary,
            fontSize = if (picked != null) 17.sp else 15.sp, fontWeight = if (picked != null) FontWeight.Black else FontWeight.Bold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 340.dp).padding(horizontal = 4.dp)
        )
        if (picked != null) {
            val (icon, label) = when (purpose) {
                SearchPurpose.DESTINATION -> Icons.Default.Flag to R.string.go_here
                SearchPurpose.STOP -> Icons.Default.AddLocationAlt to R.string.add_stop
                SearchPurpose.START -> Icons.Default.TripOrigin to R.string.start_here
            }
            PillButton(icon, stringResource(label), accent, if (purpose == SearchPurpose.STOP) Color.Black else OnCyanDark,
                Modifier.testTag("map_pick_confirm"), onConfirm)
        }
        Box(Modifier.testTag("map_pick_cancel")) {
            RoundButton(Icons.Default.Close, stringResource(R.string.cancel), 48.dp, CockpitSurfaceVariant, TextPrimary, onCancel)
        }
    }
}

/**
 * Editing a trip on the map: what to do (or that the new route is being found, or why it
 * couldn't be), the selected route's time and length, marking a part of the route to change
 * (on a long trip, so the rest stays as it is), Undo and Done.
 */
@Composable
fun RouteEditBar(
    summary: String?,
    busy: Boolean,
    error: String?,
    canUndo: Boolean,
    marking: Boolean,
    onMark: () -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f)) }
    Row(
        modifier
            .graphicsLayer {
                scaleX = 0.6f + 0.4f * grow.value; scaleY = 0.6f + 0.4f * grow.value; alpha = grow.value.coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(30.dp)).background(CockpitSurface.copy(alpha = 0.97f))
            .border(2.dp, if (error != null) WarningRed else ElectricCyan, RoundedCornerShape(30.dp))
            .padding(6.dp)
            .testTag("route_edit_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(color = ElectricCyan, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            else Icon(Icons.Default.Gesture, null, tint = if (error != null) WarningRed else ElectricCyan)
        }
        // The text gives way to the buttons (a phone held upright), never the other way round.
        Column(Modifier.weight(1f, fill = false).widthIn(max = 380.dp)) {
            Text(
                error ?: stringResource(when {
                    busy -> R.string.edit_route_working
                    marking -> R.string.edit_route_mark_hint
                    else -> R.string.edit_route_hint
                }),
                color = if (error != null) WarningRed else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (summary != null && !busy && error == null) Text(summary, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
        Box(Modifier.testTag("route_edit_mark")) {
            RoundButton(Icons.Default.LinearScale, stringResource(R.string.edit_route_mark), 48.dp,
                if (marking) ElectricCyan else CockpitSurfaceVariant, if (marking) OnCyanDark else TextPrimary, onMark)
        }
        Box(Modifier.testTag("route_edit_undo").graphicsLayer { alpha = if (canUndo) 1f else 0.35f }) {
            RoundButton(Icons.Default.Undo, stringResource(R.string.undo), 48.dp, CockpitSurfaceVariant, TextPrimary) { if (canUndo) onUndo() }
        }
        // Narrow: just the tick, so the text keeps some room.
        if (isNarrowScreen()) Box(Modifier.testTag("route_edit_done")) {
            RoundButton(Icons.Default.Check, stringResource(R.string.done), 56.dp, ElectricCyan, OnCyanDark, onDone)
        } else PillButton(Icons.Default.Check, stringResource(R.string.done), ElectricCyan, OnCyanDark, Modifier.testTag("route_edit_done"), onDone)
    }
}
