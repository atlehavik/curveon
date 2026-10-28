package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.weather.DirectionWeather
import com.curveon.data.weather.GoodWeather
import com.curveon.data.weather.PreviewWeather
import com.curveon.data.weather.TripWeather
import com.curveon.data.weather.WeatherController
import com.curveon.data.weather.WeatherVia
import com.curveon.ui.theme.*
import kotlin.math.roundToInt

private fun clock(time: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))

/** "Dry all the way · 9–13°", "Rain from about 14:00", "Showers here and there", "Strong gusts". */
@Composable
private fun summary(w: TripWeather): String {
    val wet = w.firstWet
    return when {
        wet != null && w.wetShare < 0.34 -> stringResource(R.string.weather_some_rain)
        wet != null -> stringResource(R.string.weather_rain_from, clock(wet.at.time))
        w.firstWindy != null -> stringResource(R.string.weather_gusts)
        else -> stringResource(R.string.weather_dry_temps, (w.minTemp ?: 0.0).roundToInt(), (w.maxTemp ?: 0.0).roundToInt())
    }
}

private fun TripWeather.tint(): Color = when {
    firstWet != null -> SharingBlue
    firstWindy != null -> NeonAmber
    else -> EcoGreen
}

/**
 * "Good weather today" in the empty Where to?: pick how long, see the best directions for a loop, tap one to plan it.
 * Only there when a forecast can be had; nothing else in Where to? changes.
 */
@Composable
fun GoodWeatherCard(state: GoodWeather, onHours: (Int) -> Unit, onPick: (DirectionWeather) -> Unit) {
    val names = stringResource(R.string.weather_directions).split(",")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(EcoGreen.copy(alpha = 0.08f))
            .border(1.dp, EcoGreen.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("search_good_weather"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val lengths: @Composable (Modifier) -> Unit = { m ->
            Row(m.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WeatherController.HOURS.forEach { h ->
                    val on = h == state.hours
                    Text(
                        if (h == WeatherController.HOURS.last()) stringResource(R.string.weather_whole_day) else stringResource(R.string.weather_hours, h),
                        color = if (on) CockpitBackground else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) EcoGreen else CockpitSurfaceVariant)
                            .clickable { onHours(h) }.padding(horizontal = 10.dp, vertical = 6.dp).testTag("good_weather_hours_$h")
                    )
                }
            }
        }
        // Side by side on the tablet; a phone upright gets the lengths on a line of their own, so the title fits.
        val narrow = isNarrowScreen()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WbSunny, null, tint = NeonAmber, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.weather_good_today), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!narrow) { Spacer(Modifier.width(10.dp)); lengths(Modifier) }
        }
        if (narrow) lengths(Modifier)
        state.directions.forEachIndexed { i, d ->
            val name = names.getOrNull(((d.directionDeg / 45).roundToInt()) % 8) ?: ""
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CockpitSurfaceVariant).clickable { onPick(d) }
                    .padding(horizontal = 12.dp, vertical = 9.dp).testTag("good_weather_dir_$i"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (d.weather.firstWet != null) Icons.Default.Umbrella else Icons.Default.WbSunny, null, tint = d.weather.tint(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Text(summary(d.weather), color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                state.loading -> {
                    CircularProgressIndicator(color = EcoGreen, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.weather_looking), color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
                state.failed -> Text(stringResource(R.string.weather_none), color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                else -> Spacer(Modifier.weight(1f))
            }
            Text(stringResource(R.string.weather_credit), color = TextMuted, fontSize = 10.sp)
        }
    }
}

/**
 * One line under the alternatives in the preview: where it rains and when, or that it stays dry; and when it rains, the
 * one thing that helps (a way round, or leaving later), to take with one tap. The route never changes by itself.
 */
@Composable
fun PreviewWeatherLine(w: PreviewWeather, onVia: (WeatherVia) -> Unit) {
    val trip = w.trip
    val trouble = trip.firstWet ?: trip.firstWindy
    val tint = trip.tint()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("preview_weather"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when { trip.firstWet != null -> Icons.Default.Umbrella; trip.firstWindy != null -> Icons.Default.Air; else -> Icons.Default.WbSunny },
                null, tint = if (trouble == null) NeonAmber else tint, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            val text = when {
                trouble == null -> summary(trip)
                else -> {
                    val wet = trouble === trip.firstWet
                    val at = clock(trouble.at.time)
                    if (w.place != null) stringResource(if (wet) R.string.weather_rain_at else R.string.weather_wind_at, w.place, at)
                    else stringResource(if (wet) R.string.weather_rain_after else R.string.weather_wind_after,
                        stringResource(R.string.distance_km_whole, trouble.at.metersFromStart / 1000), at)
                }
            }
            Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(w.forecastFrom?.let { stringResource(R.string.weather_forecast_from, clock(it)) }, stringResource(R.string.weather_credit)).joinToString(" · "),
                color = TextMuted, fontSize = 10.sp, maxLines = 1
            )
        }
        val via = w.via
        when {
            via != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (via.dry) R.string.weather_via_dry else R.string.weather_via_drier, via.stop.name, (via.extraSeconds / 60).roundToInt().coerceAtLeast(1)),
                    color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.weather_use), color = CockpitBackground, fontSize = 13.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(EcoGreen).clickable { onVia(via) }
                        .padding(horizontal = 14.dp, vertical = 6.dp).testTag("preview_weather_via")
                )
            }
            w.drierStart != null -> Text(stringResource(R.string.weather_start_later, clock(w.drierStart)), color = TextPrimary, fontSize = 13.sp, maxLines = 1)
            w.searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.weather_searching), color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

/** On an alternative's card: "Rain" or "Dry", when the alternatives differ in it (otherwise the line below says it all). */
@Composable
fun candidateWeatherNote(all: List<TripWeather?>, index: Int): Pair<String, Color>? {
    val known = all.filterNotNull()
    if (known.size < 2) return null
    val mine = all.getOrNull(index) ?: return null
    val differ = known.any { it.firstWet == null } && known.any { it.firstWet != null }
    if (!differ) return null
    return if (mine.firstWet == null) stringResource(R.string.weather_note_dry) to EcoGreen else stringResource(R.string.weather_note_rain) to SharingBlue
}
