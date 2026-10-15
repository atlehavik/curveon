package com.curveon.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.routing.SurfacePreference
import com.curveon.ui.theme.*

/**
 * The three route options, the same everywhere routes are planned: a surface list
 * (Tarmac only, Mixed, Gravel, Offroad), and Ferries / Motorways saying in words whether they
 * are used ("Motorways OK") or avoided ("No motorways", amber, icon crossed out).
 */
@Composable
fun RouteOptionChips(
    surface: SurfacePreference,
    avoidFerries: Boolean,
    avoidMotorways: Boolean,
    avoidTrunk: Boolean = false,
    onSurface: (SurfacePreference) -> Unit,
    onToggleFerries: () -> Unit,
    /** Motorways OK / No motorways / No motorways or E-roads: (avoid motorways, avoid trunk roads). */
    onMotorways: (Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Gravel/Offroad: how far (km) off the ordinary route for gravel; null = any distance. Shown next to the surface. */
    gravelReachKm: Int? = null,
    onGravelReach: ((Int?) -> Unit)? = null
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SurfaceChip(surface, onSurface)
        if (onGravelReach != null && surface >= SurfacePreference.GRAVEL) GravelReachChip(gravelReachKm, onGravelReach)
        OptionChip(stringResource(if (avoidFerries) R.string.no_ferries else R.string.ferries_ok), active = avoidFerries, avoid = true, onClick = onToggleFerries) {
            Icon(Icons.Default.DirectionsBoat, null, tint = if (avoidFerries) NeonAmber else TextSecondary)
        }
        MotorwayChip(avoidMotorways, avoidTrunk, onMotorways)
    }
}

/**
 * A route option. Avoid-toggles, when on, cross out their icon like a no-entry sign; the label
 * says the rest ("No motorways").
 */
@Composable
fun OptionChip(
    label: String,
    active: Boolean,
    avoid: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val color = if (active) NeonAmber else TextSecondary
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (active) NeonAmber.copy(alpha = 0.2f) else CockpitSurfaceVariant)
            .border(1.dp, if (active) NeonAmber else CockpitBorder, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CrossedIcon(avoid && active, icon = icon)
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** An icon with a red slash through it, like a no-entry sign, when [crossed]. */
@Composable
fun CrossedIcon(crossed: Boolean, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Box(
        modifier.size(22.dp).drawWithContent {
            drawContent()
            if (crossed) drawLine(WarningRed, androidx.compose.ui.geometry.Offset(0f, 0f),
                androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        },
        contentAlignment = Alignment.Center
    ) { icon() }
}

/** The lengths a drawn loop can be held to; null = the way round the whole area. */
val LOOP_MAX_KM = listOf(null, 50, 80, 100, 150, 200, 300)

/** How long a drawn loop may be: "Whole area" or "Max 100 km"; tap for the list. */
@Composable
fun LoopMaxChip(maxKm: Int?, onPick: (Int?) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        OptionChip(
            if (maxKm == null) stringResource(R.string.loop_max_any) else stringResource(R.string.loop_max_km, maxKm),
            active = maxKm != null, onClick = { open = true }
        ) {
            Icon(Icons.Default.Straighten, null, tint = if (maxKm != null) NeonAmber else TextSecondary)
        }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(CockpitSurface)) {
            LOOP_MAX_KM.forEach { km ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(if (km == null) stringResource(R.string.loop_max_any) else stringResource(R.string.loop_max_km, km),
                            color = if (km == maxKm) NeonAmber else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    },
                    onClick = { open = false; onPick(km) }
                )
            }
        }
    }
}

/**
 * What an adventure can be asked to take, in minutes of estimated riding time: every half hour to
 * four hours, then every hour, up to [current] and a while beyond (a trip of many hours has many to pick from).
 */
fun adventureMinuteChoices(current: Int): List<Int> {
    val top = maxOf(480, current + 240)
    val steps = generateSequence(60) { it + if (it < 240) 30 else 60 }.takeWhile { it <= top }.toList()
    return (steps + current).distinct().sorted()
}

/**
 * The time an adventure to the trip on screen starts with: about what its twistier roads take
 * ([curvyMinutes] for Curvy, [slowestMinutes] for the slowest style) and a little more, as there
 * must be something to wander for; rounded up to the half hour.
 */
fun defaultAdventureMinutes(curvyMinutes: Double, slowestMinutes: Double): Int {
    val wanted = maxOf(slowestMinutes, curvyMinutes * 1.12)
    return ((wanted + 29) / 30).toInt() * 30
}

