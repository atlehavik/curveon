package com.vibenav.ui.components

import android.annotation.SuppressLint
import android.graphics.RectF
import android.location.Location
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vibenav.data.map.OfflineMaps
import com.vibenav.data.model.GeoPoint
import com.vibenav.data.model.GroundPreference
import com.vibenav.data.model.TurnInstruction
import com.vibenav.ui.theme.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE = "vibenav-route"
private const val ALT = "vibenav-alternatives"
private const val MARKERS = "vibenav-markers"
private const val FOLLOW_ZOOM = 15.5
private const val NAV_TILT = 45.0

/** A route drawn during preview; [selected] is the one that will be ridden. */
data class MapRoute(val points: List<GeoPoint>, val selected: Boolean)

/**
 * The main map: MapLibre rendering the offline Norway vector map (day or night style), the
 * rider, the active route or the previewed alternatives, and stop markers.
 */
@Composable
fun VibeMapView(
    location: GeoPoint,
    headingDeg: Float,
    speedKmh: Float,
    speedLimitKmh: Int,
    hasGpsFix: Boolean,
    routePoints: List<GeoPoint>,
    groundPreference: GroundPreference,
    currentTurnInstruction: TurnInstruction?,
    isFullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    previewRoutes: List<MapRoute> = emptyList(),
    markers: List<GeoPoint> = emptyList(),
    roadNameProbe: Pair<Int, GeoPoint>? = null,
    onRoadName: (Int, String?) -> Unit = { _, _ -> },
    onLongPress: (GeoPoint) -> Unit = {},
    overlays: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineMaps = remember { OfflineMaps(context.applicationContext) }
    val hasOfflineMap = remember { offlineMaps.activeMap() != null }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var following by remember { mutableStateOf(true) }
    var cameraBearing by remember { mutableFloatStateOf(0f) }
    var cameraIdleTick by remember { mutableIntStateOf(0) }

    // Map listeners are registered once, so read changing values through State holders.
    val isFullScreenState = rememberUpdatedState(isFullScreen)
    val onToggleFullScreenState = rememberUpdatedState(onToggleFullScreen)
    val onLongPressState = rememberUpdatedState(onLongPress)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isAttributionEnabled = true
                attributionGravity = Gravity.BOTTOM or Gravity.START
                isLogoEnabled = false
                isCompassEnabled = false
            }
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(FOLLOW_ZOOM)
                .build()
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false
            }
            m.addOnCameraMoveListener { cameraBearing = m.cameraPosition.bearing.toFloat() }
            m.addOnCameraIdleListener { cameraIdleTick++ }
            m.addOnMapClickListener { if (!isFullScreenState.value) onToggleFullScreenState.value(); true }
            m.addOnMapLongClickListener { p -> onLongPressState.value(GeoPoint(p.latitude, p.longitude)); true }
            map = m
        }
    }

    // Style: day or night. Switching style drops our layers, so they are re-added each time.
    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        val json = offlineMaps.styleJson(if (dark) "dark" else "light") ?: OfflineMaps.ONLINE_FALLBACK_STYLE
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            addOverlayLayers(s, dark)
            activatePuck(m, s, context)
            style = s
        }
    }

    // Active route line
    LaunchedEffect(style, routePoints, groundPreference) {
        val s = style ?: return@LaunchedEffect
        val color = when (groundPreference) {
            GroundPreference.GRAVEL -> "#FF9100"
            GroundPreference.MIXED -> "#00C853"
            GroundPreference.PAVED -> if (dark) "#00E5FF" else "#0091EA"
        }
        (s.getLayer("$ROUTE-line") as? LineLayer)?.setProperties(lineColor(color))
        if (previewRoutes.isEmpty()) {
            s.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(lineFeatures(if (routePoints.size >= 2) listOf(routePoints) else emptyList()))
        }
    }

    // Previewed alternatives: all of them dim, the selected one bright on top.
    LaunchedEffect(style, previewRoutes) {
        val s = style ?: return@LaunchedEffect
        val selected = previewRoutes.filter { it.selected }.map { it.points }
        val others = previewRoutes.filter { !it.selected }.map { it.points }
        s.getSourceAs<GeoJsonSource>(ALT)?.setGeoJson(lineFeatures(others))
        s.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(
            lineFeatures(if (previewRoutes.isNotEmpty()) selected else if (routePoints.size >= 2) listOf(routePoints) else emptyList())
        )
    }

    LaunchedEffect(style, markers) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(MARKERS)?.setGeoJson(
            FeatureCollection.fromFeatures(markers.map { p -> Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)) })
        )
    }

    // Preview: show the whole trip. Leaving preview hands the camera back to following.
    val previewPoints = remember(previewRoutes) { previewRoutes.flatMap { it.points } }
    LaunchedEffect(map, previewPoints.size) {
        val m = map ?: return@LaunchedEffect
        if (previewPoints.size < 2) return@LaunchedEffect
        following = false
        val b = LatLngBounds.Builder()
        previewPoints.forEach { b.include(LatLng(it.latitude, it.longitude)) }
        b.include(LatLng(location.latitude, location.longitude))
        // Fit the trip into the part of the map above the preview sheet (about half the height).
        val h = mapView.height.takeIf { it > 0 } ?: 1000
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 60, 50, 120, (h * 0.52).toInt()), 800)
    }
    val inPreview = previewRoutes.isNotEmpty()
    LaunchedEffect(inPreview) { if (!inPreview) following = true }

    // Starting a route always brings the camera back to the rider.
    val hasRoute = routePoints.isNotEmpty()
    LaunchedEffect(hasRoute) { if (hasRoute) following = true }

    // Rider position + camera follow
    LaunchedEffect(map, style, location, headingDeg, following, hasGpsFix) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        if (hasGpsFix && m.locationComponent.isLocationComponentActivated) {
            m.locationComponent.forceLocationUpdate(Location("gps").apply {
                latitude = location.latitude
                longitude = location.longitude
                bearing = headingDeg
                speed = speedKmh / 3.6f
            })
        }
        if (following && !inPreview) {
            val moving = speedKmh >= 5f
            m.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(if (hasRoute) navigationZoom(speedKmh) else m.cameraPosition.zoom)
                        .bearing(if (moving) headingDeg.toDouble() else m.cameraPosition.bearing)
                        .tilt(if (moving && hasRoute) NAV_TILT else 0.0)
                        .padding(0.0, if (moving) 250.0 else 0.0, 0.0, 0.0) // rider sits low on screen
                        .build()
                ),
                900
            )
        }
    }

    // Road name after the next turn, read from the vector tiles once that spot is on screen.
    LaunchedEffect(map, style, roadNameProbe, cameraIdleTick) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val (index, point) = roadNameProbe ?: return@LaunchedEffect
        val screen = m.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
        if (screen.x < 0 || screen.y < 0 || screen.x > mapView.width || screen.y > mapView.height) return@LaunchedEffect
        val r = 24f
        val roadLayers = s.layers.map { it.id }.filter { it.startsWith("roads_") }.toTypedArray()
        val features = m.queryRenderedFeatures(RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r), *roadLayers)
        onRoadName(index, roadLabel(features))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(if (isFullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp))
            .background(CockpitBackground)
            .border(
                if (isFullScreen) 0.dp else 1.dp,
                CockpitBorder,
                if (isFullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp)
            )
            .testTag("map_container")
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (currentTurnInstruction != null) {
            Box(
                modifier = Modifier
                    .align(if (isFullScreen) Alignment.TopStart else Alignment.TopCenter)
                    .padding(14.dp)
                    .fillMaxWidth(if (isFullScreen) 0.55f else 0.95f)
            ) {
                TurnByTurnBanner(instruction = currentTurnInstruction)
            }
        }

        if (isFullScreen) {
            FullMapSpeedCluster(
                speedKmh = speedKmh,
                speedLimitKmh = speedLimitKmh,
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
            )
        }

        // One button while following (shows north; tap again for north-up). Zoom buttons only
        // appear once the rider has panned away, because while following the zoom is automatic.
        if (!inPreview) Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MapButton(
                onClick = {
                    if (following) map?.animateCamera(CameraUpdateFactory.bearingTo(0.0))
                    following = true
                },
                highlighted = following,
                tag = "map_recenter_btn"
            ) {
                if (following) {
                    Icon(Icons.Default.Navigation, "North up", tint = Color(0xFF00363D),
                        modifier = Modifier.size(24.dp).rotate(-cameraBearing))
                } else {
                    Icon(Icons.Default.MyLocation, "Follow me", tint = TextPrimary, modifier = Modifier.size(24.dp))
                }
            }
            if (!following) {
                MapButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }, tag = "map_zoom_in_btn") {
                    Icon(Icons.Default.Add, "Zoom in", tint = TextPrimary, modifier = Modifier.size(24.dp))
                }
                MapButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }, tag = "map_zoom_out_btn") {
                    Icon(Icons.Default.Remove, "Zoom out", tint = TextPrimary, modifier = Modifier.size(24.dp))
                }
            }
        }

        overlays()

        if (!hasOfflineMap) {
            Text(
                text = "No offline map installed – showing online map. Import one in Settings.",
                color = NeonAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CockpitBackground.copy(alpha = 0.9f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

private fun addOverlayLayers(s: Style, dark: Boolean) {
    s.addSource(GeoJsonSource(ALT))
    s.addSource(GeoJsonSource(ROUTE))
    s.addSource(GeoJsonSource(MARKERS))
    s.addLayer(
        LineLayer("$ALT-line", ALT).withProperties(
            lineColor(if (dark) "#5A6B7D" else "#8FA3B8"), lineWidth(6f), lineOpacity(0.85f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer("$ROUTE-casing", ROUTE).withProperties(
            lineColor(if (dark) "#00363D" else "#FFFFFF"), lineWidth(11f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer("$ROUTE-line", ROUTE).withProperties(
            lineColor(if (dark) "#00E5FF" else "#0091EA"), lineWidth(7f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        CircleLayer("$MARKERS-dots", MARKERS).withProperties(
            circleRadius(9f), circleColor("#FF9100"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
        )
    )
}

private fun lineFeatures(lines: List<List<GeoPoint>>): FeatureCollection =
    FeatureCollection.fromFeatures(lines.filter { it.size >= 2 }.map { pts ->
        Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }))
    })

/**
 * "Fv63 Storgata" style label from Protomaps road features: route number first, as that is what
 * is on the signs, then the street name. Norwegian OSM tags county/national roads with bare
 * numbers, so add the prefix the signs use (E-roads already carry theirs).
 */
internal fun roadLabel(features: List<Feature>): String? {
    val withRef = features.firstOrNull { f -> !f.getStringProperty("ref").isNullOrBlank() }
    val ref = withRef?.let { f ->
        val raw = f.getStringProperty("ref").split(';').first().trim()
        if (raw.all { it.isDigit() }) {
            val detail = f.getStringProperty("kind_detail") ?: ""
            (if (detail == "trunk" || detail == "primary" || detail == "motorway") "Rv" else "Fv") + raw
        } else raw.replace("E ", "E")
    }
    val name = features.firstNotNullOfOrNull { f -> f.getStringProperty("name")?.takeIf { it.isNotBlank() } }
    return listOfNotNull(ref, name?.takeIf { it != ref }).joinToString(" ").ifEmpty { null }
}

/** Zoom in for slow, twisty riding and out on fast roads so the next turn stays in view. */
private fun navigationZoom(speedKmh: Float): Double = when {
    speedKmh < 30f -> 16.5
    speedKmh < 60f -> 16.0
    speedKmh < 85f -> 15.3
    else -> 14.6
}

@SuppressLint("MissingPermission") // we only push locations manually, the component never reads GPS itself
private fun activatePuck(map: MapLibreMap, style: Style, context: android.content.Context) {
    val options = LocationComponentOptions.builder(context)
        .gpsDrawable(com.vibenav.R.drawable.ic_nav_arrow)
        .accuracyAlpha(0f)
        .elevation(0f)
        .build()
    map.locationComponent.apply {
        activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false)
                .build()
        )
        isLocationComponentEnabled = true
        cameraMode = CameraMode.NONE
        renderMode = RenderMode.GPS
    }
}

@Composable
private fun MapButton(
    onClick: () -> Unit,
    tag: String,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (highlighted) ElectricCyan else CockpitSurface)
            .border(1.dp, CockpitBorder, CircleShape)
            .testTag(tag)
    ) { content() }
}

@Composable
private fun FullMapSpeedCluster(speedKmh: Float, speedLimitKmh: Int, modifier: Modifier = Modifier) {
    val isOverLimit = speedLimitKmh > 0 && speedKmh.toInt() > speedLimitKmh
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CockpitSurface.copy(alpha = 0.92f))
            .border(1.dp, if (isOverLimit) WarningRed else CockpitBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("full_map_speed_cluster"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = speedKmh.toInt().toString(),
            color = if (isOverLimit) WarningRed else TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            lineHeight = 32.sp
        )
        if (speedLimitKmh > 0) SpeedLimitSign(speedLimitKmh, 36.dp)
    }
}
