@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import com.curveon.ui.theme.*

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
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(wrongTurn) { while (true) { now = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(500) } }
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
 *
 * Point by point (a destination or stops while planning): each tap on the map rides the trip on
 * to there straight away, so a trip is built A → B → C → D without confirming each point; the bar
 * shows the trip so far, Undo takes the last point back and the tick finishes.
 */
@Composable
fun MapPickBar(
    purpose: SearchPurpose,
    picked: String?,
    onConfirm: () -> Unit,
    onBackToSearch: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Point by point instead of one place (not for the start, a stop while riding, or one put before/instead of another). */
    pointByPoint: Boolean = false,
    /** Point by point: the trip so far ("0:40 · 38 km"), and whether its route is being found. */
    tripSummary: String? = null,
    busy: Boolean = false,
    canUndo: Boolean = false,
    onUndo: () -> Unit = {},
    /** Point by point from "+ Stop" on a planned trip: the first point tapped, asking stop on the way or new end. */
    asking: String? = null,
    onAlongTheWay: () -> Unit = {},
    onNewEnd: () -> Unit = {},
    /** The first point tapped becomes a via point (a white dot to shape the route), not a stop. */
    onShapingPoint: () -> Unit = {},
    /** Give up the tapped point (a mistaken tap). */
    onDismissAsk: () -> Unit = {},
    /** Why the trip's route couldn't be found, instead of the hint. */
    error: String? = null,
    /** Marking a part of the route to change (on a long trip, so the rest stays as it is); null = no trip to mark on yet. */
    marking: Boolean? = null,
    onMark: () -> Unit = {},
    /** The route locked against dragging (panning only); null = no trip to lock yet. */
    locked: Boolean? = null,
    onLock: () -> Unit = {},
    /** A point put instead of or before another one: the picked place may also go in as a via point, not only a stop. */
    onConfirmShapingPoint: (() -> Unit)? = null
) {
    // Grows out of the shrinking dialog (bottom centre).
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f)) }
    val chaining = pointByPoint
    val accent = if (purpose == SearchPurpose.STOP && !chaining) NeonAmber else ElectricCyan
    Row(
        modifier
            .graphicsLayer {
                scaleX = 0.6f + 0.4f * grow.value; scaleY = 0.6f + 0.4f * grow.value; alpha = grow.value.coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(30.dp)).background(CockpitSurface.copy(alpha = 0.97f))
            .border(2.dp, if (error != null && chaining) WarningRed else accent, RoundedCornerShape(30.dp))
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("map_pick_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!(chaining && asking != null)) Box(Modifier.testTag("map_pick_search")) {
            RoundButton(Icons.Default.Search, stringResource(R.string.back_to_search), 48.dp, CockpitSurfaceVariant, accent, onBackToSearch)
        }
        if (chaining && asking != null) {
            // Two rows so it fits a phone: the tapped place with ✕ above, the three answers below.
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.testTag("map_pick_dismiss")) {
                        RoundButton(Icons.Default.Close, stringResource(R.string.cancel), 40.dp, CockpitSurfaceVariant, TextPrimary, onDismissAsk)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(asking, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(stringResource(R.string.pick_ask_stop_or_end), color = TextSecondary, fontSize = 13.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton(Icons.Default.RadioButtonUnchecked, stringResource(R.string.pick_shaping_point), Color.White, Color.Black,
                        Modifier.testTag("map_pick_via"), onShapingPoint)
                    PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.pick_along_the_way), NeonAmber, Color.Black,
                        Modifier.testTag("map_pick_along"), onAlongTheWay)
                    PillButton(Icons.Default.Flag, stringResource(R.string.pick_new_end), ElectricCyan, OnCyanDark,
                        Modifier.testTag("map_pick_new_end"), onNewEnd)
                }
            }
        } else if (chaining) {
            Row(Modifier.weight(1f, fill = false).widthIn(max = 340.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(color = ElectricCyan, strokeWidth = 3.dp, modifier = Modifier.padding(end = 10.dp).size(20.dp))
                Column {
                    Text(
                        error ?: stringResource(when {
                            marking == true -> R.string.edit_route_mark_hint
                            locked == true -> R.string.edit_route_locked_hint
                            else -> R.string.pick_hint_next_point
                        }),
                        color = if (error != null) WarningRed else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (tripSummary != null && error == null) Text(tripSummary, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
            }
            if (marking != null) Box(Modifier.testTag("route_edit_mark")) {
                RoundButton(Icons.Default.LinearScale, stringResource(R.string.edit_route_mark), 48.dp,
                    if (marking) ElectricCyan else CockpitSurfaceVariant, if (marking) OnCyanDark else TextPrimary, onMark)
            }
            if (locked != null && marking != true) Box(Modifier.testTag("route_edit_lock")) {
                RoundButton(if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    stringResource(if (locked) R.string.edit_route_unlock else R.string.edit_route_lock), 48.dp,
                    if (locked) NeonAmber else CockpitSurfaceVariant, if (locked) Color.Black else TextPrimary, onLock)
            }
            Box(Modifier.testTag("map_pick_undo").graphicsLayer { alpha = if (canUndo) 1f else 0.35f }) {
                RoundButton(Icons.Default.Undo, stringResource(R.string.undo), 48.dp, CockpitSurfaceVariant, TextPrimary) { if (canUndo) onUndo() }
            }
            Box(Modifier.testTag("map_pick_done")) {
                RoundButton(Icons.Default.Check, stringResource(R.string.done), 56.dp, ElectricCyan, OnCyanDark, onCancel)
            }
        } else if (picked != null && onConfirmShapingPoint != null) {
            // Two rows so it fits a phone: the place with ✕ above, via point or stop below.
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(picked, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    Box(Modifier.testTag("map_pick_cancel")) {
                        RoundButton(Icons.Default.Close, stringResource(R.string.cancel), 40.dp, CockpitSurfaceVariant, TextPrimary, onCancel)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton(Icons.Default.RadioButtonUnchecked, stringResource(R.string.pick_shaping_point), Color.White, Color.Black,
                        Modifier.testTag("map_pick_confirm_via"), onConfirmShapingPoint)
                    PillButton(Icons.Default.AddLocationAlt, stringResource(R.string.pick_as_stop), NeonAmber, Color.Black,
                        Modifier.testTag("map_pick_confirm"), onConfirm)
                }
            }
        } else {
            Text(
                picked ?: stringResource(when (purpose) {
                    SearchPurpose.DESTINATION -> R.string.pick_hint_destination
                    SearchPurpose.STOP -> R.string.pick_hint_stop
                    SearchPurpose.START -> R.string.pick_hint_start
                }),
                color = if (picked != null) TextPrimary else TextSecondary,
                fontSize = if (picked != null) 17.sp else 15.sp, fontWeight = if (picked != null) FontWeight.Black else FontWeight.Bold,
                maxLines = if (picked != null) 1 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 340.dp).padding(horizontal = 4.dp)
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
}


/**
 * Drawing the area for a roundtrip: what to do, about how long the loop round it gets once a
 * finger is drawing ([km]), where it starts if not here, and a way out.
 */
@Composable
fun SketchBar(
    onCancel: () -> Unit, modifier: Modifier = Modifier, km: Int? = null, startName: String? = null,
    /** Chosen before drawing: how long the loop may be, and on what surface. */
    maxKm: Int? = null, onMaxKm: (Int?) -> Unit = {},
    surface: com.curveon.routing.SurfacePreference = com.curveon.routing.SurfacePreference.PAVED,
    onSurface: (com.curveon.routing.SurfacePreference) -> Unit = {},
    /** Gravel/Offroad: how far off the ordinary road for gravel (null = any distance). */
    gravelReachKm: Int? = null,
    onGravelReach: (Int?) -> Unit = {},
    /** Drawing the area for an adventure to a destination: how long it may take, null = no limit (instead of the loop's max length). */
    adventure: Boolean = false,
    adventureMinutes: Int? = null,
    onAdventureMinutes: (Int?) -> Unit = {},
    /** "Good weather – pick for me": Curveon plans the loop where the forecast is best (null: no forecast, or an adventure). */
    onGoodWeather: (() -> Unit)? = null,
    goodWeatherBusy: Boolean = false,
    goodWeatherFailed: Boolean = false
) {
    Column(
        modifier
            .clip(RoundedCornerShape(26.dp)).background(CockpitSurface.copy(alpha = 0.97f))
            .border(2.dp, NeonAmber, RoundedCornerShape(26.dp))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 10.dp)
            .testTag("sketch_bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Gesture, null, tint = NeonAmber)
        Column(Modifier.weight(1f, fill = false).widthIn(max = 420.dp)) {
            Text(
                if (adventure) stringResource(R.string.adventure_draw_hint)
                else if (km != null) stringResource(R.string.roundtrip_draw_km, km) else stringResource(R.string.roundtrip_draw_hint),
                color = TextPrimary, fontSize = if (km != null && !adventure) 20.sp else 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (startName != null) Text(stringResource(R.string.from_place, startName), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
        Box(Modifier.testTag("sketch_cancel")) {
            RoundButton(Icons.Default.Close, stringResource(R.string.cancel), 48.dp, CockpitSurfaceVariant, TextPrimary, onCancel)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (adventure) AdventureTimeChip(adventureMinutes, onAdventureMinutes) else LoopMaxChip(maxKm, onMaxKm)
        SurfaceChip(surface, onSurface)
        if (surface >= com.curveon.routing.SurfacePreference.GRAVEL) GravelReachChip(gravelReachKm, onGravelReach)
        if (onGoodWeather != null) Box(Modifier.testTag("sketch_good_weather")) {
            OptionChip(
                stringResource(when { goodWeatherBusy -> R.string.weather_looking; goodWeatherFailed -> R.string.weather_none; else -> R.string.weather_pick_for_me }),
                active = true, onClick = onGoodWeather
            ) {
                if (goodWeatherBusy) CircularProgressIndicator(color = EcoGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.Default.WbSunny, null, tint = NeonAmber)
            }
        }
    }
    }
}

/**
 * How planning on the map works, in three short lines over the pick bar: shown each time the rider
 * plans point by point, until they say not to show it again.
 */
@Composable
fun PointByPointHelp(onOk: () -> Unit, onNever: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 440.dp).clip(RoundedCornerShape(20.dp)).background(CockpitSurface.copy(alpha = 0.97f))
            .border(1.5.dp, ElectricCyan, RoundedCornerShape(20.dp)).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp)
            .testTag("pbp_help"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.pbp_help_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
        @Composable fun line(icon: androidx.compose.ui.graphics.vector.ImageVector, text: Int) = Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = ElectricCyan, modifier = Modifier.padding(top = 1.dp, end = 10.dp).size(18.dp))
            Text(stringResource(text), color = TextSecondary, fontSize = 14.sp)
        }
        line(Icons.Default.TouchApp, R.string.pbp_help_tap)
        line(Icons.Default.Lock, R.string.pbp_help_lock)
        line(Icons.Default.LinearScale, R.string.pbp_help_mark)
        line(Icons.Default.Check, R.string.pbp_help_done)
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onNever) { Text(stringResource(R.string.pbp_help_never), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = onOk) { Text(stringResource(R.string.ok), color = ElectricCyan, fontSize = 15.sp, fontWeight = FontWeight.Black) }
        }
    }
}

/** A tap on the demo ride's DEMO tag: play at losing the GPS signal, or at taking a wrong turn at the next junction. */
@Composable
fun DemoRideMenu(
    signalLost: Boolean,
    wrongTurnAhead: Boolean,
    navigating: Boolean,
    onSignalLost: (Boolean) -> Unit,
    onWrongTurn: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CockpitSurface,
        title = { HideSystemBars(); Text(stringResource(R.string.demo_menu_title), color = TextPrimary, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Default.SatelliteAlt, stringResource(if (signalLost) R.string.demo_signal_back else R.string.demo_signal_lose),
                    NeonAmber.copy(alpha = 0.18f), NeonAmber, Modifier.fillMaxWidth().testTag("demo_signal")) { onSignalLost(!signalLost) }
                if (navigating) PillButton(Icons.Default.AltRoute, stringResource(if (wrongTurnAhead) R.string.demo_wrong_turn_set else R.string.demo_wrong_turn),
                    NeonAmber.copy(alpha = 0.18f), NeonAmber, Modifier.fillMaxWidth().testTag("demo_wrong_turn")) { if (!wrongTurnAhead) onWrongTurn() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
