package com.curveon.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.curveon.data.model.GeoPoint
import com.curveon.data.model.TurnInstruction
import com.curveon.ui.theme.*
import kotlinx.coroutines.flow.first
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.PropertyFactory.*

/**
 * Where the map is looking, for the places kept on it. The following camera moves without ever
 * settling, so look now and then and report when the view has moved or zoomed enough to matter.
 */
@Composable
internal fun CurveonMapState.ViewportReports(
    onViewport: (south: Double, north: Double, west: Double, east: Double, zoom: Double, widthKm: Double, centerLat: Double, centerLon: Double) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val onViewportState = rememberUpdatedState(onViewport)
    LaunchedEffect(map, style) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        var last: org.maplibre.android.geometry.LatLngBounds? = null
        while (true) {
            val b = m.projection.visibleRegion.latLngBounds
            val was = last
            val width = b.longitudeEast - b.longitudeWest
            if (was == null || kotlin.math.abs(width / (was.longitudeEast - was.longitudeWest) - 1) > 0.05 ||
                kotlin.math.abs(b.center.longitude - was.center.longitude) > width * 0.1 ||
                kotlin.math.abs(b.center.latitude - was.center.latitude) > (b.latitudeNorth - b.latitudeSouth) * 0.1) {
                last = b
                // Across the middle of the screen: what the map's scale is, however it is tilted.
                val w = mapView.width.toFloat(); val h = mapView.height.toFloat()
                val midKm = if (w <= 0f) 0.0 else {
                    val l = m.projection.fromScreenLocation(android.graphics.PointF(0f, h / 2))
                    val r = m.projection.fromScreenLocation(android.graphics.PointF(w, h / 2))
                    com.curveon.routing.Geo.distance(l.latitude, l.longitude, r.latitude, r.longitude) / 1000
                }
                val at = m.cameraPosition.target
                onViewportState.value(b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast, m.cameraPosition.zoom,
                    midKm, at?.latitude ?: b.center.latitude, at?.longitude ?: b.center.longitude)
            }
            kotlinx.coroutines.withTimeoutOrNull(800) { viewportWake.receive() }
            lifecycleOwner.lifecycle.untilStarted()
        }
    }
}

/**
 * Preview: the camera stays where the rider puts it while they build or change the trip; the
 * fit button (zoom to extents) shows the whole of it. Only when a trip first appears and isn't
 * already in view is it fitted by itself. Leaving preview leaves the camera where it is.
 * Drawing an area: flat, north up and zoomed out. Returns whether there is a trip to fit.
 */
@Composable
internal fun CurveonMapState.PreviewCamera(
    previewRoutes: List<MapRoute>,
    editor: RouteEditor?,
    previewSheetMinimized: Boolean,
    location: GeoPoint,
    onSketch: ((List<GeoPoint>) -> Unit)?,
): Boolean {
    val editorState = rememberUpdatedState(editor)
    val previewPoints = remember(previewRoutes) { previewRoutes.flatMap { it.points } }
    val fitTrip: (Boolean) -> Unit = fit@{ onlyIfOutOfView ->
        val m = map ?: return@fit
        val pts = previewPoints
        if (pts.size < 2) return@fit
        val h = mapView.height.takeIf { it > 0 } ?: 1000
        val w = mapView.width.takeIf { it > 0 } ?: 1000
        // The part of the map above the preview sheet (about two thirds of the height, or just its
        // one-line bar when folded down); while editing there is no sheet.
        val bottom = when {
            editorState.value != null -> (90 * mapView.resources.displayMetrics.density).toInt()
            previewSheetMinimized -> (100 * mapView.resources.displayMetrics.density).toInt()
            else -> (h * 0.68).toInt()
        }
        if (onlyIfOutOfView && pts.all { p ->
                val s = m.projection.toScreenLocation(LatLng(p.latitude, p.longitude))
                s.x in 0f..w.toFloat() && s.y in 0f..(h - bottom).toFloat()
            }) return@fit
        val b = LatLngBounds.Builder()
        pts.forEach { b.include(LatLng(it.latitude, it.longitude)) }
        // The rider too, but only when the trip starts near them: planning far away is about the trip.
        val first = pts.first()
        if (haversine(location, first) < 2_000) b.include(LatLng(location.latitude, location.longitude))
        // The zoom that fits, then the trip's middle in the middle of the free area, by an explicit
        // camera padding (following leaves its own top padding on the camera, and bounds updates
        // don't reliably keep the part under the sheet free).
        val bounds = b.build()
        val pad = intArrayOf(60, 50, 120, bottom)
        val zoom = m.getCameraForLatLngBounds(bounds, pad, 0.0, 0.0)?.zoom ?: return@fit
        android.util.Log.d("CurveonMap", "Fit trip ${pts.size} points (only if out of view: $onlyIfOutOfView)")
        m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(bounds.center).zoom(zoom).bearing(0.0).tilt(0.0)
            .padding(pad[0].toDouble(), pad[1].toDouble(), pad[2].toDouble(), pad[3].toDouble())
            .build()), 800)
    }
    val hasPreviewTrip = previewPoints.size >= 2
    LaunchedEffect(map, hasPreviewTrip) {
        if (map == null || !hasPreviewTrip) return@LaunchedEffect
        following = false
        if (editorState.value == null) fitTrip(true)
    }
    LaunchedEffect(fitTick) { if (fitTick > 0) fitTrip(false) }
    // A drawn area stays in view while its loop is worked out.
    // Drawing an area: flat, north up and zoomed out to about a day's ride, where the map already is.
    LaunchedEffect(map, onSketch != null) {
        val m = map ?: return@LaunchedEffect
        if (onSketch == null) return@LaunchedEffect
        following = false
        m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(m.cameraPosition.target ?: LatLng(location.latitude, location.longitude)).zoom(minOf(m.cameraPosition.zoom, SKETCH_ZOOM))
            .bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), 800)
    }
    return hasPreviewTrip
}

