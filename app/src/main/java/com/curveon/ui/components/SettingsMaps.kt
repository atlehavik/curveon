package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.map.InstalledMap
import com.curveon.data.map.MapDownloadState
import com.curveon.ui.theme.*
import androidx.compose.ui.graphics.Color

private val OnCyan = Color(0xFF00363D)

@Composable
internal fun InstalledMap.displayName() = area?.displayName(LocalConfiguration.current.locales[0]) ?: file.name

@Composable
internal fun MapRow(m: InstalledMap, download: MapDownloadState, queue: List<String>, actions: SettingsActions, onDelete: () -> Unit) {
    val running = download.runningFor(m.id)
    val busy = running != null || m.id in queue
    val missing = m.missingRouting.isNotEmpty() || m.missingSearch
    val warn = m.newerBuild != null || missing
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.displayName(), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        if (m.bytes < 1e9) stringResource(R.string.size_mb, maxOf(1.0, m.bytes / 1e6)) else stringResource(R.string.size_gb, m.bytes / 1e9),
                        if (m.area == null) stringResource(R.string.maps_by_hand) else m.build?.let { stringResource(R.string.settings_map_from, it) },
                        m.newerBuild?.let { stringResource(R.string.settings_map_newer, it) },
                        stringResource(R.string.maps_routing_missing).takeIf { m.missingRouting.isNotEmpty() },
                        when {
                            m.area == null -> null
                            m.search == com.curveon.data.search.SearchIndexes.Source.NONE -> stringResource(R.string.maps_search_missing)
                            m.betterSearch -> stringResource(R.string.maps_search_better)
                            m.search == com.curveon.data.search.SearchIndexes.Source.MAP -> stringResource(R.string.maps_search_basic)
                            else -> null
                        }
                    ).joinToString(" · "),
                    color = if (warn) NeonAmber else TextSecondary, fontSize = 13.sp
                )
            }
            if (busy) {
                if (running == null) Text(stringResource(R.string.maps_waiting), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                OutlinedButton(onClick = { actions.onCancelMapDownload(m.id) }) { Text(stringResource(R.string.cancel), color = WarningRed) }
            } else {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = TextMuted) }
                if (m.area != null) {
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { actions.onDownloadMap(m.id) }, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                        Text(stringResource(if (m.newerBuild == null && missing) R.string.maps_get_missing else R.string.update),
                            color = OnCyan, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (running != null) DownloadProgress(running)
    }
}

/** A queued or running download of a country that isn't on the tablet yet. */
@Composable
internal fun PendingRow(id: String, name: String, download: MapDownloadState, actions: SettingsActions) {
    val running = download.runningFor(id)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (running == null) Text(stringResource(R.string.maps_waiting), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            OutlinedButton(onClick = { actions.onCancelMapDownload(id) }) { Text(stringResource(R.string.cancel), color = WarningRed) }
        }
        if (running != null) DownloadProgress(running)
    }
}
