package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.model.TelemetryData
import com.curveon.ui.theme.*

@Composable
fun Obd2TelemetryCard(
    telemetry: TelemetryData,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CockpitSurface)
            .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp))
            .padding(14.dp)
            .testTag("obd2_telemetry_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.obd_title),
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (telemetry.isObdConnected) EcoGreen.copy(alpha = 0.2f) else WarningRed.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(if (telemetry.isObdConnected) R.string.obd_online else R.string.obd_standby),
                        color = if (telemetry.isObdConnected) EcoGreen else WarningRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4 Telemetry Metrics Grid (Coolant, Battery, Fuel, Altitude)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetricItem(
                    icon = Icons.Default.Thermostat,
                    label = stringResource(R.string.obd_coolant).uppercase(),
                    value = "${telemetry.coolantTempC}°C",
                    tint = if (telemetry.coolantTempC > 105) WarningRed else ElectricCyan
                )

                TelemetryMetricItem(
                    icon = Icons.Default.ElectricMeter,
                    label = stringResource(R.string.obd_battery).uppercase(),
                    value = "%.1fV".format(telemetry.batteryVoltage),
                    tint = if (telemetry.batteryVoltage < 12.0f) WarningRed else EcoGreen
                )

                TelemetryMetricItem(
                    icon = Icons.Default.LocalGasStation,
                    label = stringResource(R.string.poi_fuel).uppercase(),
                    value = "${telemetry.fuelPercent}%",
                    tint = if (telemetry.fuelPercent < 20) WarningRed else NeonAmber
                )

                TelemetryMetricItem(
                    icon = Icons.Default.Terrain,
                    label = stringResource(R.string.obd_altitude).uppercase(),
                    value = "${telemetry.altitudeMeters}m",
                    tint = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricItem(
    icon: ImageVector,
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            color = TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
