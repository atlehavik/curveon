package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.curveon.R
import com.curveon.data.groups.SharedTrip
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay

/** The shared trips the rider has accepted, under "Shared routes": ride one (it opens in the preview) or take it off the list. */
@Composable
fun ColumnScope.SharedTripsPage(trips: List<SharedTrip>, onRide: (SharedTrip) -> Unit, onRemove: (SharedTrip) -> Unit) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("shared_trips_page"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (trips.isEmpty()) Text(stringResource(R.string.shared_trips_none), color = TextMuted, fontSize = 14.sp)
        trips.forEach { t ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(sharedTripInfo(t), color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(Icons.Default.Navigation, stringResource(R.string.ride_now), ElectricCyan, Color(0xFF00363D), Modifier.weight(1f)) { onRide(t) }
                    PillButton(Icons.Default.Close, stringResource(R.string.shared_trip_remove), CockpitSurface, TextPrimary, Modifier.weight(1f)) { onRemove(t) }
                }
            }
        }
    }
}

@Composable
private fun sharedTripInfo(t: SharedTrip): String {
    val stops = t.doc.stops.count { !it.via }
    val who = t.fromNickname?.let { stringResource(R.string.shared_trip_from, it) }
    return listOfNotNull(t.groupName, who, if (stops > 0) stringResource(R.string.shared_trip_stops, stops) else null).joinToString(" · ")
}

/**
 * What is shared is called [original], and the rider has [taken]: a field for the name to save it under, with a free one
 * in it. Nothing is overwritten.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SaveAsField(original: String, taken: Collection<String>, name: String, onName: (String) -> Unit) {
    Text(stringResource(R.string.share_name_taken, original), color = NeonAmber, fontSize = 14.sp)
    // The keyboard takes most of a phone held sideways: the card scrolls to the field, so the rider sees what they type.
    val bring = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboard) {
        // Again as the keyboard settles and the card gets its last height. Nothing moves if the field can already be seen.
        if (focused && keyboard > 0) repeat(4) { delay(200); bring.bringIntoView() }
    }
    OutlinedTextField(
        value = name, onValueChange = onName, singleLine = true, label = { Text(stringResource(R.string.share_save_as)) },
        isError = !com.curveon.data.groups.ShareNames.usable(name, taken),
        supportingText = if (com.curveon.data.groups.ShareNames.taken(name, taken)) { { Text(stringResource(R.string.share_name_still_taken)) } } else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = ElectricCyan, unfocusedBorderColor = CockpitBorder,
            cursorColor = ElectricCyan, focusedLabelColor = ElectricCyan, unfocusedLabelColor = TextSecondary
        ),
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bring).onFocusChanged { focused = it.isFocused }.testTag("share_save_as")
    )
}

/**
 * The card of a shared offer. Its window fills the screen and the card stays clear of the keyboard, so with the keyboard
 * up the name field and the buttons can always be scrolled to, also on a phone held sideways, where the keyboard takes
 * more than half the height.
 */
@Composable
private fun OfferFrame(onOutside: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onOutside, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        HideSystemBars()
        val view = LocalView.current
        val density = LocalDensity.current
        // With gesture navigation Android counts the keyboard from a window edge one navigation bar above the real one, so
        // the card would end that far behind the keyboard: what is still covered is measured against what can be seen.
        var covered by remember { mutableStateOf(0.dp) }
        var box by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
        fun measure() {
            val b = box?.takeIf { it.isAttached } ?: return
            val seen = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
            val onScreen = IntArray(2).also(view::getLocationOnScreen)
            val below = onScreen[1] + b.positionInWindow().y + b.size.height - seen.bottom
            covered = with(density) { below.coerceAtLeast(0f).toDp() }
        }
        // What can be seen is told a moment after the keyboard's height, so it is looked at again as the keyboard settles.
        val keyboard = WindowInsets.ime.getBottom(density)
        LaunchedEffect(keyboard) { repeat(6) { delay(100); measure() } }
        val outside by rememberUpdatedState(onOutside)
        Box(
            // The window is the whole screen, so Android no longer sees a tap beside the card as outside it.
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { outside() } }.safeDrawingPadding().onGloballyPositioned { box = it; measure() }.padding(bottom = covered),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.padding(16.dp).clip(RoundedCornerShape(20.dp)).pointerInput(Unit) { detectTapGestures { } }.background(CockpitSurface).widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()).padding(20.dp).testTag(tag),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content
            )
        }
    }
}

/**
 * A friend shared a trip: ride it now, keep it under "Shared routes", or ignore it (and it is not offered again). When the
 * rider already has a trip or track of that name ([taken]), they choose another; the callbacks get it, or null when the name is free.
 */
