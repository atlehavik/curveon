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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.model.GeoPoint
import com.curveon.data.search.SearchResult
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable

/** What a search is for: where to go, a stop on the way, or where a planned trip starts. */
enum class SearchPurpose { DESTINATION, STOP, START }

/** Search result kind for "from my position" in [SearchPurpose.START]. */
const val KIND_HERE = "here"

/** "Where to?": offline place/address/POI search, with one-tap "nearest fuel / camping / hotel / dining". */
@Composable
fun DestinationSearchDialog(
    searchStatus: String,
    onSearch: suspend (String) -> List<SearchResult>,
    onPick: (SearchResult) -> Unit,
    onDismiss: () -> Unit,
    savedRoutes: List<com.curveon.data.model.RouteItem> = emptyList(),
    recents: List<com.curveon.data.routing.Stop> = emptyList(),
    onPickSaved: (com.curveon.data.model.RouteItem) -> Unit = {},
    onDeleteSaved: (com.curveon.data.model.RouteItem) -> Unit = {},
    onNearest: (suspend (kinds: List<String>) -> List<SearchResult>)? = null,
    purpose: SearchPurpose = SearchPurpose.DESTINATION,
    /** "Pick on map": the dialog shrinks onto the map and this is called once it's gone. */
    onPickOnMap: (() -> Unit)? = null,
    /** Coming back from picking on the map: grow out of the map instead of just appearing. */
    growIn: Boolean = false
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var nearest by remember { mutableStateOf<NearestCategory?>(null) }
    var confirmDelete by remember { mutableStateOf<com.curveon.data.model.RouteItem?>(null) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.isNotEmpty()) nearest = null
        if (query.trim().length < 2) { if (nearest == null) results = emptyList(); return@LaunchedEffect }
        delay(250) // debounce typing
        searching = true
        results = onSearch(query)
        searching = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // 1 = open, 0 = shrunk into the "pick on map" bar at the bottom of the map.
    val size = remember { androidx.compose.animation.core.Animatable(if (growIn) 0f else 1f) }
    LaunchedEffect(Unit) { if (growIn) size.animateTo(1f, androidx.compose.animation.core.tween(260)) }
    val minimize: () -> Unit = {
        scope.launch {
            size.animateTo(0f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            onPickOnMap?.invoke()
        }
    }

    CockpitDialog(
        title = stringResource(R.string.where_to), icon = Icons.Default.Search, accent = ElectricCyan, onDismiss = onDismiss,
        tag = "destination_search_dialog",
        frameModifier = Modifier.graphicsLayer {
            scaleX = 0.2f + 0.8f * size.value
            scaleY = 0.08f + 0.92f * size.value
            alpha = (size.value * 1.6f).coerceAtMost(1f)
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
        header = {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(when (purpose) {
                        SearchPurpose.DESTINATION -> stringResource(R.string.search_placeholder)
                        SearchPurpose.STOP -> stringResource(R.string.search_add_stop)
                        SearchPurpose.START -> stringResource(R.string.search_start_from)
                    })
                },
                leadingIcon = {
                    when (purpose) {
                        SearchPurpose.DESTINATION -> Icon(Icons.Default.Search, null, tint = ElectricCyan)
                        SearchPurpose.STOP -> Icon(Icons.Default.AddLocationAlt, null, tint = NeonAmber)
                        SearchPurpose.START -> Icon(Icons.Default.TripOrigin, null, tint = ElectricCyan)
                    }
                },
                trailingIcon = {
                    if (searching) CircularProgressIndicator(Modifier.size(20.dp), color = ElectricCyan, strokeWidth = 2.dp)
                    else if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.clear), tint = TextSecondary) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = ElectricCyan, unfocusedBorderColor = CockpitBorder, cursorColor = ElectricCyan
                ),
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("destination_search_field")
            )
        }
    ) {
        // The window's dim fades with the frame, so the map shows through as it shrinks.
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        LaunchedEffect(window) {
            val w = window ?: return@LaunchedEffect
            val dim = w.attributes.dimAmount
            snapshotFlow { size.value }.collect { w.setDimAmount(dim * it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onNearest != null) {
                Text(stringResource(R.string.search_nearest).uppercase(), color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                NearestCategory.entries.forEach { cat ->
                    Shortcut(cat.icon, stringResource(cat.label), cat.tint, selected = nearest == cat) {
                        query = ""
                        nearest = cat
                        scope.launch { searching = true; results = onNearest(cat.kinds); searching = false }
                    }
                }
            }
            Text(searchStatus, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onPickOnMap != null) Box(Modifier.testTag("search_pick_on_map")) {
                Shortcut(Icons.Default.Map, stringResource(R.string.pick_on_map), if (purpose == SearchPurpose.STOP) NeonAmber else ElectricCyan, onClick = minimize)
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Nothing typed: what you are most likely to want, without typing on the bike.
            if (query.isEmpty() && nearest == null) {
                if (purpose == SearchPurpose.START) item {
                    val here = stringResource(R.string.from_here)
                    ListRow(Icons.Default.MyLocation, here, "", null, onClick = { onPick(SearchResult(here, "", KIND_HERE, 0.0, 0.0, 0.0)) })
                }
                val saved = if (purpose == SearchPurpose.DESTINATION) savedRoutes else emptyList()
                if (saved.isNotEmpty()) item { SectionLabel(stringResource(R.string.search_saved_routes)) }
                items(saved, key = { "saved${it.id}" }) { item ->
                    ListRow(
                        icon = if (item.isRoundtrip) Icons.Default.Loop else Icons.Default.Bookmark,
                        title = item.title,
                        subtitle = listOf(savedSubtitle(item.subtitle), stringResource(R.string.distance_km_whole, item.totalDistanceKm), "%d:%02d".format(item.estimatedMinutes / 60, item.estimatedMinutes % 60))
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        trailing = null,
                        onClick = { onPickSaved(item) },
                        onLongClick = { confirmDelete = item }
                    )
                }
                if (recents.isNotEmpty()) item { SectionLabel(stringResource(R.string.search_recent)) }
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
                            listOf(r.area, kindLabel(r.kind)).filter { it.isNotEmpty() }.joinToString(" · ") + if (r.online) " · " + stringResource(R.string.search_online) else "",
                            color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(formatKm(r.distanceKm), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (results.isEmpty() && query.trim().length >= 2 && !searching) {
                item { Text(stringResource(R.string.search_no_matches), color = TextMuted, modifier = Modifier.padding(12.dp)) }
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_named, item.title)) },
            confirmButton = { TextButton(onClick = { onDeleteSaved(item); confirmDelete = null }) { Text(stringResource(R.string.delete), color = WarningRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, tint: Color, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = if (selected) 0.35f else 0.15f))
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

/** One-tap "nearest …" searches; [kinds] are the search-index kinds each one covers. */
private enum class NearestCategory(val label: Int, val icon: ImageVector, val tint: Color, val kinds: List<String>) {
    FUEL(R.string.kind_fuel, Icons.Default.LocalGasStation, EcoGreen, listOf("fuel")),
    CAMPING(R.string.kind_camping, Icons.Default.Cabin, ElectricCyan, listOf("camp_site")),
    HOTEL(R.string.kind_hotel, Icons.Default.Hotel, ElectricCyan, listOf("hotel")),
    DINING(R.string.kind_dining, Icons.Default.Restaurant, ElectricCyan, listOf("restaurant", "cafe", "fast_food")),
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

/** Saved routes store "Loop"/"Track" (or "via …", the same in both languages); shown in the rider's language. */
@Composable
private fun savedSubtitle(stored: String) = when (stored) {
    "Loop" -> stringResource(R.string.saved_loop)
    "Track" -> stringResource(R.string.saved_track)
    else -> stored
}

@Composable
private fun formatKm(km: Double) = if (km < 10) stringResource(R.string.distance_km, km) else stringResource(R.string.distance_km_whole, km)

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

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "city" -> R.string.kind_city
    "town" -> R.string.kind_town
    "village" -> R.string.kind_village
    "suburb", "borough", "quarter", "neighbourhood" -> R.string.kind_suburb
    "hamlet", "locality", "isolated_dwelling" -> R.string.kind_hamlet
    "island" -> R.string.kind_island
    "street" -> R.string.kind_street
    "fuel" -> R.string.kind_fuel
    "charging" -> R.string.kind_charging
    "cafe" -> R.string.kind_cafe
    "restaurant" -> R.string.kind_restaurant
    "pub" -> R.string.kind_pub
    "ferry" -> R.string.kind_ferry
    "parking" -> R.string.kind_parking
    "viewpoint" -> R.string.kind_viewpoint
    "camp_site" -> R.string.kind_camping
    "hotel" -> R.string.kind_hotel
    "hut" -> R.string.kind_hut
    "attraction" -> R.string.kind_attraction
    "museum" -> R.string.kind_museum
    "supermarket" -> R.string.kind_supermarket
    "shop" -> R.string.kind_shop
    "pass" -> R.string.kind_pass
    "peak" -> R.string.kind_peak
    "motorcycle_shop" -> R.string.kind_motorcycle_shop
    "fast_food" -> R.string.kind_fast_food
    "recent" -> R.string.search_recent
    else -> null
}?.let { stringResource(it) } ?: kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