/** The camera follows the rider, and the rider arrow is placed, frame by frame. */
@Composable
internal fun CurveonMapState.FollowCamera(
    routePoints: List<GeoPoint>,
    riderFix: com.curveon.data.location.RiderMotion.Fix?,
    location: GeoPoint,
    headingDeg: Float,
    speedKmh: Float,
    currentTurnInstruction: TurnInstruction?,
    bottomClearance: androidx.compose.ui.unit.Dp,
    inPreview: Boolean,
    hasGpsFix: Boolean,
    autoZoom: Boolean,
) {
    val locationState = rememberUpdatedState(location)
    // The camera is only taken back to the rider when navigation starts or the rider presses the
    // recenter button. Ending a preview, an edit or a pick leaves the map where the rider put it.
    // Starting a route always brings the camera back to the rider.
    val hasRoute = routePoints.isNotEmpty()
    LaunchedEffect(hasRoute) { if (hasRoute) following = true }

    // Rider position + camera follow. Every frame, not every fix: the rider is carried forward
    // between the once-a-second fixes (RiderMotion) and the camera moves with them, so the map
    // glides along the road. Zoom, tilt and where the rider sits on screen ease towards their
    // targets, so changing speed or mode doesn't jolt the view.
    LaunchedEffect(riderFix) { riderFix?.let { motion.onFix(it, System.nanoTime()) }; wake.trySend(Unit) }
    val accuracyState = rememberUpdatedState(riderFix?.accuracyM ?: 10.0)
    LaunchedEffect(headingDeg, speedKmh, currentTurnInstruction, bottomClearance) { wake.trySend(Unit) }
    DisposableEffect(mapView) {
        val onLayout = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> wake.trySend(Unit) }
        mapView.addOnLayoutChangeListener(onLayout)
        onDispose { mapView.removeOnLayoutChangeListener(onLayout) }
    }
    val speedState = rememberUpdatedState(speedKmh)
    val headingState = rememberUpdatedState(headingDeg)
    val turnState = rememberUpdatedState(currentTurnInstruction)
    val clearanceState = rememberUpdatedState(bottomClearance)
    // MapLibre draws on every screen frame of its own accord while the map moves (labels fade in
    // and out), so cap it to match. There is no "no cap": 1000 is as good as none.
    LaunchedEffect(map) { if (map != null) mapView.setMaximumFps(MAX_FPS) }
    LaunchedEffect(map, style, following, inPreview, orientation, zoomBias, hasGpsFix, autoZoom, tilt3d) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val follow = following && !inPreview
        val threeD = orientation == Orientation.THREE_D
        // Start from where the camera is, so coming back to following is a glide too.
        var zoom = m.cameraPosition.zoom
        var tilt = m.cameraPosition.tilt
        var lift = -1.0 // set from where the rider is on the first frame
        var camBearing = m.cameraPosition.bearing
        var last = 0L
        var lastPose: com.curveon.data.location.RiderMotion.Pose? = null
        var lastTrace = 0L
        var idle = false
        while (true) {
            // Standing still with the camera settled there is nothing to glide: rather than work on
            // every frame, sleep until something changes (a fix, the heading, a pan or zoom, the
            // layout), so the arrow never lags the map; and look once a second regardless.
            if (idle) kotlinx.coroutines.withTimeoutOrNull(1_000) { wake.receive() }
            val now = withFrameNanos { it }
            if (last != 0L && now - last < FRAME_NS) continue // every other 60 Hz frame
            val dt = if (last == 0L) 0.016 else ((now - last) / 1e9).coerceIn(0.0, 0.5)
            last = now
            val pose = (if (hasGpsFix) motion.sample(System.nanoTime()) else null)
            val target = pose?.let { LatLng(it.lat, it.lon) } ?: locationState.value.let { LatLng(it.latitude, it.longitude) }
            val moving = speedState.value >= 5f
            var settled = true
            if (follow) {
                val speed = speedState.value
                val h = mapView.height.toDouble()
                val wantZoom = if (autoZoom) (followZoom(speed, turnState.value?.distanceMeters, threeD) + zoomBias).coerceIn(10.0, 18.5) else zoom
                val wantTilt = if (threeD) tilt3d else 0.0
                // Heading up and 3D: the rider low on screen to see more road ahead. North up: centred.
                var wantLift = when {
                    threeD -> RIDER_LOW_3D * h
                    orientation == Orientation.HEADING_UP && moving -> RIDER_LOW_2D * h
                    else -> 0.0
                }
                // The rider sits at (height + lift) / 2: not so low that the arrow runs into the cards below.
                val clear = clearanceState.value.value * mapView.resources.displayMetrics.density
                if (clear > 0) wantLift = minOf(wantLift, (h - 2 * (clear + 40 * mapView.resources.displayMetrics.density)).coerceAtLeast(0.0))
                if (lift < 0) lift = (2 * m.projection.toScreenLocation(target).y - h).coerceIn(0.0, h)
                zoom = ease(zoom, wantZoom, dt, 1.2)
                tilt = ease(tilt, wantTilt, dt, 0.4)
                lift = ease(lift, wantLift, dt, 0.6)
                val wantBearing = when {
                    orientation == Orientation.NORTH_UP -> 0.0
                    pose != null && moving -> pose.bearingDeg
                    else -> camBearing
                }
                camBearing = (camBearing + com.curveon.routing.Geo.angleDiff(camBearing, wantBearing) * (1 - kotlin.math.exp(-dt / 0.25)) + 360.0) % 360.0
                val cam = m.cameraPosition
                // The rider low on screen by moving the camera's centre ahead of them, not by padding
                // the top: MapLibre caps the tilt by the padding, so a padded view couldn't tilt as far.
                // How far ahead is read off the view as it is now (one frame behind, too little to see).
                val padded = (cam.padding?.getOrNull(1) ?: 0.0) != 0.0
                val center = if (lift < 0.5 || padded) target else {
                    val ahead = m.projection.fromScreenLocation(android.graphics.PointF(mapView.width / 2f, ((h + lift) / 2).toFloat()))
                    val at = cam.target ?: target
                    LatLng(target.latitude - (ahead.latitude - at.latitude), target.longitude - (ahead.longitude - at.longitude))
                }
                val changed = padded || cam.target?.let { kotlin.math.abs(it.latitude - center.latitude) > 1e-7 || kotlin.math.abs(it.longitude - center.longitude) > 1e-7 } != false ||
                    kotlin.math.abs(cam.zoom - zoom) > 1e-3 || kotlin.math.abs(cam.tilt - tilt) > 0.05 ||
                    kotlin.math.abs(com.curveon.routing.Geo.angleDiff(cam.bearing, camBearing)) > 0.05
                settled = !changed
                if (changed) m.moveCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(center)
                        .zoom(zoom)
                        .bearing(camBearing)
                        .tilt(tilt)
                        .padding(0.0, 0.0, 0.0, 0.0)
                        .build()
                ))
            }
            // The arrow is drawn over the map (RiderArrow), placed from the camera just set, so it
            // moves in step with the map. MapLibre's own location puck animates on its own clock
            // and can't take a new position every frame.
            val p = m.projection.toScreenLocation(target)
            if (!follow) riderScreen = androidx.compose.ui.geometry.Offset(p.x, p.y) // only shown once panned away
            val cam = m.cameraPosition
            // The halo: the GPS's accuracy on the map, sideways (rx) and along the view (ry, shorter in
            // 3D), never smaller than a ring just round the arrow.
            val here = com.curveon.routing.LatLon(target.latitude, target.longitude)
            fun reach(bearing: Double): Float {
                val q = m.projection.toScreenLocation(com.curveon.routing.Geo.destination(here, bearing, accuracyState.value).let { LatLng(it.lat, it.lon) })
                return kotlin.math.hypot(q.x - p.x, q.y - p.y)
            }
            var rx = reach(cam.bearing + 90)
            var ry = (reach(cam.bearing) + reach(cam.bearing + 180)) / 2
            val minR = HALO_MIN_DP * mapView.resources.displayMetrics.density
            if (rx < minR) { ry = if (rx > 0f) ry * minR / rx else minR; rx = minR }
            val maxR = maxOf(mapView.width, mapView.height).toFloat()
            val newPuck = Puck(p.x, p.y, ((pose?.bearingDeg ?: headingState.value.toDouble()) - cam.bearing).toFloat(), cam.tilt.toFloat(),
                rx.coerceAtMost(maxR), ry.coerceAtMost(maxR))
            idle = settled && !moving && newPuck == puck
            puck = newPuck
            if (pose != null && now - lastTrace > 200_000_000L) {
                lastTrace = now
                com.curveon.data.location.NavTrace.drawn(pose.lat, pose.lon, pose.bearingDeg, m.cameraPosition.zoom, m.cameraPosition.tilt, follow)
            }
            lastPose = pose
        }
    }
}
