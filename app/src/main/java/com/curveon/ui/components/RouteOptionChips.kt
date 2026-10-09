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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.routing.SurfacePreference
import com.curveon.ui.theme.*

/**
 * The three route options, the same everywhere routes are planned: one chip cycling
 * Tarmac → Mixed → Gravel → Offroad, and Ferries / Motorways struck through when avoided.
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
        val surfaceLabel = when (surface) {
            SurfacePreference.PAVED -> stringResource(R.string.surface_tarmac)
            SurfacePreference.MIXED -> stringResource(R.string.surface_mixed)
            SurfacePreference.GRAVEL -> stringResource(R.string.surface_gravel)
            SurfacePreference.OFFROAD -> stringResource(R.string.surface_offroad)
        }
        OptionChip(surfaceLabel, active = surface != SurfacePreference.PAVED, onClick = {
            onSurface(SurfacePreference.entries[(surface.ordinal + 1) % SurfacePreference.entries.size])
        }) {
            Icon(Icons.Default.Landscape, null, tint = if (surface != SurfacePreference.PAVED) NeonAmber else TextSecondary)
        }
        OptionChip(stringResource(R.string.ferries), active = avoidFerries, avoid = true, onClick = onToggleFerries) {
            Icon(Icons.Default.DirectionsBoat, null, tint = if (avoidFerries) NeonAmber else TextSecondary)
        }
        OptionChip(stringResource(R.string.motorways), active = avoidMotorways, avoid = true, onClick = onToggleMotorways) {
            Image(painterResource(com.curveon.R.drawable.ic_motorway), null, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * A route option. Avoid-toggles show their label struck through when on ("~~Motorways~~" = no
 * motorways), which reads at a glance without extra words.
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
        Box(Modifier.size(22.dp).graphicsLayer { alpha = if (avoid && active) 0.5f else 1f }, contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(6.dp))
        Text(
            label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            textDecoration = if (avoid && active) TextDecoration.LineThrough else null
        )
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
