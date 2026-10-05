package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TelemetryData
import com.example.ui.theme.*

@Composable
fun SpeedometerTachometer(
    telemetry: TelemetryData,
    onToggleUnit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = telemetry.displaySpeed.toFloat(),
        animationSpec = tween(durationMillis = 300),
        label = "speed"
    )

    val animatedRpmFraction by animateFloatAsState(
        targetValue = (telemetry.rpm.toFloat() / telemetry.maxRpm.toFloat()).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "rpm"
    )

    val isOverLimit = telemetry.isOverSpeedLimit
    val speedColor by animateColorAsState(
        targetValue = if (isOverLimit) WarningRed else TextPrimary,
        label = "speed_color"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitSurface)
            .border(1.dp, CockpitBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("speedometer_tachometer_card")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. Sleek Tachometer Horizontal Indicator Line (No top text header)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
            ) {
                val w = size.width
                val h = size.height
                val corner = CornerRadius(h / 2f, h / 2f)

                // Track Background
                drawRoundRect(
                    color = CockpitSurfaceVariant,
                    size = Size(w, h),
                    cornerRadius = corner
                )

                // Active RPM Line
                val activeW = w * animatedRpmFraction
                val gradient = Brush.horizontalGradient(
                    colors = listOf(
                        ElectricCyan,
                        EcoGreen,
                        NeonAmber,
                        WarningRed
                    )
                )
                drawRoundRect(
                    brush = gradient,
                    size = Size(activeW, h),
                    cornerRadius = corner
                )
            }

            // 2. Main Speedometer Row with RPM Number directly below indicator line
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Speed Digits & Speed Limit Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onToggleUnit() }
                        .padding(vertical = 1.dp)
                ) {
                    // Digital Speed Number
                    Text(
                        text = animatedSpeed.toInt().toString(),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = speedColor,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = (-1).sp,
                        lineHeight = 36.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = telemetry.speedUnit,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOverLimit) WarningRed else TextMuted
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Round Speed Limit Sign
                    if (telemetry.speedLimitKmh > 0) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(SpeedLimitWhite)
                                .border(3.dp, SpeedLimitBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = telemetry.displaySpeedLimit.toString(),
                                color = Color.Black,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                // Right: RPM Number (below indicator line, lean angle removed) & Trip
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // RPM Number only (no "RPM" label)
                    Text(
                        text = telemetry.rpm.toString(),
                        color = if (telemetry.rpm > telemetry.redlineRpm) WarningRed else ElectricCyan,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )

                    // Trip Odometer
                    Text(
                        text = "TRIP %.1f km • %dm".format(telemetry.tripDistanceKm, telemetry.tripDurationMinutes),
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
