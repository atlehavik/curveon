package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.curveon.data.weather.PreviewWeather
import com.curveon.data.weather.TripWeather
import com.curveon.data.weather.WeatherVia
import com.curveon.ui.theme.*
import kotlin.math.roundToInt

private fun clock(time: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))

/** "Dry all the way · 9–13°". */
@Composable
private fun dryAllTheWay(w: TripWeather): String =
    stringResource(R.string.weather_dry_temps, (w.minTemp ?: 0.0).roundToInt(), (w.maxTemp ?: 0.0).roundToInt())

private fun TripWeather.tint(): Color = when {
    firstWet != null -> SharingBlue
    firstWindy != null -> NeonAmber
    else -> EcoGreen
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
                trouble == null -> dryAllTheWay(trip)
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
