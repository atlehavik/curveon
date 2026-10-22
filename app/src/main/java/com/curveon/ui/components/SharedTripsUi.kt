package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.curveon.R
import com.curveon.data.groups.SharedTrip
import com.curveon.ui.theme.*

/** The shared trips the rider has accepted, under "Shared routes": ride one (it opens in the preview) or take it off the list. */
@Composable
fun ColumnScope.SharedTripsPage(trips: List<SharedTrip>, onRide: (SharedTrip) -> Unit, onRemove: (SharedTrip) -> Unit) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("shared_trips_page"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (trips.isEmpty()) Text(stringResource(R.string.shared_trips_none), color = TextMuted, fontSize = 14.sp)
        trips.forEach { t ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.doc.name.ifBlank { t.doc.destination.name }, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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

/** A friend shared a trip: ride it now, keep it under "Shared routes", or ignore it (and it is not offered again). */
@Composable
fun SharedTripOfferDialog(trip: SharedTrip, onRideNow: () -> Unit, onSave: () -> Unit, onIgnore: () -> Unit, fromFriend: Boolean = false) {
    Dialog(onDismissRequest = onSave, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(CockpitSurface).padding(20.dp).widthIn(max = 520.dp).testTag("shared_trip_offer"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.shared_trip_offer_title, trip.fromNickname ?: ""), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(trip.doc.name.ifBlank { trip.doc.destination.name }, color = NeonAmber, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(sharedTripInfo(trip), color = TextSecondary, fontSize = 13.sp)
            Text(stringResource(if (fromFriend) R.string.shared_trip_offer_hint_friend else R.string.shared_trip_offer_hint), color = TextMuted, fontSize = 13.sp)
            PillButton(Icons.Default.Navigation, stringResource(R.string.ride_now), ElectricCyan, Color(0xFF00363D), Modifier.fillMaxWidth().testTag("shared_offer_ride"), onRideNow)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Default.BookmarkBorder, stringResource(R.string.shared_trip_save), CockpitSurfaceVariant, TextPrimary, Modifier.weight(1f).testTag("shared_offer_save"), onSave)
                PillButton(Icons.Default.Close, stringResource(R.string.shared_trip_ignore), WarningRed.copy(alpha = 0.25f), WarningRed, Modifier.weight(1f).testTag("shared_offer_ignore"), onIgnore)
            }
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
