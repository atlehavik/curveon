package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GeoPoint
import com.example.data.model.GroundPreference
import com.example.data.model.TurnInstruction
import com.example.data.model.TurnType
import com.example.data.osm.OsmTileManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
fun OsmMapView(
    centerPoint: GeoPoint,
    currentHeadingDeg: Float,
    currentSpeedKmh: Float,
    speedLimitKmh: Int,
    routePoints: List<GeoPoint>,
    groundPreference: GroundPreference,
    currentTurnInstruction: TurnInstruction?,
    isFullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tileManager = remember { OsmTileManager(context) }

    var zoomLevel by remember { mutableFloatStateOf(14.2f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var isFollowingVehicle by remember { mutableStateOf(true) }

    // Map tiles cache memory
    val loadedTiles = remember { mutableStateMapOf<String, Bitmap>() }

    // When vehicle center changes and following is true, reset pan
    LaunchedEffect(centerPoint, isFollowingVehicle) {
        if (isFollowingVehicle) {
            panOffsetX = 0f
            panOffsetY = 0f
        }
    }

    // Load tiles for current view
    val intZoom = zoomLevel.toInt().coerceIn(10, 18)
    LaunchedEffect(centerPoint, intZoom, panOffsetX, panOffsetY) {
        val centerTileX = OsmTileManager.lonToTileX(centerPoint.longitude, intZoom)
        val centerTileY = OsmTileManager.latToTileY(centerPoint.latitude, intZoom)

        // Load 3x3 surrounding tiles
        for (dx in -1..1) {
            for (dy in -1..1) {
                val tx = (centerTileX.toInt() + dx).coerceAtLeast(0)
                val ty = (centerTileY.toInt() + dy).coerceAtLeast(0)
                val key = "$intZoom/$tx/$ty"
                if (!loadedTiles.containsKey(key)) {
                    coroutineScope.launch {
                        val bmp = tileManager.getTile(intZoom, tx, ty)
                        if (bmp != null) {
                            loadedTiles[key] = bmp
                        }
                    }
                }
            }
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
            .testTag("osm_map_container")
    ) {
        // Interactive Canvas Layer
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoomLevel = (zoomLevel + 1f).coerceAtMost(18f)
                        },
                        onTap = {
                            if (!isFullScreen) {
                                onToggleFullScreen()
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        isFollowingVehicle = false
                        zoomLevel = (zoomLevel * zoom).coerceIn(10f, 18f)
                        panOffsetX += pan.x
                        panOffsetY += pan.y
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val centerX = canvasW / 2f + panOffsetX
            val centerY = canvasH / 2f + panOffsetY

            // 1. Draw High-Contrast Dark Motorcycle Tactical Map Base
            drawRect(color = CockpitBackground)

            // Draw dark grid / topo contour lines
            val gridSize = 60.dp.toPx()
            val startX = (centerX % gridSize)
            val startY = (centerY % gridSize)

            for (x in -1..(canvasW / gridSize).toInt() + 1) {
                val lineX = startX + x * gridSize
                drawLine(
                    color = CockpitBorder.copy(alpha = 0.25f),
                    start = Offset(lineX, 0f),
                    end = Offset(lineX, canvasH),
                    strokeWidth = 1f
                )
            }
            for (y in -1..(canvasH / gridSize).toInt() + 1) {
                val lineY = startY + y * gridSize
                drawLine(
                    color = CockpitBorder.copy(alpha = 0.25f),
                    start = Offset(0f, lineY),
                    end = Offset(canvasW, lineY),
                    strokeWidth = 1f
                )
            }

            // 2. Draw Cached OSM Bitmaps if present
            val centerTileX = OsmTileManager.lonToTileX(centerPoint.longitude, intZoom)
            val centerTileY = OsmTileManager.latToTileY(centerPoint.latitude, intZoom)
            val scale = (2.0.pow(zoomLevel.toDouble() - intZoom)).toFloat()
            val scaledTileSize = OsmTileManager.TILE_SIZE * scale

            for (dx in -2..2) {
                for (dy in -2..2) {
                    val tx = centerTileX.toInt() + dx
                    val ty = centerTileY.toInt() + dy
                    val key = "$intZoom/$tx/$ty"
                    val bmp = loadedTiles[key]

                    val tilePixelX = centerX + ((tx - centerTileX).toFloat() * scaledTileSize)
                    val tilePixelY = centerY + ((ty - centerTileY).toFloat() * scaledTileSize)

                    if (bmp != null && !bmp.isRecycled) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(tilePixelX.toInt(), tilePixelY.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(scaledTileSize.toInt(), scaledTileSize.toInt()),
                            alpha = 0.88f
                        )
                    }
                }
            }

            // 3. Draw Route Polyline with Curvy/Gravel styling
            if (routePoints.isNotEmpty()) {
                val routePath = Path()
                var first = true

                routePoints.forEach { pt ->
                    // Convert GeoPoint to screen pixels
                    val ptTileX = OsmTileManager.lonToTileX(pt.longitude, intZoom)
                    val ptTileY = OsmTileManager.latToTileY(pt.latitude, intZoom)
                    val px = centerX + ((ptTileX - centerTileX).toFloat() * scaledTileSize)
                    val py = centerY + ((ptTileY - centerTileY).toFloat() * scaledTileSize)

                    if (first) {
                        routePath.moveTo(px, py)
                        first = false
                    } else {
                        routePath.lineTo(px, py)
                    }
                }

                // Outer route glow
                drawPath(
                    path = routePath,
                    color = when (groundPreference) {
                        GroundPreference.GRAVEL -> NeonAmber.copy(alpha = 0.35f)
                        GroundPreference.MIXED -> EcoGreen.copy(alpha = 0.35f)
                        GroundPreference.PAVED -> ElectricCyan.copy(alpha = 0.35f)
                    },
                    style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Main route line
                drawPath(
                    path = routePath,
                    color = when (groundPreference) {
                        GroundPreference.GRAVEL -> NeonAmber
                        GroundPreference.MIXED -> EcoGreen
                        GroundPreference.PAVED -> ElectricCyan
                    },
                    style = Stroke(
                        width = 8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (groundPreference == GroundPreference.GRAVEL) {
                            PathEffect.dashPathEffect(floatArrayOf(30f, 15f), 0f)
                        } else null
                    )
                )

                // Start & End markers
                val startPt = routePoints.first()
                val sTileX = OsmTileManager.lonToTileX(startPt.longitude, intZoom)
                val sTileY = OsmTileManager.latToTileY(startPt.latitude, intZoom)
                val spx = centerX + ((sTileX - centerTileX).toFloat() * scaledTileSize)
                val spy = centerY + ((sTileY - centerTileY).toFloat() * scaledTileSize)

                drawCircle(color = EcoGreen, radius = 9.dp.toPx(), center = Offset(spx, spy))
                drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(spx, spy))
            }

            // 4. Draw Motorcycle Vehicle Marker at Center
            val vehCenterX = centerX
            val vehCenterY = centerY

            // Glowing vehicle radius pulse
            drawCircle(
                color = ElectricCyan.copy(alpha = 0.25f),
                radius = 28.dp.toPx(),
                center = Offset(vehCenterX, vehCenterY)
            )

            // Vehicle Outer Ring
            drawCircle(
                color = ElectricCyan,
                radius = 14.dp.toPx(),
                center = Offset(vehCenterX, vehCenterY),
                style = Stroke(width = 3.dp.toPx())
            )

            // Vehicle Heading Arrow (Motorcycle silhouette / triangle)
            val radHeading = Math.toRadians((currentHeadingDeg - 90.0))
            val arrowLength = 22.dp.toPx()
            val tipX = vehCenterX + (arrowLength * cos(radHeading)).toFloat()
            val tipY = vehCenterY + (arrowLength * sin(radHeading)).toFloat()

            val baseLeftRad = radHeading + Math.toRadians(140.0)
            val baseRightRad = radHeading - Math.toRadians(140.0)
            val baseRadius = 12.dp.toPx()

            val b1x = vehCenterX + (baseRadius * cos(baseLeftRad)).toFloat()
            val b1y = vehCenterY + (baseRadius * sin(baseLeftRad)).toFloat()
            val b2x = vehCenterX + (baseRadius * cos(baseRightRad)).toFloat()
            val b2y = vehCenterY + (baseRadius * sin(baseRightRad)).toFloat()

            val arrowPath = Path().apply {
                moveTo(tipX, tipY)
                lineTo(b1x, b1y)
                lineTo(vehCenterX, vehCenterY)
                lineTo(b2x, b2y)
                close()
            }
            drawPath(path = arrowPath, color = ElectricCyan)
            drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(vehCenterX, vehCenterY))
        }

        // Top Banner: Turn-by-Turn Instruction Card (Glove-glanceable)
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

        // Floating Map Controls (Right Side: Zoom In, Zoom Out, Recenter, Compass)
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
                    panOffsetX = 0f
                    panOffsetY = 0f
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isFollowingVehicle) ElectricCyan else CockpitSurface)
                    .border(1.dp, CockpitBorder, CircleShape)
                    .testTag("map_recenter_btn")
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
                onClick = { zoomLevel = (zoomLevel + 1f).coerceAtMost(18f) },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CockpitSurface)
                    .border(1.dp, CockpitBorder, CircleShape)
                    .testTag("map_zoom_in_btn")
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
                onClick = { zoomLevel = (zoomLevel - 1f).coerceAtLeast(10f) },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CockpitSurface)
                    .border(1.dp, CockpitBorder, CircleShape)
                    .testTag("map_zoom_out_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }


        // Bottom Right Tag: Offline OSM indicator
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
                    text = "OSM OFFLINE • Z${intZoom}",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
