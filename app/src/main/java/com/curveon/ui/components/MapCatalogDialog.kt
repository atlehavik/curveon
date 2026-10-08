package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.map.CatalogArea
import com.curveon.data.map.InstalledMap
import com.curveon.data.map.MapCatalog
import com.curveon.data.map.MapDownloadState
import com.curveon.data.map.MapDownloads
import com.curveon.ui.theme.*
import java.text.Collator

private val OnCyan = Color(0xFF00363D)

/**
 * Browse continents and download any country, like DMD2. Opens on the rider's own continent.
 * Several countries can be picked in a row; they download one after another.
 */
@Composable
fun MapCatalogDialog(
    catalog: MapCatalog,
    installed: List<InstalledMap>,
    download: MapDownloadState,
    queue: List<String>,
    freeBytes: Long,
    /** Size of downloading an area now (routing tiles already on the tablet don't count). */
    downloadBytes: (CatalogArea) -> Long,
    startContinent: String,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var continent by rememberSaveable { mutableStateOf(startContinent) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val locale = LocalConfiguration.current.locales[0]
    val collator = remember(locale) { Collator.getInstance(locale) }
    val rows = remember(continent, expanded, locale) {
        catalog.countries(continent).sortedWith(compareBy(collator) { it.displayName(locale) }).flatMap { c ->
            listOf(c to 0) + if (c.id in expanded) catalog.children(c.id).sortedWith(compareBy(collator) { it.name }).map { it to 1 } else emptyList()
        }
    }
    val installedIds = installed.map { it.id }.toSet()

    CockpitDialog(
        title = stringResource(R.string.maps_title), icon = Icons.Default.Public, accent = ElectricCyan, onDismiss = onDismiss,
        tag = "map_catalog_dialog", subtitle = stringResource(R.string.maps_free, freeBytes / 1e9)
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(210.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(CockpitBackground.copy(alpha = 0.6f))
                    .verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MapCatalog.CONTINENTS.forEach { c ->
                    val on = c == continent
                    Text(
                        stringResource(MapCatalog.continentName(c)), color = if (on) OnCyan else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) ElectricCyan else Color.Transparent)
                            .clickable { continent = c }.padding(horizontal = 14.dp, vertical = 14.dp)
                    )
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.first.id }) { (area, indent) ->
                    val split = catalog.isSplit(area)
                    AreaRow(
                        area = area, indent = indent, split = split, open = area.id in expanded,
                        parts = if (split) catalog.children(area.id).size else 0,
                        installed = area.id in installedIds,
                        running = (download as? MapDownloadState.Running)?.takeIf { it.areaId == area.id },
                        queued = area.id in queue,
                        bytes = downloadBytes(area),
                        onToggle = { expanded = if (area.id in expanded) expanded - area.id else expanded + area.id },
                        onDownload = { onDownload(area.id) },
                        onCancel = { onCancel(area.id) }
                    )
                }
                item { Text(stringResource(R.string.maps_hint), color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun AreaRow(
    area: CatalogArea, indent: Int, split: Boolean, open: Boolean, parts: Int, installed: Boolean,
    running: MapDownloadState.Running?, queued: Boolean, bytes: Long,
    onToggle: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val whole = !split || area.mapBytes <= MapDownloads.MAX_WHOLE_BYTES
    Column(
        Modifier.fillMaxWidth().padding(start = (indent * 28).dp).clip(RoundedCornerShape(14.dp))
            .background(if (indent > 0) CockpitSurfaceVariant.copy(alpha = 0.6f) else CockpitSurfaceVariant)
            .then(if (split) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (indent > 0) area.name else area.displayName(locale), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        split && !whole -> pluralStringResource(R.plurals.maps_parts, parts, parts) + " · " + stringResource(R.string.maps_too_big)
                        installed -> stringResource(R.string.maps_installed)
                        bytes < 1e9 -> stringResource(R.string.maps_about_mb, maxOf(1.0, bytes / 1e6))
                        else -> stringResource(R.string.maps_about, bytes / 1e9)
                    },
                    color = TextSecondary, fontSize = 13.sp
                )
            }
            when {
                running != null || queued -> {
                    if (running == null) Text(stringResource(R.string.maps_waiting), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = WarningRed) }
                }
                installed -> Icon(Icons.Default.CheckCircle, stringResource(R.string.maps_installed), tint = ElectricCyan, modifier = Modifier.size(28.dp))
                whole -> Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                    Text(stringResource(R.string.download), color = OnCyan, fontWeight = FontWeight.Bold)
                }
            }
            if (split) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecondary, modifier = Modifier.padding(start = 8.dp))
        }
        if (running != null) DownloadProgress(running)
    }
}

@Composable
internal fun DownloadProgress(running: MapDownloadState.Running) {
    LinearProgressIndicator(progress = { running.fraction }, color = ElectricCyan, trackColor = CockpitBorder,
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
    Text(running.message, color = TextSecondary, fontSize = 12.sp)
}