@Composable
fun SharedTripOfferDialog(
    trip: SharedTrip, onRideNow: (String?) -> Unit, onSave: (String?) -> Unit, onIgnore: () -> Unit,
    fromFriend: Boolean = false, taken: Collection<String> = emptyList()
) {
    val conflict = com.curveon.data.groups.ShareNames.taken(trip.title, taken)
    var name by remember(trip.tripId, taken) { mutableStateOf(com.curveon.data.groups.ShareNames.free(trip.title, taken)) }
    val ok = !conflict || com.curveon.data.groups.ShareNames.usable(name, taken)
    val chosen = name.trim().takeIf { conflict }
    OfferFrame(onOutside = { if (!conflict) onSave(null) }, tag = "shared_trip_offer") {
        Text(stringResource(R.string.shared_trip_offer_title, trip.fromNickname ?: ""), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(trip.title, color = NeonAmber, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(sharedTripInfo(trip), color = TextSecondary, fontSize = 13.sp)
        // With a name to choose there is no room for the hint on a phone held sideways, and the name matters more.
        if (conflict) SaveAsField(trip.title, taken, name) { name = it }
        else Text(stringResource(if (fromFriend) R.string.shared_trip_offer_hint_friend else R.string.shared_trip_offer_hint), color = TextMuted, fontSize = 13.sp)
        PillButton(Icons.Default.Navigation, stringResource(R.string.ride_now), if (ok) ElectricCyan else CockpitSurfaceVariant, if (ok) Color(0xFF00363D) else TextMuted,
            Modifier.fillMaxWidth().testTag("shared_offer_ride")) { if (ok) onRideNow(chosen) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(Icons.Default.BookmarkBorder, stringResource(R.string.shared_trip_save), CockpitSurfaceVariant, if (ok) TextPrimary else TextMuted,
                Modifier.weight(1f).testTag("shared_offer_save")) { if (ok) onSave(chosen) }
            PillButton(Icons.Default.Close, stringResource(R.string.shared_trip_ignore), WarningRed.copy(alpha = 0.25f), WarningRed, Modifier.weight(1f).testTag("shared_offer_ignore"), onIgnore)
        }
    }
}

/** Someone shared a GPX group or track: keep it among the rider's GPX tracks, just as they have it, or ignore it. A name the rider already has takes another. */
@Composable
fun SharedGpxOfferDialog(offer: com.curveon.data.groups.SharedGpxOffer, taken: Collection<String>, onSave: (String) -> Unit, onIgnore: () -> Unit) {
    val conflict = com.curveon.data.groups.ShareNames.taken(offer.name, taken)
    var name by remember(offer.shareId, taken) { mutableStateOf(com.curveon.data.groups.ShareNames.free(offer.name, taken)) }
    val ok = com.curveon.data.groups.ShareNames.usable(name, taken)
    OfferFrame(onOutside = { if (!conflict) onSave(offer.name) }, tag = "shared_gpx_offer") {
        Text(stringResource(if (offer.isGroup) R.string.shared_gpx_group_offer_title else R.string.shared_gpx_track_offer_title, offer.fromNickname ?: ""),
            color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(offer.name, color = NeonAmber, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(listOfNotNull(offer.groupName, if (offer.isGroup) pluralStringResource(R.plurals.gpx_tracks_count, offer.tracks, offer.tracks) else null,
            "${offer.km} ${stringResource(R.string.stat_km)}").joinToString(" · "), color = TextSecondary, fontSize = 13.sp)
        Text(stringResource(R.string.shared_gpx_offer_hint), color = TextMuted, fontSize = 13.sp)
        if (conflict) SaveAsField(offer.name, taken, name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(Icons.Default.BookmarkBorder, stringResource(R.string.shared_gpx_save), if (ok) ElectricCyan else CockpitSurfaceVariant, if (ok) Color(0xFF00363D) else TextMuted,
                Modifier.weight(1f).testTag("shared_gpx_save")) { if (ok) onSave(name.trim()) }
            PillButton(Icons.Default.Close, stringResource(R.string.shared_trip_ignore), WarningRed.copy(alpha = 0.25f), WarningRed, Modifier.weight(1f).testTag("shared_gpx_ignore"), onIgnore)
        }
    }
}

/** Which friends get the trip: tick some and send. Without friends yet, it says where to add one. */
@Composable
fun FriendPickerDialog(friends: List<com.curveon.protocol.Message.FriendInfo>, onSend: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(setOf<String>()) }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            Modifier.padding(16.dp).clip(RoundedCornerShape(20.dp)).background(CockpitSurface).widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState()).padding(20.dp).testTag("friend_picker"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.share_trip_pick_friends), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            if (friends.isEmpty()) Text(stringResource(R.string.share_trip_no_friends), color = TextSecondary, fontSize = 14.sp)
            friends.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { chosen = if (f.id in chosen) chosen - f.id else chosen + f.id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FriendAvatar(f, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(f.nickname, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Checkbox(f.id in chosen, { chosen = if (it) chosen + f.id else chosen - f.id },
                        colors = CheckboxDefaults.colors(checkedColor = ElectricCyan, checkmarkColor = Color(0xFF00363D), uncheckedColor = TextMuted))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Default.Close, stringResource(R.string.cancel), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f), onDismiss)
                if (friends.isNotEmpty()) PillButton(
                    Icons.Default.Send, stringResource(R.string.share_trip_send), if (chosen.isEmpty()) CockpitSurfaceVariant else ElectricCyan,
                    if (chosen.isEmpty()) TextMuted else Color(0xFF00363D), Modifier.weight(1f).testTag("friend_picker_send")
                ) { if (chosen.isNotEmpty()) onSend(chosen.toList()) }
            }
        }
    }
}