/** "3:00 h"; the list is open for any time, the chip shows the one picked. */
@Composable
fun adventureTimeLabel(minutes: Int) = stringResource(R.string.adventure_time_hm, minutes / 60, minutes % 60)

/** How long the adventure should take: "3:00 h", tap for the list. */
@Composable
fun AdventureTimeChip(minutes: Int, onPick: (Int) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        OptionChip(adventureTimeLabel(minutes), active = true, onClick = { open = true }) {
            Icon(Icons.Default.Schedule, null, tint = NeonAmber)
        }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(CockpitSurface)) {
            adventureMinuteChoices(minutes).forEach { m ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(adventureTimeLabel(m), color = if (m == minutes) NeonAmber else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    onClick = { open = false; onPick(m) }
                )
            }
        }
    }
}

/** The surface: tap for the list (Tarmac only, Mixed, Gravel, Offroad); the route is only worked out again once one is picked. */
@Composable
fun SurfaceChip(surface: SurfacePreference, onSurface: (SurfacePreference) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    @Composable fun label(s: SurfacePreference) = when (s) {
        SurfacePreference.PAVED -> stringResource(R.string.surface_tarmac_only)
        SurfacePreference.MIXED -> stringResource(R.string.surface_mixed)
        SurfacePreference.GRAVEL -> stringResource(R.string.surface_gravel)
        SurfacePreference.OFFROAD -> stringResource(R.string.surface_offroad)
    }
    Box {
        OptionChip(label(surface), active = surface != SurfacePreference.PAVED, onClick = { open = true }) {
            Icon(Icons.Default.Landscape, null, tint = if (surface != SurfacePreference.PAVED) NeonAmber else TextSecondary)
        }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(CockpitSurface)) {
            SurfacePreference.entries.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label(s), color = if (s == surface) NeonAmber else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    onClick = { open = false; if (s != surface) onSurface(s) }
                )
            }
        }
    }
}

/** How far off the ordinary route gravel may take the trip; null = as far as it likes. */
val GRAVEL_REACH_KM = listOf(1, 3, 5, 10, 20, null)

/** "Detour up to 5 km" / "Any detour" (to find gravel, beside the Gravel chip); tap for the list. */
@Composable
fun GravelReachChip(reachKm: Int?, onPick: (Int?) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    @Composable fun label(km: Int?) = if (km == null) stringResource(R.string.gravel_reach_any) else stringResource(R.string.gravel_reach_km, km)
    Box {
        OptionChip(label(reachKm), active = true, onClick = { open = true }) {
            Icon(Icons.Default.AltRoute, null, tint = NeonAmber)
        }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(CockpitSurface)) {
            // The question first, so the distances make sense on their own.
            Text(stringResource(R.string.gravel_reach_title), color = TextSecondary, fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp))
            GRAVEL_REACH_KM.forEach { km ->
                val item = if (km == null) stringResource(R.string.gravel_reach_item_any) else stringResource(R.string.gravel_reach_item_km, km)
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(item, color = if (km == reachKm) NeonAmber else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    onClick = { open = false; onPick(km) }
                )
            }
        }
    }
}

/** Motorways OK, No motorways, or No motorways or E-roads (trunk roads); a list, so the route is worked out once. */
@Composable
fun MotorwayChip(avoidMotorways: Boolean, avoidTrunk: Boolean, onPick: (Boolean, Boolean) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val choices = listOf(false to false, true to false, true to true)
    val current = avoidMotorways to (avoidMotorways && avoidTrunk)
    @Composable fun label(c: Pair<Boolean, Boolean>) = stringResource(when {
        c.second -> R.string.no_motorways_e_roads
        c.first -> R.string.no_motorways
        else -> R.string.motorways_ok
    })
    // Refusing E-roads also refuses motorways, so the E sign is the one crossed out then.
    @Composable fun sign(c: Pair<Boolean, Boolean>) =
        Image(painterResource(if (c.second) com.curveon.R.drawable.ic_eroad else com.curveon.R.drawable.ic_motorway), null, modifier = Modifier.size(22.dp))
    Box {
        OptionChip(label(current), active = avoidMotorways, avoid = true, onClick = { open = true }) { sign(current) }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(CockpitSurface)) {
            choices.forEach { c ->
                androidx.compose.material3.DropdownMenuItem(
                    leadingIcon = { CrossedIcon(c.first) { sign(c) } },
                    text = { Text(label(c), color = if (c == current) NeonAmber else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    onClick = { open = false; if (c != current) onPick(c.first, c.second) }
                )
            }
        }
    }
}
