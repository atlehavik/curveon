package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.obd.ObdState
import com.curveon.data.obd.ObdValue
import com.curveon.ui.theme.*

/**
 * The engine panel on the dashboard: the readings the rider picked in Settings (fuel and RPM to
 * begin with), two side by side, each with a bar. While the adapter isn't talking, the values show
 * "–" and a line says why. Tap to open the engine settings.
 */
@Composable
fun EnginePanel(state: ObdState, values: List<ObdValue>, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CockpitSurface)
            .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onOpenSettings)
            .padding(14.dp)
            .testTag("engine_panel"),
        // Given more height than it needs (a phone held upright): centred.
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        val live = state.status == ObdState.Status.CONNECTED
        if (!live) Text(
            stringResource(when (state.status) {
                ObdState.Status.OFF -> R.string.obd_choose_adapter
                ObdState.Status.NO_ADAPTER -> R.string.obd_bluetooth_off
                ObdState.Status.CONNECTING -> R.string.obd_connecting
                else -> R.string.obd_not_answering
            }),
            color = NeonAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2
        )
        values.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { v -> Gauge(v, state.takeIf { live }, Modifier.weight(1f)) }
                if (row.size == 1 && values.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Gauge(v: ObdValue, s: ObdState?, modifier: Modifier) {
    val (icon, label) = when (v) {
        ObdValue.FUEL -> Icons.Default.LocalGasStation to R.string.poi_fuel
        ObdValue.RPM -> Icons.Default.Speed to R.string.obd_rpm
        ObdValue.COOLANT -> Icons.Default.Thermostat to R.string.obd_coolant
        ObdValue.VOLTAGE -> Icons.Default.ElectricMeter to R.string.obd_battery
    }
    // Text, share of the bar (null = no bar) and whether it's a warning.
    val (text, share, warn) = when (v) {
        ObdValue.FUEL -> Triple(s?.fuelLiters?.let { "%.1f L".format(it) } ?: s?.fuelPercent?.let { "$it %" }, s?.fuelPercent?.div(100f), (s?.fuelPercent ?: 100) < 20)
        ObdValue.RPM -> Triple(s?.rpm?.toString(), s?.rpm?.div(MAX_RPM), (s?.rpm ?: 0) > REDLINE_RPM)
        ObdValue.COOLANT -> Triple(s?.coolantC?.let { "$it °C" }, null, (s?.coolantC ?: 0) > 105)
        ObdValue.VOLTAGE -> Triple(s?.voltage?.let { "%.1f V".format(it) }, null, (s?.voltage ?: 13f) < 12f)
    }
    val tint = if (warn) WarningRed else when (v) { ObdValue.FUEL -> NeonAmber; ObdValue.RPM -> ElectricCyan; else -> TextSecondary }
    Column(modifier.testTag("engine_${v.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(label), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(text ?: "–", color = if (warn) WarningRed else TextPrimary, fontSize = 30.sp, lineHeight = 30.sp,
            fontWeight = FontWeight.Black, maxLines = 1)
        if (v == ObdValue.FUEL || v == ObdValue.RPM) Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(CockpitSurfaceVariant)
        ) {
            if (share != null) Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().background(tint))
        }
    }
}

private const val MAX_RPM = 12_000f
private const val REDLINE_RPM = 10_000
