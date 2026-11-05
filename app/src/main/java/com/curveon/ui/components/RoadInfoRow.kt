@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.ui.theme.*

/** The road under a held finger: its speed limit sign, name, class and surface. Information only. */
@Composable
fun RoadInfoRow(road: com.curveon.data.routing.RoadInfo, useMph: Boolean = false) {
    val kind = road.highway?.let { roadKind(it) }
    val surface = roadSurface(road)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("road_info")) {
        road.speedKmh?.let { SpeedLimitSign(if (useMph) com.curveon.data.model.TelemetryData.mphLimit(it) else it, 44.dp) }
        road.ref?.let { RoadShield(it, 30.dp) }
        if (road.priority) PriorityRoadSign(34.dp)
        Column {
            Text(road.name ?: kind ?: "", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val details = listOfNotNull(kind.takeIf { road.name != null }, surface).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, color = if (road.unpaved) NeonAmber else TextSecondary, fontSize = 14.sp, maxLines = 1)
            if (road.speedKmh != null) Text(
                stringResource(if (road.officialSpeed) R.string.road_speed_official else R.string.road_speed_osm),
                color = TextMuted, fontSize = 12.sp, maxLines = 1
            )
            if (road.needsMap) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("road_needs_map")) {
                Icon(Icons.Default.Download, null, tint = NeonAmber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (road.mapArea != null) stringResource(R.string.road_needs_map_for, road.mapArea) else stringResource(R.string.road_needs_map),
                    color = NeonAmber, fontSize = 13.sp, maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun roadKind(highway: String): String? = when (highway.removeSuffix("_link")) {
    "motorway" -> stringResource(R.string.road_kind_motorway)
    "trunk", "primary" -> stringResource(R.string.road_kind_main)
    "secondary", "tertiary", "unclassified" -> stringResource(R.string.road_kind_country)
    "residential", "living_street" -> stringResource(R.string.road_kind_residential)
    "service" -> stringResource(R.string.road_kind_service)
    "track" -> stringResource(R.string.road_kind_track)
    "path", "footway", "cycleway", "bridleway", "pedestrian" -> stringResource(R.string.road_kind_path)
    else -> null
}

@Composable
private fun roadSurface(road: com.curveon.data.routing.RoadInfo): String? = when (road.surface) {
    "asphalt", "paved", "concrete", "concrete:plates", "paving_stones", "chipseal" -> stringResource(R.string.road_surface_asphalt)
    "sett", "cobblestone", "unhewn_cobblestone" -> stringResource(R.string.road_surface_cobbles)
    "gravel", "fine_gravel", "compacted", "pebblestone", "unpaved" -> stringResource(R.string.road_surface_gravel)
    "dirt", "ground", "earth", "soil", "mud", "sand", "grass", "clay" -> stringResource(R.string.road_surface_dirt)
    null -> when {
        road.highway == null -> null
        road.highway == "track" -> stringResource(R.string.road_surface_unknown_track)
        else -> stringResource(R.string.road_surface_unknown)
    }
    else -> road.surface
}?.let { s -> road.tracktype?.removePrefix("grade")?.let { g -> stringResource(R.string.road_track_grade, s, g) } ?: s }
