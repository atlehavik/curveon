package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.curveon.R
import com.curveon.data.bluetooth.TabletRoutes
import com.curveon.data.model.RouteItem
import com.curveon.ui.theme.*

/** On the tablet: a trip the rider sent from the phone, already kept among the planned trips. Ride it now, or later. */
@Composable
fun RouteFromPhoneDialog(trip: RouteItem, onRideNow: () -> Unit, onLater: () -> Unit) {
    Dialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            Modifier.padding(16.dp).clip(RoundedCornerShape(20.dp)).background(CockpitSurface).padding(20.dp).widthIn(max = 520.dp)
                .testTag("route_from_phone"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.route_from_phone_title), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(trip.title, color = NeonAmber, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.distance_km_whole, trip.totalDistanceKm.toDouble()) + " · " +
                    "%d:%02d".format(trip.estimatedMinutes / 60, trip.estimatedMinutes % 60),
                color = TextSecondary, fontSize = 13.sp
            )
            Text(stringResource(R.string.route_from_phone_kept), color = TextMuted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Default.Navigation, stringResource(R.string.ride_now), ElectricCyan, Color(0xFF00363D),
                    Modifier.weight(1f).testTag("route_from_phone_ride"), onRideNow)
                PillButton(Icons.Default.Schedule, stringResource(R.string.later), CockpitSurfaceVariant, TextPrimary,
                    Modifier.weight(1f).testTag("route_from_phone_later"), onLater)
            }
        }
    }
}

/**
 * On the phone: how sending a trip to the tablet went. A dialog rather than a toast, as it is often sent from the
 * rides or planned trips, which would hide a toast. When Curveon Companion is not sharing, or did not answer at all,
 * it says what to do, with a button that opens the companion.
 */
@Composable
fun SendToTabletResultDialog(outcome: TabletRoutes.Outcome, onOpenCompanion: () -> Unit, onDismiss: () -> Unit) {
    val needsCompanion = outcome == TabletRoutes.Outcome.SHARING_OFF || outcome == TabletRoutes.Outcome.NO_ANSWER
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("send_to_tablet_result"),
        icon = { Icon(Icons.Default.TabletAndroid, null, tint = if (outcome == TabletRoutes.Outcome.SENT) ElectricCyan else NeonAmber) },
        title = {
            HideSystemBars()
            Text(stringResource(when {
                needsCompanion -> R.string.send_to_tablet_help_title
                outcome == TabletRoutes.Outcome.EMPTY -> R.string.trip_no_line_title
                else -> R.string.send_to_tablet
            }))
        },
        // Scrolls with large text on a low screen, so none of what to do is cut off.
        text = {
            Text(modifier = Modifier.verticalScroll(rememberScrollState()), text = stringResource(when (outcome) {
                TabletRoutes.Outcome.SENT -> R.string.send_to_tablet_sent
                TabletRoutes.Outcome.WAITING -> R.string.send_to_tablet_waiting
                TabletRoutes.Outcome.TOO_LONG -> R.string.send_to_tablet_too_long
                TabletRoutes.Outcome.EMPTY -> R.string.send_to_tablet_empty
                TabletRoutes.Outcome.SHARING_OFF -> R.string.send_to_tablet_sharing_off
                TabletRoutes.Outcome.NO_ANSWER -> R.string.send_to_tablet_no_answer
            }))
        },
        confirmButton = {
            if (needsCompanion) TextButton(onClick = { onDismiss(); onOpenCompanion() }, Modifier.testTag("open_companion")) {
                Text(stringResource(R.string.open_companion))
            } else TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = if (needsCompanion) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }) else null
    )
}
