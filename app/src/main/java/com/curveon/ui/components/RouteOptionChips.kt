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
import androidx.compose.material.icons.filled.Straighten
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
 * The three route options, the same everywhere routes are planned: one chip cycling
 * Tarmac only → Mixed → Gravel → Offroad, and Ferries / Motorways saying in words whether they
 * are used ("Motorways OK") or avoided ("No motorways", amber, icon crossed out).
 */
@Composable
fun RouteOptionChips(
    surface: SurfacePreference,
    avoidFerries: Boolean,
    avoidMotorways: Boolean,
    onSurface: (SurfacePreference) -> Unit,
    onToggleFerries: () -> Unit,
    onToggleMotorways: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SurfaceChip(surface, onSurface)
        OptionChip(stringResource(if (avoidFerries) R.string.no_ferries else R.string.ferries_ok), active = avoidFerries, avoid = true, onClick = onToggleFerries) {
            Icon(Icons.Default.DirectionsBoat, null, tint = if (avoidFerries) NeonAmber else TextSecondary)
        }
        OptionChip(stringResource(if (avoidMotorways) R.string.no_motorways else R.string.motorways_ok), active = avoidMotorways, avoid = true, onClick = onToggleMotorways) {
            Image(painterResource(com.curveon.R.drawable.ic_motorway), null, modifier = Modifier.size(22.dp))
        }
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
        val crossed = avoid && active
        Box(
            Modifier.size(22.dp).drawWithContent {
                drawContent()
                if (crossed) drawLine(WarningRed, androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            },
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** A row of mutually exclusive choices (km/h | mph, Auto | Day | Night, …). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) ElectricCyan else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(o) }.padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label(o), color = if (on) androidx.compose.ui.graphics.Color(0xFF00363D) else TextPrimary,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
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

/** The surface chip on its own (Tarmac only → Mixed → Gravel → Offroad), as in [RouteOptionChips]. */
@Composable
fun SurfaceChip(surface: SurfacePreference, onSurface: (SurfacePreference) -> Unit) {
    val label = when (surface) {
        SurfacePreference.PAVED -> stringResource(R.string.surface_tarmac_only)
        SurfacePreference.MIXED -> stringResource(R.string.surface_mixed)
        SurfacePreference.GRAVEL -> stringResource(R.string.surface_gravel)
        SurfacePreference.OFFROAD -> stringResource(R.string.surface_offroad)
    }
    OptionChip(label, active = surface != SurfacePreference.PAVED, onClick = {
        onSurface(SurfacePreference.entries[(surface.ordinal + 1) % SurfacePreference.entries.size])
    }) {
        Icon(Icons.Default.Landscape, null, tint = if (surface != SurfacePreference.PAVED) NeonAmber else TextSecondary)
    }
}
