package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.curveon.data.model.GeoPoint
import com.curveon.data.search.SearchResult
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable

/** "Where to?": offline place/address/POI search, with shortcuts to roundtrips and GPX tracks. */
@Composable
fun DestinationSearchDialog(
    searchStatus: String,
    onSearch: suspend (String) -> List<SearchResult>,
    onPick: (SearchResult) -> Unit,
    onRoundtrip: () -> Unit,
    onGpx: () -> Unit,
    onDismiss: () -> Unit,
    savedRoutes: List<com.curveon.data.model.RouteItem> = emptyList(),
    recents: List<com.curveon.data.routing.Stop> = emptyList(),
    onPickSaved: (com.curveon.data.model.RouteItem) -> Unit = {},
    onDeleteSaved: (com.curveon.data.model.RouteItem) -> Unit = {},
    onNearestFuel: (suspend () -> List<SearchResult>)? = null
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var showingFuel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<com.curveon.data.model.RouteItem?>(null) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.isNotEmpty()) showingFuel = false
        if (query.trim().length < 2) { if (!showingFuel) results = emptyList(); return@LaunchedEffect }
        delay(250) // debounce typing
        searching = true
        results = onSearch(query)
        searching = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(24.dp))
                .padding(18.dp)
                .testTag("destination_search_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Where to? Town, address, fuel, viewpoint…") },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = ElectricCyan) },
                    trailingIcon = {
                        if (searching) CircularProgressIndicator(Modifier.size(20.dp), color = ElectricCyan, strokeWidth = 2.dp)
                        else if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear", tint = TextSecondary) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricCyan, unfocusedBorderColor = CockpitBorder, cursorColor = ElectricCyan
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus).testTag("destination_search_field")
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = TextSecondary) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Shortcut(Icons.Default.Explore, "Roundtrip", NeonAmber, onRoundtrip)
                Shortcut(Icons.Default.Route, "GPX tracks", ElectricCyan, onGpx)
                if (onNearestFuel != null) Shortcut(Icons.Default.LocalGasStation, "Nearest fuel", EcoGreen) {
                    query = ""
                    showingFuel = true
                    scope.launch { searching = true; results = onNearestFuel(); searching = false }
                }
                Spacer(Modifier.weight(1f))
                Text(searchStatus, color = TextMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically).widthIn(max = 420.dp))
            }

            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Nothing typed: what you are most likely to want, without typing on the bike.
                if (query.isEmpty() && !showingFuel) {
                    if (savedRoutes.isNotEmpty()) item { SectionLabel("Saved routes") }
                    items(savedRoutes, key = { "saved${it.id}" }) { item ->
                        ListRow(
                            icon = if (item.isRoundtrip) Icons.Default.Loop else Icons.Default.Bookmark,
                            title = item.title,
                            subtitle = listOf(item.subtitle, "%.0f km".format(item.totalDistanceKm), "%d:%02d".format(item.estimatedMinutes / 60, item.estimatedMinutes % 60))
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            trailing = null,
                            onClick = { onPickSaved(item) },
                            onLongClick = { confirmDelete = item }
                        )
                    }
                    if (recents.isNotEmpty()) item { SectionLabel("Recent") }
                    items(recents, key = { "recent${it.name}${it.point.lat}" }) { stop ->
                        ListRow(Icons.Default.History, stop.name, "", null, onClick = {
                            onPick(SearchResult(stop.name, "", "recent", stop.point.lat, stop.point.lon, 0.0))
                        })
                    }
                }
                items(results) { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CockpitSurfaceVariant)
                            .clickable { onPick(r) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(kindIcon(r.kind), null, tint = ElectricCyan, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(r.area, kindLabel(r.kind)).filter { it.isNotEmpty() }.joinToString(" · ") + if (r.online) " · online" else "",
                                color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(formatKm(r.distanceKm), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (results.isEmpty() && query.trim().length >= 2 && !searching) {
                    item { Text("No matches", color = TextMuted, modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${item.title}?") },
            confirmButton = { TextButton(onClick = { onDeleteSaved(item); confirmDelete = null }) { Text("Delete", color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.15f))
            .border(1.dp, tint, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ListRow(icon: ImageVector, title: String, subtitle: String, trailing: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitSurfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = ElectricCyan, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatKm(km: Double) = if (km < 10) "%.1f km".format(km) else "%.0f km".format(km)

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "city", "town", "village", "suburb", "borough", "quarter", "hamlet", "neighbourhood", "locality", "isolated_dwelling" -> Icons.Default.LocationCity
    "island" -> Icons.Default.Landscape
    "street" -> Icons.Default.Signpost
    "fuel" -> Icons.Default.LocalGasStation
    "charging" -> Icons.Default.EvStation
    "cafe" -> Icons.Default.LocalCafe
    "restaurant", "fast_food", "pub" -> Icons.Default.Restaurant
    "ferry" -> Icons.Default.DirectionsBoat
    "parking" -> Icons.Default.LocalParking
    "viewpoint" -> Icons.Default.Visibility
    "camp_site" -> Icons.Default.Cabin
    "hotel", "hut" -> Icons.Default.Hotel
    "attraction", "museum" -> Icons.Default.Attractions
    "motorcycle_shop" -> Icons.Default.TwoWheeler
    "supermarket", "shop" -> Icons.Default.ShoppingCart
    "peak", "pass" -> Icons.Default.Terrain
    else -> Icons.Default.Place
}

private fun kindLabel(kind: String): String = when (kind) {
    "street" -> "Street"
    "fuel" -> "Fuel"
    "ferry" -> "Ferry terminal"
    "viewpoint" -> "Viewpoint"
    "camp_site" -> "Camping"
    "pass" -> "Mountain pass"
    "peak" -> "Peak"
    "motorcycle_shop" -> "Motorcycle shop"
    "fast_food" -> "Fast food"
    else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
