package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.routing.NavigationGoal
import com.curveon.data.routing.RouteCandidate
import com.curveon.data.routing.RoutePreview
import com.curveon.routing.RoadStyle
import com.curveon.routing.SurfacePreference
import com.curveon.ui.theme.*

private val OnCyan = Color(0xFF00363D)

/**
 * Shown over the map before riding: the alternatives side by side (tap to pick), the few options
 * that change the route, stops, save, and GO. Everything else stays out of the way.
 */
@Composable
fun RoutePreviewSheet(
    preview: RoutePreview,
    onSelect: (Int) -> Unit,
    onSurface: (SurfacePreference) -> Unit,
    onToggleFerries: () -> Unit,
    onToggleMotorways: () -> Unit,
    onRemoveStop: (Int) -> Unit,
    onShuffle: () -> Unit,
    onReverse: () -> Unit = {},
    onSave: () -> Unit,
    onGo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val goal = preview.goal
    val options = goal.options
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(CockpitSurface.copy(alpha = 0.97f))
            .border(1.dp, CockpitBorder, RoundedCornerShape(22.dp))
            .padding(14.dp)
            .testTag("route_preview_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Title row: where to, save, close
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (goal) {
                    is NavigationGoal.Destination -> goal.name
                    is NavigationGoal.Roundtrip -> "Roundtrip · about %.0f km".format(goal.distanceKm)
                    is NavigationGoal.Track -> goal.name
                },
                color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (goal is NavigationGoal.Roundtrip) SmallIcon(Icons.Default.Shuffle, "Another loop", onShuffle)
            if (goal is NavigationGoal.Track) SmallIcon(Icons.Default.SwapHoriz, "Ride it the other way", onReverse)
            if (preview.selectedRoute != null) SmallIcon(Icons.Default.BookmarkBorder, "Save route", onSave)
            SmallIcon(Icons.Default.Close, "Cancel", onClose)
        }

        // Stops (only A→B trips have them); tap × to drop one.
        if (goal is NavigationGoal.Destination && goal.stops.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                goal.stops.forEachIndexed { i, s ->
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(NeonAmber.copy(alpha = 0.18f))
                            .clickable { onRemoveStop(i) }.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}. ${s.name}", color = NeonAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Icon(Icons.Default.Close, "Remove stop", tint = NeonAmber, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // The alternatives
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            preview.candidates.forEachIndexed { i, c ->
                CandidateCard(c, selected = i == preview.selected, single = preview.candidates.size == 1,
                    modifier = Modifier.weight(1f)) { onSelect(i) }
            }
        }

        // Ferries switched off but the route still has one: say so plainly, and why.
        val ferries = preview.selectedRoute?.ferryCount ?: 0
        if (options.avoidFerries && ferries > 0) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NeonAmber.copy(alpha = 0.16f))
                    .border(1.dp, NeonAmber, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("preview_ferry_warning"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.DirectionsBoat, null, tint = NeonAmber, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (ferries == 1) "Includes a ferry. " else "Includes $ferries ferries. ") + when (goal) {
                        is NavigationGoal.Roundtrip -> "No loop this long that way without one – try another direction or distance."
                        else -> "There is no road there without one."
                    },
                    color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // Options + GO
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteOptionChips(
                surface = options.surface, avoidFerries = options.avoidFerries, avoidMotorways = options.avoidMotorways,
                onSurface = onSurface, onToggleFerries = onToggleFerries, onToggleMotorways = onToggleMotorways
            )
            Spacer(Modifier.weight(1f))
            val ready = preview.selectedRoute != null
            Row(
                Modifier.height(56.dp).widthIn(min = 140.dp).clip(RoundedCornerShape(28.dp))
                    .background(if (ready) ElectricCyan else CockpitSurfaceVariant)
                    .clickable(enabled = ready, onClick = onGo).padding(horizontal = 22.dp).testTag("preview_go_btn"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Navigation, null, tint = if (ready) OnCyan else TextMuted)
                Spacer(Modifier.width(8.dp))
                Text("GO", color = if (ready) OnCyan else TextMuted, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun CandidateCard(c: RouteCandidate, selected: Boolean, single: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val route = c.route
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) ElectricCyan.copy(alpha = 0.14f) else CockpitSurfaceVariant)
            .border(if (selected) 2.dp else 1.dp, if (selected) ElectricCyan else CockpitBorder, RoundedCornerShape(16.dp))
            .clickable(enabled = route != null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (!single) Text(
            when (c.style) { RoadStyle.FASTEST -> "Fastest"; RoadStyle.CURVY -> "Curvy"; RoadStyle.VERY_CURVY -> "Super curvy"; null -> "" },
            color = if (selected) ElectricCyan else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
        when {
            route != null -> {
                val min = (route.durationSeconds / 60).toInt()
                Text("%d:%02d".format(min / 60, min % 60), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("%.0f km".format(route.distanceMeters / 1000), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                CurvinessBar(route.curviness)
                val notes = buildList {
                    if (route.unpavedMeters > 1000) add("%.0f km gravel".format(route.unpavedMeters / 1000))
                    if (route.ferryCount > 0) add(if (route.ferryCount == 1) "ferry" else "${route.ferryCount} ferries")
                }
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = NeonAmber, fontSize = 11.sp, maxLines = 1)
            }
            c.error != null -> Text(c.error, color = WarningRed, fontSize = 12.sp, maxLines = 3)
            else -> Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ElectricCyan, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Five little segments: how twisty the route is. */
@Composable
private fun CurvinessBar(curviness: Int) {
    val filled = ((curviness + 10) / 20).coerceIn(1, 5)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            Box(Modifier.size(width = 14.dp, height = 5.dp).clip(RoundedCornerShape(3.dp))
                .background(if (i < filled) NeonAmber else CockpitBorder))
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.Gesture, "Curviness", tint = TextMuted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = TextSecondary) }
}
