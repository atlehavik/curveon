package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.GeoPoint
import com.example.data.model.GroundPreference
import com.example.data.model.RoadTypePreference
import com.example.ui.theme.*

data class PointOfInterest(
    val id: String,
    val name: String,
    val category: PoiCategory,
    val description: String,
    val distanceKm: Float,
    val location: GeoPoint,
    val highlightBadge: String? = null
)

enum class PoiCategory(val label: String, val icon: ImageVector, val accentColor: Color) {
    ALL("All", Icons.Default.Explore, ElectricCyan),
    PASSES("Passes", Icons.Default.Terrain, ElectricCyan),
    FUEL("Fuel", Icons.Default.LocalGasStation, NeonAmber),
    CAFE("Cafes", Icons.Default.LocalCafe, EcoGreen),
    WORKSHOP("Workshops", Icons.Default.Build, Color(0xFFFF9800)),
    CAMPING("Lodging", Icons.Default.Cabin, Color(0xFF81D4FA))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationMenuDialog(
    currentLocation: GeoPoint,
    onDismiss: () -> Unit,
    onNavigateToDestination: (GeoPoint, String, RoadTypePreference, GroundPreference) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(PoiCategory.ALL) }
    var selectedRoadType by remember { mutableStateOf(RoadTypePreference.CURVY) }
    var selectedGround by remember { mutableStateOf(GroundPreference.PAVED) }

    // Pre-calculated points of interest around the current area
    val pois = remember(currentLocation) {
        listOf(
            PointOfInterest(
                id = "poi_stelvio",
                name = "Passo dello Stelvio Summit",
                category = PoiCategory.PASSES,
                description = "2,757m • 48 Legendary Twisty Hairpins",
                distanceKm = 8.4f,
                location = GeoPoint(46.5286, 10.4532, 2757.0),
                highlightBadge = "Grade A Twisty"
            ),
            PointOfInterest(
                id = "poi_fuel_shell",
                name = "Shell Express 24/7 & Air Pump",
                category = PoiCategory.FUEL,
                description = "V-Power 100 • Moto Tyre Pressure Gauge",
                distanceKm = 4.6f,
                location = GeoPoint(46.4800, 10.3700, 1220.0),
                highlightBadge = "24h Open"
            ),
            PointOfInterest(
                id = "poi_gavia",
                name = "Passo di Gavia",
                category = PoiCategory.PASSES,
                description = "2,621m • Narrow High-Alpine Ridge",
                distanceKm = 18.2f,
                location = GeoPoint(46.3461, 10.4878, 2621.0),
                highlightBadge = "Scenic Pass"
            ),
            PointOfInterest(
                id = "poi_cafe_alps",
                name = "Alps Biker Cafe & Espresso",
                category = PoiCategory.CAFE,
                description = "Moto Terrace • Rider Community Hub",
                distanceKm = 6.8f,
                location = GeoPoint(46.4678, 10.3750, 1200.0),
                highlightBadge = "Biker Friendly"
            ),
            PointOfInterest(
                id = "poi_timmelsjoch",
                name = "Passo del Rombo / Timmelsjoch",
                category = PoiCategory.PASSES,
                description = "2,509m • Mountain Pass to Austria",
                distanceKm = 32.5f,
                location = GeoPoint(46.9056, 11.0978, 2509.0),
                highlightBadge = "Toll Pass"
            ),
            PointOfInterest(
                id = "poi_eni_fuel",
                name = "ENI Station & Moto Wash",
                category = PoiCategory.FUEL,
                description = "Super 98 • Helmet Cleaning Station",
                distanceKm = 11.3f,
                location = GeoPoint(46.5400, 10.5100, 1420.0),
                highlightBadge = "Helmet Station"
            ),
            PointOfInterest(
                id = "poi_moto_workshop",
                name = "Dolomiti Moto Service & Tyres",
                category = PoiCategory.WORKSHOP,
                description = "Puncture repair • Chain adjustment & parts",
                distanceKm = 14.1f,
                location = GeoPoint(46.4710, 10.3650, 1210.0),
                highlightBadge = "Puncture Help"
            ),
            PointOfInterest(
                id = "poi_camp_rider",
                name = "Rider Alpine Haven & Cabins",
                category = PoiCategory.CAMPING,
                description = "Moto parking • Gear drying room",
                distanceKm = 16.5f,
                location = GeoPoint(46.5050, 10.4200, 1380.0),
                highlightBadge = "Gear Dryer"
            ),
            PointOfInterest(
                id = "poi_pordoi",
                name = "Passo Pordoi (Sella Ronda Loop)",
                category = PoiCategory.PASSES,
                description = "2,239m • Dolomites Queen of Passes",
                distanceKm = 48.0f,
                location = GeoPoint(46.4883, 11.8108, 2239.0),
                highlightBadge = "Dolomites"
            )
        )
    }

