package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
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
import com.curveon.data.map.OfflineMaps
import com.curveon.data.model.GeoPoint
import com.curveon.data.model.GroundPreference
import com.curveon.data.model.TurnInstruction
import com.curveon.ui.theme.*
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

private const val ROUTE = "curveon-route"
private const val ALT = "curveon-alternatives"
private const val MARKERS = "curveon-markers"
private const val GPX = "curveon-gpx"
private const val GPX_POIS = "curveon-gpx-pois"
private const val HINT = "curveon-track-hint"
private const val ARROW_IMAGE = "curveon-arrow"
/** GPX waypoints turn from dots into icons from this zoom in (about 30 km across the map). */
private const val POI_ICON_ZOOM = 11f
private const val FOLLOW_ZOOM = 15.5
private const val TILT_3D = 60.0

/** How the camera turns while following the rider; the rider picks it with the compass button. */
private enum class Orientation { NORTH_UP, HEADING_UP, THREE_D }

/** A route drawn during preview; [selected] is the one that will be ridden. */
data class MapRoute(val points: List<GeoPoint>, val selected: Boolean)

/**
 * The main map: MapLibre rendering the offline Norway vector map (day or night style), the
 * rider, the active route or the previewed alternatives, and stop markers.
 */
@Composable
fun CurveonMapView(
    location: GeoPoint,
    headingDeg: Float,
    speedKmh: Float,
    speedLimitKmh: Int,
    hasGpsFix: Boolean,
    routePoints: List<GeoPoint>,
    groundPreference: GroundPreference,
    currentTurnInstruction: TurnInstruction?,
    isFullScreen: Boolean,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    previewRoutes: List<MapRoute> = emptyList(),
    markers: List<GeoPoint> = emptyList(),
    roadNameProbe: Pair<Int, GeoPoint>? = null,
    onRoadName: (Int, String?) -> Unit = { _, _ -> },
    gpxTracks: List<com.curveon.data.gpx.OverlayTrack> = emptyList(),
    gpxPois: List<com.curveon.data.gpx.GpxPoi> = emptyList(),
    trackHint: com.curveon.data.gpx.TrackHint? = null,
    onPoiClick: (com.curveon.data.gpx.GpxPoi) -> Unit = {},
    /** Zoom follows speed (in when slow and twisty, out on fast roads). */
    autoZoom: Boolean = true,
    /** Changes when the offline map file was downloaded/replaced/deleted: reload the style. */
    mapVersion: Int = 0,
    onLongPress: (GeoPoint) -> Unit = {},
    overlays: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineMaps = remember { OfflineMaps(context.applicationContext) }
    val hasOfflineMap = remember(mapVersion) { offlineMaps.activeMap() != null }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Camera: follows the rider (north up, heading up or 3D) until the rider pans the map.
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var orientation by remember {
        mutableStateOf(runCatching { Orientation.valueOf(prefs.getString("mapOrientation", null)!!) }.getOrDefault(Orientation.HEADING_UP))
    }
    var following by remember { mutableStateOf(true) }
    /** Where the rider is on screen (px), to hint at them from the edge once they're out of view. */
    var riderScreen by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    /** +/- while following nudges the speed-based zoom by this many levels. */
    var zoomBias by remember { mutableDoubleStateOf(0.0) }
    var cameraBearing by remember { mutableFloatStateOf(0f) }
    var cameraIdleTick by remember { mutableIntStateOf(0) }

    // Map listeners are registered once, so read changing values through State holders.
    val locationState = rememberUpdatedState(location)
    val onLongPressState = rememberUpdatedState(onLongPress)
    val onPoiClickState = rememberUpdatedState(onPoiClick)
    val poisState = rememberUpdatedState(gpxPois)
    val poiBadges = rememberPoiBadges()

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
            val trackRider = {
                val loc = locationState.value
                val p = m.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
                riderScreen = androidx.compose.ui.geometry.Offset(p.x, p.y)
            }
            m.addOnCameraMoveListener { cameraBearing = m.cameraPosition.bearing.toFloat(); trackRider() }
            m.addOnCameraIdleListener { cameraIdleTick++; trackRider() }
            m.addOnMapClickListener { p ->
                // A tap on a GPX waypoint shows it. Only the rail button switches map/dashboard,
                // so a stray tap on the map never takes the dashboard away.
                val pt = m.projection.toScreenLocation(p)
                val hit = m.queryRenderedFeatures(RectF(pt.x - 24, pt.y - 24, pt.x + 24, pt.y + 24), "$GPX_POIS-dots", "$GPX_POIS-icons").firstOrNull()
                val poi = hit?.getNumberProperty("i")?.toInt()?.let { poisState.value.getOrNull(it) }
                if (poi != null) onPoiClickState.value(poi)
                poi != null
            }
            m.addOnMapLongClickListener { p -> onLongPressState.value(GeoPoint(p.latitude, p.longitude)); true }
            map = m
        }
    }

    // Style: day or night. Switching style drops our layers, so they are re-added each time.
    LaunchedEffect(map, dark, mapVersion) {
        val m = map ?: return@LaunchedEffect
        val json = offlineMaps.styleJson(if (dark) "dark" else "light") ?: OfflineMaps.ONLINE_FALLBACK_STYLE
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            addOverlayLayers(s, dark)
            poiBadges.forEach { (name, bmp) -> s.addImage(name, bmp) }
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

    // GPX overlays: tracks in their colour with direction arrows, and their waypoints.
    LaunchedEffect(style, gpxTracks) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(GPX)?.setGeoJson(FeatureCollection.fromFeatures(gpxTracks.filter { it.points.size >= 2 }.map { t ->
            Feature.fromGeometry(LineString.fromLngLats(t.points.map { Point.fromLngLat(it.longitude, it.latitude) })).also {
                it.addStringProperty("color", hex(t.color))
            }
        }))
    }
    LaunchedEffect(style, gpxPois) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(GPX_POIS)?.setGeoJson(FeatureCollection.fromFeatures(gpxPois.mapIndexed { i, p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also {
                it.addNumberProperty("i", i)
                it.addStringProperty("name", p.displayName(context))
                it.addStringProperty("color", hex(p.category.color))
                it.addStringProperty("icon", poiImageName(p.kind))
            }
        }))
    }
    // Dashed line from the rider to the nearest visible track.
    LaunchedEffect(style, trackHint) {
        val s = style ?: return@LaunchedEffect
        val h = trackHint
        s.getSourceAs<GeoJsonSource>(HINT)?.setGeoJson(
            if (h == null || h.onTrack) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeatures(listOf(
                Feature.fromGeometry(LineString.fromLngLats(listOf(
                    Point.fromLngLat(h.from.longitude, h.from.latitude), Point.fromLngLat(h.to.longitude, h.to.latitude)
                ))).also { it.addStringProperty("color", hex(h.color)) },
                Feature.fromGeometry(Point.fromLngLat(h.to.longitude, h.to.latitude)).also { it.addStringProperty("color", hex(h.color)) }
            ))
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
    LaunchedEffect(map, style, location, headingDeg, following, orientation, zoomBias, hasGpsFix) {
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
            val threeD = orientation == Orientation.THREE_D
            m.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        // 3D looks further ahead, so it can sit a little closer.
                        .zoom(if (autoZoom) (navigationZoom(speedKmh) + zoomBias + if (threeD) 0.5 else 0.0).coerceIn(10.0, 18.5) else m.cameraPosition.zoom)
                        .bearing(if (orientation == Orientation.NORTH_UP) 0.0 else if (moving) headingDeg.toDouble() else m.cameraPosition.bearing)
                        .tilt(if (threeD) TILT_3D else 0.0)
                        // Heading up and 3D: rider low on screen to see more road ahead. North up: centred.
                        .padding(0.0, if (threeD) 0.3 * mapView.height else if (orientation == Orientation.HEADING_UP && moving) 250.0 else 0.0, 0.0, 0.0)
                        .build()
                ),
                900
            )
        }
        val p = m.projection.toScreenLocation(LatLng(location.latitude, location.longitude))
        riderScreen = androidx.compose.ui.geometry.Offset(p.x, p.y)
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
        if (!following && !inPreview && hasGpsFix) riderScreen?.let { RiderEdgeGlow(it, Modifier.matchParentSize()) }

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

        // Compass button: north up → heading up → 3D. Once the rider pans the map away, a second
        // button brings the camera back to them; it hides again while following. +/- are always
        // there: while following with auto-zoom they nudge the speed-based zoom, otherwise they zoom.
        if (!inPreview) Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MapButton(
                onClick = {
                    orientation = Orientation.entries[(orientation.ordinal + 1) % Orientation.entries.size]
                    prefs.edit().putString("mapOrientation", orientation.name).apply()
                    // Not following: turn the map where it is; following picks it up by itself.
                    if (!following) map?.let { m ->
                        m.animateCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder(m.cameraPosition)
                                .bearing(if (orientation == Orientation.NORTH_UP) 0.0 else headingDeg.toDouble())
                                .tilt(if (orientation == Orientation.THREE_D) TILT_3D else 0.0)
                                .build()
                        ), 600)
                    }
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

        overlays()

        if (!hasOfflineMap) {
            Text(
                text = stringResource(R.string.map_online_only),
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

private fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

/** White chevron used (tinted per track) to show a track's direction. */
private fun arrowBitmap(): android.graphics.Bitmap {
    val size = 48
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE
        strokeWidth = 11f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    // Points along +x, which is the line direction for line-placed symbols.
    val path = android.graphics.Path().apply { moveTo(12f, 8f); lineTo(36f, 24f); lineTo(12f, 40f) }
    c.drawPath(path, p)
    return bmp
}

private fun addOverlayLayers(s: Style, dark: Boolean) {
    s.addImage(ARROW_IMAGE, arrowBitmap(), true)
    s.addSource(GeoJsonSource(GPX))
    s.addSource(GeoJsonSource(GPX_POIS))
    s.addSource(GeoJsonSource(HINT))
    s.addLayer(
        LineLayer("$GPX-casing", GPX).withProperties(
            lineColor(if (dark) "#000000" else "#FFFFFF"), lineWidth(11f), lineOpacity(0.6f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer("$GPX-line", GPX).withProperties(
            lineColor(org.maplibre.android.style.expressions.Expression.get("color")), lineWidth(7f), lineOpacity(0.9f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$GPX-arrows", GPX).withProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(120f),
            iconImage(ARROW_IMAGE), iconSize(0.6f),
            iconColor(if (dark) "#000000" else "#FFFFFF"),
            iconAllowOverlap(true), iconIgnorePlacement(true),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
        )
    )
    s.addLayer(
        LineLayer("$HINT-line", HINT).withProperties(
            lineColor(org.maplibre.android.style.expressions.Expression.get("color")), lineWidth(3f),
            lineDasharray(arrayOf(2f, 2f)), lineOpacity(0.9f)
        )
    )
    s.addLayer(
        CircleLayer("$HINT-end", HINT).withProperties(
            circleRadius(7f), circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)
        ).withFilter(org.maplibre.android.style.expressions.Expression.eq(
            org.maplibre.android.style.expressions.Expression.geometryType(),
            org.maplibre.android.style.expressions.Expression.literal("Point")))
    )
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
    // Zoomed out: small dots in the category colour (many waypoints stay readable). Zoomed in:
    // a badge with an icon per kind, and the name.
    s.addLayer(
        CircleLayer("$GPX_POIS-dots", GPX_POIS).withProperties(
            circleRadius(8f), circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)
        ).also { it.maxZoom = POI_ICON_ZOOM }
    )
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$GPX_POIS-icons", GPX_POIS).withProperties(
            iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
            iconAllowOverlap(true), iconIgnorePlacement(true),
            textField(org.maplibre.android.style.expressions.Expression.get("name")),
            textFont(arrayOf("Noto Sans Medium")), textSize(12f), textOffset(arrayOf(0f, 1.5f)),
            textAnchor(Property.TEXT_ANCHOR_TOP), textMaxWidth(9f), textOptional(true),
            textColor(if (dark) "#F1F5F9" else "#1B2430"), textHaloColor(if (dark) "#0B0E14" else "#FFFFFF"), textHaloWidth(1.5f)
        ).also { it.minZoom = POI_ICON_ZOOM }
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

/** Zoom in for slow, twisty riding and out on fast roads so what's ahead stays in view. */
private fun navigationZoom(speedKmh: Float): Double = when {
    speedKmh < 8f -> 16.0 // standing still: a bit of overview
    speedKmh < 30f -> 16.5
    speedKmh < 60f -> 16.0
    speedKmh < 85f -> 15.3
    else -> 14.6
}

@SuppressLint("MissingPermission") // we only push locations manually, the component never reads GPS itself
private fun activatePuck(map: MapLibreMap, style: Style, context: android.content.Context) {
    val options = LocationComponentOptions.builder(context)
        .gpsDrawable(com.curveon.R.drawable.ic_nav_arrow)
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

/**
 * The rider is off screen: a glow on the edge in their direction, stronger the closer they are,
 * so it's clear at a glance where "me" went after panning around.
 */
@Composable
private fun RiderEdgeGlow(rider: androidx.compose.ui.geometry.Offset, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val margin = 12.dp.toPx()
        if (rider.x in -margin..w + margin && rider.y in -margin..h + margin) return@Canvas
        if (rider.x.isNaN() || rider.y.isNaN()) return@Canvas
        val c = center
        val d = rider - c
        // Where the line from the centre towards the rider leaves the screen.
        val t = minOf(
            if (d.x != 0f) (w / 2) / kotlin.math.abs(d.x) else Float.MAX_VALUE,
            if (d.y != 0f) (h / 2) / kotlin.math.abs(d.y) else Float.MAX_VALUE
        )
        val edge = c + d * t
        val beyond = d.getDistance() * (1 - t)
        val near = (1f - beyond / (maxOf(w, h) * 3f)).coerceIn(0.35f, 1f)
        val radius = 110.dp.toPx() * (0.7f + 0.3f * near)
        drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(ElectricCyan.copy(alpha = 0.75f * near), ElectricCyan.copy(alpha = 0.3f * near), Color.Transparent),
                center = edge, radius = radius
            ),
            radius = radius, center = edge
        )
    }
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
