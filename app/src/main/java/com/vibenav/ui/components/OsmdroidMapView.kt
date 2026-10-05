package com.vibenav.ui.components

import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vibenav.R
import com.vibenav.data.model.GeoPoint
import com.vibenav.data.model.GroundPreference
import com.vibenav.data.model.TurnInstruction
import com.vibenav.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

@Composable
fun OsmdroidMapView(
    centerPoint: GeoPoint,
    currentHeadingDeg: Float,
    currentSpeedKmh: Float,
    speedLimitKmh: Int,
    routePoints: List<GeoPoint>,
    groundPreference: GroundPreference,
    currentTurnInstruction: TurnInstruction?,
    isFullScreen: Boolean,
    onToggleFullScreen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize osmdroid configuration synchronously before view hierarchy creation
    remember(context) {
        val config = Configuration.getInstance()
        config.userAgentValue = context.packageName
        config.osmdroidBasePath = File(context.filesDir, "osmdroid")
        config.osmdroidTileCache = File(context.cacheDir, "osm_tiles")
        true
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var isFollowingVehicle by remember { mutableStateOf(true) }

    // Lifecycle observation for MapView
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                Lifecycle.Event.ON_DESTROY -> mapViewRef?.onDetach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.onDetach()
        }
    }

    // Keep camera centered on vehicle when following
    LaunchedEffect(centerPoint, isFollowingVehicle) {
        if (isFollowingVehicle) {
            mapViewRef?.controller?.animateTo(OsmGeoPoint(centerPoint.latitude, centerPoint.longitude))
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
            .testTag("osmdroid_mapview_container")
    ) {
        // Osmdroid Native MapView via AndroidView
        AndroidView(
            factory = { ctx ->
                // Ensure user agent is set on configuration before MapView initializes tile provider
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled = false
                    setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
                    controller.setZoom(15.5)
                    controller.setCenter(OsmGeoPoint(centerPoint.latitude, centerPoint.longitude))

                    // Tactical Dark Mode Color Filter for night/cockpit motorcycle riding
                    val darkMatrix = ColorMatrix(
                        floatArrayOf(
                            -0.85f, 0f, 0f, 0f, 255f, // red
                            0f, -0.85f, 0f, 0f, 255f, // green
                            0f, -0.85f, 0f, 0f, 255f, // blue
                            0f, 0f, 0f, 1f, 0f         // alpha
                        )
                    )
                    overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(darkMatrix))

                    mapViewRef = this
                }
            },
            update = { mapView ->
                // Update Route Polyline & Markers
                mapView.overlays.clear()

                // 1. Draw Active Route Polyline
                if (routePoints.isNotEmpty()) {
                    val polyline = Polyline(mapView).apply {
                        val osmPoints = routePoints.map { OsmGeoPoint(it.latitude, it.longitude) }
                        setPoints(osmPoints)
                        outlinePaint.strokeWidth = 14f
                        outlinePaint.color = when (groundPreference) {
                            GroundPreference.GRAVEL -> android.graphics.Color.rgb(255, 145, 0)
                            GroundPreference.MIXED -> android.graphics.Color.rgb(0, 230, 118)
                            GroundPreference.PAVED -> android.graphics.Color.rgb(0, 229, 255)
                        }
                    }
                    mapView.overlays.add(polyline)
                }

                // 2. Motorcycle Position & Heading Marker
                val motorcycleMarker = Marker(mapView).apply {
                    position = OsmGeoPoint(centerPoint.latitude, centerPoint.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    rotation = currentHeadingDeg
                    icon = androidx.core.content.ContextCompat.getDrawable(
                        mapView.context,
                        R.drawable.ic_launcher_foreground
                    )
                    title = "Motorcycle"
                }
                mapView.overlays.add(motorcycleMarker)

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top Banner: Turn-by-Turn Guidance Banner
        if (currentTurnInstruction != null) {
            Box(
                modifier = Modifier
                    .align(if (isFullScreen) Alignment.TopStart else Alignment.TopCenter)
                    .padding(14.dp)
                    .fillMaxWidth(if (isFullScreen) 0.55f else 0.95f)
            ) {
                TurnByTurnBanner(
                    instruction = currentTurnInstruction
                )
            }
        }

        // Full Map: Speed and Speed Limit in Upper Right Section (Speed number only, no unit)
        if (isFullScreen) {
            val isOverLimit = speedLimitKmh > 0 && currentSpeedKmh.toInt() > speedLimitKmh
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CockpitSurface.copy(alpha = 0.92f))
                    .border(
                        1.dp,
                        if (isOverLimit) WarningRed else CockpitBorder,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("full_map_speed_cluster")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Speed Number Only (no unit)
                    Text(
                        text = currentSpeedKmh.toInt().toString(),
                        color = if (isOverLimit) WarningRed else TextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = (-1).sp,
                        lineHeight = 32.sp
                    )

                    // Speed Limit Sign
                    if (speedLimitKmh > 0) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(SpeedLimitWhite)
                                .border(3.dp, SpeedLimitBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = speedLimitKmh.toString(),
                                color = Color.Black,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }

        // Floating Map Controls (Right Side: Zoom In, Zoom Out, Recenter)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Recenter / Follow Me Button
            IconButton(
                onClick = {
                    isFollowingVehicle = true
                    mapViewRef?.controller?.animateTo(OsmGeoPoint(centerPoint.latitude, centerPoint.longitude))
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isFollowingVehicle) ElectricCyan else CockpitSurface)
                    .border(1.dp, CockpitBorder, CircleShape)
                    .testTag("osmdroid_recenter_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Follow Vehicle",
                    tint = if (isFollowingVehicle) Color(0xFF00363D) else TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Zoom In
            IconButton(
                onClick = { mapViewRef?.controller?.zoomIn() },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CockpitSurface)
                    .border(1.dp, CockpitBorder, CircleShape)
                    .testTag("osmdroid_zoom_in_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Zoom In",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Zoom Out
            IconButton(
                onClick = { mapViewRef?.controller?.zoomOut() },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CockpitSurface)
                    .border(1.dp, CockpitBorder, CircleShape)
                    .testTag("osmdroid_zoom_out_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Bottom Right Tag: OSMDROID Offline indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CockpitBackground.copy(alpha = 0.85f))
                .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(EcoGreen)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "OSMDROID OFFLINE READY",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