    val filteredPois = remember(selectedCategory, searchQuery) {
        pois.filter { poi ->
            val matchesCategory = selectedCategory == PoiCategory.ALL || poi.category == selectedCategory
            val matchesSearch = searchQuery.isBlank() ||
                    poi.name.contains(searchQuery, ignoreCase = true) ||
                    poi.description.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface)
                .border(1.5.dp, CockpitBorder, RoundedCornerShape(20.dp))
                .padding(16.dp)
                .testTag("navigation_menu_dialog")
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header with title and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ElectricCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TwoWheeler,
                                contentDescription = "Navigation Menu",
                                tint = Color(0xFF00363D),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "DESTINATION & POI",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Search address or tap a motorcycle POI",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(CockpitSurfaceVariant)
                            .testTag("nav_menu_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 1. Search Bar for Address or Location
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search address, pass, or city...",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (searchQuery.isNotBlank()) {
                                // Navigate to custom destination offset based on search query
                                val targetPoint = GeoPoint(
                                    currentLocation.latitude + 0.08,
                                    currentLocation.longitude + 0.12,
                                    currentLocation.altitudeMeters + 150.0
                                )
                                onNavigateToDestination(
                                    targetPoint,
                                    searchQuery.trim(),
                                    selectedRoadType,
                                    selectedGround
                                )
                                onDismiss()
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = CockpitBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = ElectricCyan
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_destination_input")
                )

                // If user typed a custom address not matching a POI directly, show custom navigation prompt
                if (searchQuery.isNotBlank() && filteredPois.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElectricCyan.copy(alpha = 0.15f))
                            .border(1.dp, ElectricCyan, RoundedCornerShape(12.dp))
                            .clickable {
                                val targetPoint = GeoPoint(
                                    currentLocation.latitude + 0.09,
                                    currentLocation.longitude + 0.14,
                                    currentLocation.altitudeMeters + 200.0
                                )
                                onNavigateToDestination(
                                    targetPoint,
                                    searchQuery.trim(),
                                    selectedRoadType,
                                    selectedGround
                                )
                                onDismiss()
                            }
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "Navigate to \"$searchQuery\"",
                                    color = ElectricCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Calculate optimal motorcycle route",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Navigate",
                                tint = ElectricCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // 2. Routing Mode Selector: Curvy vs Fastest vs Trail
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RoadTypeChip(
                        title = "Curvy",
                        icon = Icons.Default.AltRoute,
                        isSelected = selectedRoadType == RoadTypePreference.CURVY,
                        onClick = { selectedRoadType = RoadTypePreference.CURVY },
                        modifier = Modifier.weight(1f)
                    )
                    RoadTypeChip(
                        title = "Very Curvy",
                        icon = Icons.Default.Timeline,
                        isSelected = selectedRoadType == RoadTypePreference.VERY_CURVY,
                        onClick = { selectedRoadType = RoadTypePreference.VERY_CURVY },
                        modifier = Modifier.weight(1f)
                    )
                    RoadTypeChip(
                        title = "Fastest",
                        icon = Icons.Default.Speed,
                        isSelected = selectedRoadType == RoadTypePreference.FASTEST,
                        onClick = { selectedRoadType = RoadTypePreference.FASTEST },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 3. POI Category Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(PoiCategory.values()) { category ->
                        val isSelected = category == selectedCategory
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = category },
                            label = { Text(category.label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            leadingIcon = {
                                Icon(
                                    imageVector = category.icon,
                                    contentDescription = category.label,
                                    tint = if (isSelected) Color.Black else category.accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = category.accentColor,
                                selectedLabelColor = Color.Black,
                                containerColor = CockpitSurfaceVariant,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = CockpitBorder,
                                selectedBorderColor = category.accentColor
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                // 4. POI List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPois) { poi ->
                        PoiListItem(
                            poi = poi,
                            onSelect = {
                                onNavigateToDestination(
                                    poi.location,
                                    poi.name,
                                    selectedRoadType,
                                    selectedGround
                                )
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoadTypeChip(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) ElectricCyan.copy(alpha = 0.2f) else CockpitSurfaceVariant)
            .border(
                width = 1.dp,
                color = if (isSelected) ElectricCyan else CockpitBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) ElectricCyan else TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                color = if (isSelected) ElectricCyan else TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PoiListItem(
    poi: PointOfInterest,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitSurfaceVariant)
            .border(1.dp, CockpitBorder, RoundedCornerShape(14.dp))
            .clickable { onSelect() }
            .padding(12.dp)
            .testTag("nav_poi_item_${poi.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Category Icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(poi.category.accentColor.copy(alpha = 0.18f))
                        .border(1.dp, poi.category.accentColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = poi.category.icon,
                        contentDescription = poi.category.label,
                        tint = poi.category.accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = poi.name,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (poi.highlightBadge != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ElectricCyan.copy(alpha = 0.15f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = poi.highlightBadge,
                                    color = ElectricCyan,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = poi.description,
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Distance & Quick Navigate Action
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "%.1f km".format(poi.distanceKm),
                    color = ElectricCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onSelect,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = "GO",
                        color = Color(0xFF00363D),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}
