package com.vibenav.ui.components

import android.annotation.SuppressLint
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
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE = "vibenav-route"
private const val FOLLOW_ZOOM = 15.5
private const val NAV_TILT = 45.0

/**
 * The main map: MapLibre rendering the offline Norway vector map, with the rider's position,
 * heading-up camera follow and the active route.
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
    onLongPress: (GeoPoint) -> Unit = {},
    modifier: Modifier = Modifier,
    overlays: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineMaps = remember { OfflineMaps(context.applicationContext) }
    val styleJson = remember { offlineMaps.styleJson("dark") }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var following by remember { mutableStateOf(true) }
    var cameraBearing by remember { mutableFloatStateOf(0f) }

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
            m.addOnMapClickListener { if (!isFullScreenState.value) onToggleFullScreenState.value(); true }
            m.addOnMapLongClickListener { p -> onLongPressState.value(GeoPoint(p.latitude, p.longitude)); true }
            val builder = if (styleJson != null) Style.Builder().fromJson(styleJson)
            else Style.Builder().fromJson(OfflineMaps.ONLINE_FALLBACK_STYLE)
            m.setStyle(builder) { s ->
                s.addSource(GeoJsonSource(ROUTE_SOURCE))
                s.addLayer(
                    LineLayer("$ROUTE_SOURCE-casing", ROUTE_SOURCE).withProperties(
                        lineColor("#00363D"), lineWidth(11f),
                        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
                s.addLayer(
                    LineLayer("$ROUTE_SOURCE-line", ROUTE_SOURCE).withProperties(
                        lineColor("#00E5FF"), lineWidth(7f),
                        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
                activatePuck(m, s, context)
                style = s
            }
            map = m
        }
    }
    // Route line
    LaunchedEffect(style, routePoints, groundPreference) {
        val s = style ?: return@LaunchedEffect
        val color = when (groundPreference) {
            GroundPreference.GRAVEL -> "#FF9100"
            GroundPreference.MIXED -> "#00E676"
            GroundPreference.PAVED -> "#00E5FF"
        }
        (s.getLayer("$ROUTE_SOURCE-line") as? LineLayer)?.setProperties(lineColor(color))
        val source = s.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return@LaunchedEffect
        if (routePoints.size >= 2) {
            source.setGeoJson(LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }))
        } else {
            source.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
        }
    }

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
        if (following) {
            val moving = speedKmh >= 5f
            m.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(if (hasRoute) navigationZoom(speedKmh) else m.cameraPosition.zoom)
                        .bearing(if (moving) headingDeg.toDouble() else m.cameraPosition.bearing)
                        .tilt(if (moving && routePoints.isNotEmpty()) NAV_TILT else 0.0)
                        .padding(0.0, if (moving) 250.0 else 0.0, 0.0, 0.0) // rider sits low on screen
                        .build()
                ),
                900
            )
        }
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

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MapButton(
                onClick = {
                    if (following) {
                        // Second press while following: snap back to north-up
                        map?.animateCamera(CameraUpdateFactory.bearingTo(0.0))
                    }
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
            MapButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }, tag = "map_zoom_in_btn") {
                Icon(Icons.Default.Add, "Zoom in", tint = TextPrimary, modifier = Modifier.size(24.dp))
            }
            MapButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }, tag = "map_zoom_out_btn") {
                Icon(Icons.Default.Remove, "Zoom out", tint = TextPrimary, modifier = Modifier.size(24.dp))
            }
        }

        overlays()

        if (styleJson == null) {
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
        if (speedLimitKmh > 0) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SpeedLimitWhite)
                    .border(3.dp, SpeedLimitBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(speedLimitKmh.toString(), color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
