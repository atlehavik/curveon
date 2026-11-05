package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.curveon.data.model.GeoPoint
import com.curveon.ui.theme.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.style.layers.PropertyFactory.*

/** The map view, and over it the rider, the map's buttons, the remote's crosshair, the cards ([overlays]) and a note when there is no map. */
@Composable
internal fun CurveonMapState.MapContent(
    modifier: Modifier,
    isFullScreen: Boolean,
    location: GeoPoint,
    hasGpsFix: Boolean,
    gpsLost: Boolean,
    dark: Boolean,
    demoRide: Boolean,
    onDemoTap: () -> Unit,
    inPreview: Boolean,
    navigating: Boolean,
    headingDeg: Float,
    speedKmh: Float,
    autoZoom: Boolean,
    onSketch: ((List<GeoPoint>) -> Unit)?,
    buttonsTopInset: androidx.compose.ui.unit.Dp,
    buttonsBottomInset: androidx.compose.ui.unit.Dp,
    weatherButton: Boolean,
    onWeatherMap: ((Boolean) -> Unit)?,
    weatherMapOn: Boolean,
    hasPreviewTrip: Boolean,
    heatButton: Boolean,
    onShowGravelHeat: ((Boolean) -> Unit)?,
    showGravelHeat: Boolean,
    heatOn: Boolean,
    hasOfflineMap: Boolean,
    overlays: @Composable BoxScope.() -> Unit,
) {
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
        RiderMarks(this@MapContent, location, hasGpsFix, gpsLost, dark, demoRide, onDemoTap, inPreview)
        MapRemote(this@MapContent, navigating, headingDeg, speedKmh, autoZoom, onSketch, inPreview)

        // Compass button: north up → heading up → 3D. Once the rider pans the map away, a second
        // button brings the camera back to them; it hides again while following. +/- are always
        // there: while following with auto-zoom they nudge the speed-based zoom, otherwise they zoom.
        if (!inPreview) MapButtons(this@MapContent, navigating, headingDeg, autoZoom, buttonsTopInset, buttonsBottomInset, weatherButton, onWeatherMap, weatherMapOn)

        // Zoom to extents: the whole trip being planned, at a tap; under the speed and road cards.
        if (inPreview && hasPreviewTrip) Box(Modifier.align(Alignment.TopEnd).padding(top = maxOf(buttonsTopInset, 92.dp), end = 14.dp)) {
            MapButton(onClick = { fitTick++ }, tag = "map_fit_trip_btn") {
                Icon(Icons.Default.ZoomOutMap, stringResource(R.string.map_fit_trip), tint = TextPrimary, modifier = Modifier.size(24.dp))
            }
        }

        // The gravel heat map on and off, top left where nothing else is while planning or browsing
        // (the trip bar is there only while navigating); what it is up to beside it.
        if (heatButton) Row(Modifier.align(Alignment.TopStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MapButton(onClick = { onShowGravelHeat?.invoke(!showGravelHeat) }, highlighted = showGravelHeat, tag = "map_gravel_heat_btn") {
                Icon(Icons.Default.Whatshot, stringResource(R.string.map_gravel_heat),
                    tint = if (showGravelHeat) Color(0xFF00363D) else TextPrimary, modifier = Modifier.size(24.dp))
            }
            val note = when {
                !heatOn || heatTiles == null -> null
                heatTiles!!.any { it in heatCounting } -> stringResource(R.string.map_gravel_heat_counting)
                heatTiles!!.isEmpty() -> stringResource(R.string.map_gravel_heat_none)
                else -> null
            }
            if (note != null) Text(note, color = TextPrimary, fontSize = 13.sp,
                modifier = Modifier.padding(start = 10.dp).widthIn(max = 240.dp).clip(RoundedCornerShape(10.dp))
                    .background(CockpitSurface.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp))
        }

        overlays()

        // Riding on the online map is a fine choice; only say something when that leaves no map.
        if (!hasOfflineMap && !rememberOnline()) {
            Text(
                text = stringResource(R.string.map_none_offline),
                color = NeonAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 14.dp) // under the speed
                    .clip(RoundedCornerShape(8.dp))
                    .background(CockpitBackground.copy(alpha = 0.9f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** The rider: the arrow (a ghost without a signal), its halo, the DEMO tag, and a glow at the edge once out of view. */
@Composable
internal fun BoxScope.RiderMarks(
    h: CurveonMapState, location: GeoPoint, hasGpsFix: Boolean, gpsLost: Boolean, dark: Boolean, demoRide: Boolean, onDemoTap: () -> Unit, inPreview: Boolean,
) = with(h) {
    // At night the rider is orange, so it stands out from the cyan route; cyan again on an orange (gravel) route.
    val riderOrange = dark
    val riderColor = if (riderOrange) NeonAmber else ElectricCyan
    // No signal: a pale ghost of the arrow (where the rider was last seen, or is guessed to be in a tunnel)
    // with an amber satellite by it, and no accuracy halo (there is no accuracy to show). Nothing when there has never been a place to put it.
    val seen = location.latitude != 0.0 || location.longitude != 0.0
    val ghost = !hasGpsFix || gpsLost
    if (style != null && (hasGpsFix || seen)) {
        if (!ghost) RiderHalo(dark, riderColor) { puck }
        RiderArrow(riderOrange, ghost = ghost) { puck }
        if (ghost) RiderNoSatellite { puck }
        if (demoRide) RiderDemoTag(onDemoTap) { puck }
    }
    if (!following && !inPreview && hasGpsFix) riderScreen?.let { RiderEdgeGlow(it, dark, riderColor, Modifier.matchParentSize()) }
}

/**
 * The handlebar remote: the same zoom as the buttons, and the map moved in steps. What the crosshair is on is what a
 * long press there would give.
 */
@Composable
internal fun BoxScope.MapRemote(
    h: CurveonMapState, navigating: Boolean, headingDeg: Float, speedKmh: Float, autoZoom: Boolean, onSketch: ((List<GeoPoint>) -> Unit)?, inPreview: Boolean,
) = with(h) {
    val speedState = rememberUpdatedState(speedKmh)
    val latestChoose by rememberUpdatedState<(Orientation) -> Unit> { chooseOrientation(it, navigating, headingDeg) }
    val latestOrientation by rememberUpdatedState(orientation)
    val latestAutoZoom by rememberUpdatedState(autoZoom)
    val latestSketch by rememberUpdatedState(onSketch)
    val remote = LocalRemote.current
    DisposableEffect(remote) {
        val actions = object : RemoteMap {
            override fun pan(dx: Int, dy: Int) {
                val m = map ?: return
                following = false
                remotePanned = true
                panPressedAt = android.os.SystemClock.elapsedRealtime()
                val step = minOf(mapView.width, mapView.height) * REMOTE_PAN_SHARE
                m.scrollBy(-dx * step, -dy * step, REMOTE_PAN_MS)
            }
            override fun zoomIn() { if (following && latestAutoZoom) zoomBias = (zoomBias + 1).coerceAtMost(3.0) else map?.animateCamera(CameraUpdateFactory.zoomIn()); panPressedAt = android.os.SystemClock.elapsedRealtime() }
            override fun zoomOut() { if (following && latestAutoZoom) zoomBias = (zoomBias - 1).coerceAtLeast(-4.0) else map?.animateCamera(CameraUpdateFactory.zoomOut()); panPressedAt = android.os.SystemClock.elapsedRealtime() }
            override fun recenter() { following = true }
            override fun whatsHere() {
                val m = map ?: return
                longPressAt.value?.invoke(m.projection.fromScreenLocation(android.graphics.PointF(mapView.width / 2f, mapView.height / 2f)))
            }
            override fun nextOrientation() = latestChoose(Orientation.entries[(latestOrientation.ordinal + 1) % Orientation.entries.size])
            override val panned get() = !following
            override val canPoint get() = latestSketch == null
        }
        remote?.map = actions
        onDispose { if (remote?.map === actions) remote.map = null }
    }
    LaunchedEffect(following) { if (following) remotePanned = false }
    // Moved aside with the remote while riding, and then left alone: back to the rider by itself,
    // so a bump on the joystick never leaves the map somewhere else. Standing still it stays.
    LaunchedEffect(remotePanned) {
        while (remotePanned) {
            kotlinx.coroutines.delay(1_000)
            if (speedState.value >= 5f && android.os.SystemClock.elapsedRealtime() - panPressedAt > REMOTE_PAN_RETURN_MS) following = true
        }
    }
    if (remotePanned && !following && !inPreview) {
        RemoteCrosshair(Modifier.align(Alignment.Center))
        Text(stringResource(R.string.remote_crosshair_hint), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp).clip(RoundedCornerShape(10.dp))
                .background(CockpitSurface.copy(alpha = 0.9f)).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

/**
 * Compass button: north up → heading up → 3D. Once the rider pans the map away, a second
 * button brings the camera back to them; it hides again while following. +/- are always
 * there: while following with auto-zoom they nudge the speed-based zoom, otherwise they zoom.
 */
@Composable
internal fun BoxScope.MapButtons(
    h: CurveonMapState,
    navigating: Boolean,
    headingDeg: Float,
    autoZoom: Boolean,
    buttonsTopInset: androidx.compose.ui.unit.Dp,
    buttonsBottomInset: androidx.compose.ui.unit.Dp,
    weatherButton: Boolean,
    onWeatherMap: ((Boolean) -> Unit)?,
    weatherMapOn: Boolean,
) = with(h) {
    Column(
        modifier = Modifier.align(Alignment.CenterEnd).padding(top = buttonsTopInset, bottom = buttonsBottomInset).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End
    ) {
        // The weather map on and off, standing still on the full-screen map (top left is the give way sign's).
        if (weatherButton) MapButton(onClick = { onWeatherMap?.invoke(!weatherMapOn) }, highlighted = weatherMapOn, tag = "map_weather_btn") {
            Icon(Icons.Default.WbSunny, stringResource(R.string.map_weather),
                tint = if (weatherMapOn) Color(0xFF00363D) else TextPrimary, modifier = Modifier.size(24.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tiltControls && orientation == Orientation.THREE_D) {
                // Up lifts the view towards looking down on the map, down lowers it towards the road.
                MapButton(onClick = { tiltBy(-TILT_STEP) }, tag = "map_tilt_up_btn") {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.map_tilt_up), tint = TextPrimary, modifier = Modifier.size(28.dp))
                }
                MapButton(onClick = { tiltBy(TILT_STEP) }, tag = "map_tilt_down_btn") {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.map_tilt_down), tint = TextPrimary, modifier = Modifier.size(28.dp))
                }
            }
            MapButton(
                onClick = { chooseOrientation(Orientation.entries[(orientation.ordinal + 1) % Orientation.entries.size], navigating, headingDeg) },
                onLongClick = {
                    if (orientation != Orientation.THREE_D) chooseOrientation(Orientation.THREE_D, navigating, headingDeg)
                    tiltControls = true
                    tiltPresses++
                },
                tag = "map_orientation_btn"
            ) {
                when (orientation) {
                    Orientation.NORTH_UP -> Text("N", color = ElectricCyan, fontSize = 22.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.rotate(-cameraBearing))
                    Orientation.HEADING_UP -> Icon(Icons.Default.Navigation, stringResource(R.string.map_heading_up), tint = ElectricCyan,
                        modifier = Modifier.size(26.dp))
                    Orientation.THREE_D -> Text("3D", color = ElectricCyan, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        MapButton(onClick = {
            if (following && autoZoom) zoomBias = (zoomBias + 1).coerceAtMost(3.0) else map?.animateCamera(CameraUpdateFactory.zoomIn())
        }, tag = "map_zoom_in_btn") {
            Icon(Icons.Default.Add, stringResource(R.string.map_zoom_in), tint = TextPrimary, modifier = Modifier.size(24.dp))
        }
        MapButton(onClick = {
            if (following && autoZoom) zoomBias = (zoomBias - 1).coerceAtLeast(-4.0) else map?.animateCamera(CameraUpdateFactory.zoomOut())
        }, tag = "map_zoom_out_btn") {
            Icon(Icons.Default.Remove, stringResource(R.string.map_zoom_out), tint = TextPrimary, modifier = Modifier.size(24.dp))
        }
        // Its own slot, so the other buttons don't jump when it comes and goes.
        Box(Modifier.size(52.dp)) {
            if (!following) MapButton(onClick = { following = true }, highlighted = true, tag = "map_recenter_btn") {
                Icon(Icons.Default.MyLocation, stringResource(R.string.map_follow_me), tint = Color(0xFF00363D), modifier = Modifier.size(24.dp))
            }
        }

    }
}
