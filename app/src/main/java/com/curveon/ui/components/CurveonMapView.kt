package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ZoomOutMap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.style.expressions.Expression as E
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
private const val PICK = "curveon-pick"
private const val APPROACH = "curveon-approach"
/** At most this often is a camera that keeps moving (following the rider) reported as settled. */
private const val IDLE_TICK_MS = 1_000L
/**
 * The map, camera and rider arrow move 30 times a second, not on every 60 Hz frame: it looks the
 * same on the bike and costs about a quarter less battery (measured against 60 on a Pixel 9a).
 * Frames closer than this to the last one drawn are skipped.
 */
private const val FRAME_NS = 25_000_000L
private const val MAX_FPS = 30
private const val GPX = "curveon-gpx"
private const val GPX_POIS = "curveon-gpx-pois"
private const val HINT = "curveon-track-hint"
private const val SKETCH = "curveon-sketch"
private const val ROUTE_SURFACE = "curveon-route-surface"
private const val SKETCH_ZOOM = 9.0
private const val ARROW_IMAGE = "curveon-arrow"
/** GPX waypoints turn from dots into icons from this zoom in (about 30 km across the map). */
private const val POI_ICON_ZOOM = 11f
/** A marked part of the route shorter than this on screen is a tap, not a part. */
private const val MIN_SECTION_DP = 24f
private const val SURFACE = "curveon-surface"

/** The road surface dashes, also shown as the key in Settings. */
internal object SurfaceColors {
    const val GRAVEL_LIGHT = "#9AA0A6"
    const val GRAVEL_DARK = "#8A919C"
    const val SOFT_LIGHT = "#B39A82"
    const val SOFT_DARK = "#8F7458"
}
/** Gravel and soft roads from this zoom in (about 10 km across the X7's map); further out they would clutter. */
private const val SURFACE_ZOOM = 11f
private const val FOLLOW_ZOOM = 15.5
/** 3D tilt; car navigators sit around 45–50 (Mapbox's navigation camera: 45). */
private const val TILT_3D = 50.0
/** Rider position below the middle, as a share of the map's height, given as top padding (×2 lower). */
private const val RIDER_LOW_3D = 0.5 // rider ~3/4 down the map
private const val RIDER_LOW_2D = 0.3

/** How the camera turns while following the rider; the rider picks it with the compass button. */
private enum class Orientation { NORTH_UP, HEADING_UP, THREE_D }

/** A route drawn during preview; [selected] is the one that will be ridden. */
/**
 * A route to draw; [surfaces] (one per point: [SURFACE_PAVED], [SURFACE_GRAVEL], [SURFACE_TRACK])
 * colours the selected one by what is underfoot, so the gravel in a planned trip shows at a glance.
 */
data class MapRoute(val points: List<GeoPoint>, val selected: Boolean, val surfaces: List<Int>? = null)

const val SURFACE_PAVED = 0
const val SURFACE_GRAVEL = 1
const val SURFACE_TRACK = 2

/**
 * The main map: MapLibre rendering the offline Norway vector map (day or night style), the
 * rider, the active route or the previewed alternatives, and stop markers.
 */
@Composable
fun CurveonMapView(
    location: GeoPoint,
    /** The latest fix, carried forward frame by frame so the rider glides instead of hopping. */
    riderFix: com.curveon.data.location.RiderMotion.Fix?,
    headingDeg: Float,
    speedKmh: Float,
    speedLimitKmh: Int,
    hasGpsFix: Boolean,
    routePoints: List<GeoPoint>,
    groundPreference: GroundPreference,
    currentTurnInstruction: TurnInstruction?,
    isFullScreen: Boolean,
    /** How far up from the bottom the cards over the map reach: the rider is lifted to keep clear of them. */
    bottomClearance: androidx.compose.ui.unit.Dp = 0.dp,
    /** Room the cards over the map take above and below the right-hand buttons: the buttons sit between them. */
    buttonsTopInset: androidx.compose.ui.unit.Dp = 0.dp,
    buttonsBottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    previewRoutes: List<MapRoute> = emptyList(),
    markers: List<MapPin> = emptyList(),
    roadNameProbe: Pair<Int, GeoPoint>? = null,
    onRoadName: (Int, com.curveon.data.road.MapRoad?) -> Unit = { _, _ -> },
    /** Called now and then with the road under the rider as the tiles have it; null = don't read it. */
    onRoadHere: ((com.curveon.data.road.MapRoad?) -> Unit)? = null,
    gpxTracks: List<com.curveon.data.gpx.OverlayTrack> = emptyList(),
    gpxPois: List<com.curveon.data.gpx.GpxPoi> = emptyList(),
    trackHint: com.curveon.data.gpx.TrackHint? = null,
    onPoiClick: (com.curveon.data.gpx.GpxPoi) -> Unit = {},
    /** Where the map is looking once it settles: south, north, west, east, and the zoom. */
    onViewport: (south: Double, north: Double, west: Double, east: Double, zoom: Double) -> Unit = { _, _, _, _, _ -> },
    /** Zoom follows speed (in when slow and twisty, out on fast roads). */
    autoZoom: Boolean = true,
    /** Test: the arrow's tip, not its middle, where the rider is. */
    arrowTipOnSpot: Boolean = false,
    /** Changes when the offline map file was downloaded/replaced/deleted: reload the style. */
    mapVersion: Int = 0,
    /** A finger held on the map: the place, the road there as the map labels it, and its number, class and name (null if none). */
    onLongPress: (GeoPoint, String?, com.curveon.data.road.MapRoad?) -> Unit = { _, _, _ -> },
    /** Picking a place on the map: a tap goes here instead of opening waypoints. */
    onTap: ((GeoPoint) -> Unit)? = null,
    /** The place picked so far, shown as a pin. */
    pickedPoint: GeoPoint? = null,
    /** Where the route starts when that isn't where the rider is: a dashed line from them to it. */
    approachTo: GeoPoint? = null,
    /** Editing the previewed trip on the map (drag its places or its line); null when not. */
    editor: RouteEditor? = null,
    /** Long-press on the previewed route line: start editing it. */
    onEditRoute: (() -> Unit)? = null,
    /** The preview sheet is folded down to one line: fit the trip into the taller space above it. */
    previewSheetMinimized: Boolean = false,
    /** Gravel and soft roads in an area (south, west, north, east), drawn dashed when zoomed in. */
    surfaceWays: (suspend (Double, Double, Double, Double) -> List<com.curveon.routing.SurfaceWay>)? = null,
    /** Settings → Display: draw the road surfaces at all. */
    showSurfaces: Boolean = true,
    /** An area drawn with a finger (closed back to its start), shown dashed. */
    sketch: List<GeoPoint> = emptyList(),
    /** Drawing an area: one finger draws (two still move the map), and the stroke comes here on letting go. */
    onSketch: ((List<GeoPoint>) -> Unit)? = null,
    /** The stroke so far while drawing (empty when the finger is lifted without an area). */
    onSketchProgress: (List<GeoPoint>) -> Unit = {},
    overlays: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineMaps = remember { OfflineMaps(context.applicationContext) }
    val hasOfflineMap = remember(mapVersion) { offlineMaps.installedMaps().isNotEmpty() }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    /**
     * The map has drawn a frame and isn't destroyed: only then may rendered features be read
     * (before, e.g. just after turning the phone, MapLibre's renderer isn't there and it crashes).
     */
    var canQuery by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Camera: follows the rider (north up, heading up or 3D) until the rider pans the map.
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    // Remembered separately for browsing the map (north up unless chosen otherwise) and for navigating (3D),
    // and switched when a trip starts or ends.
    val navigating = routePoints.size >= 2
    fun savedOrientation(nav: Boolean) = runCatching { Orientation.valueOf(prefs.getString(if (nav) "orientationNav" else "orientationBrowse", null)!!) }
        .getOrDefault(if (nav) Orientation.THREE_D else Orientation.NORTH_UP)
    var orientation by remember { mutableStateOf(savedOrientation(navigating)) }
    var orientationFor by remember { mutableStateOf(navigating) }
    if (orientationFor != navigating) { orientationFor = navigating; orientation = savedOrientation(navigating) }
    var following by remember { mutableStateOf(true) }
    /** Where the rider is on screen (px), to hint at them from the edge once they're out of view. */
    var riderScreen by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    /** +/- while following nudges the speed-based zoom by this many levels. */
    var zoomBias by remember { mutableDoubleStateOf(0.0) }
    var cameraBearing by remember { mutableFloatStateOf(0f) }
    var cameraIdleTick by remember { mutableIntStateOf(0) }
    /** Nudged when what the rider loop below draws may change (a fix, the camera, the layout), to wake it. */
    val wake = remember { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    /** Where and how to draw the rider arrow this frame. */
    var puck by remember { mutableStateOf<Puck?>(null) }

    // Map listeners are registered once, so read changing values through State holders.
    val locationState = rememberUpdatedState(location)
    val onLongPressState = rememberUpdatedState(onLongPress)
    val onTapState = rememberUpdatedState(onTap)
    val editorState = rememberUpdatedState(editor)
    val onSketchState = rememberUpdatedState(onSketch)
    val onSketchProgressState = rememberUpdatedState(onSketchProgress)
    val onEditRouteState = rememberUpdatedState(onEditRoute)
    val previewRoutesState = rememberUpdatedState(previewRoutes)
    val onPoiClickState = rememberUpdatedState(onPoiClick)
    val onViewportState = rememberUpdatedState(onViewport)
    val poisState = rememberUpdatedState(gpxPois)
    val poiBadges = rememberPoiBadges()

    val mapView = remember {
        MapLibre.getInstance(context)
        // Online map tiles seen once stay usable offline (MapLibre's default cache is 50 MB).
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, null)
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
            canQuery = false
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.addOnDidFinishRenderingFrameListener { _, _, _ -> if (!canQuery) canQuery = true }
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isAttributionEnabled = true
                // The map's licence notice has to stay readable: left edge, halfway up, where no card sits.
                attributionGravity = Gravity.CENTER_VERTICAL or Gravity.START
                val margin = (8 * mapView.resources.displayMetrics.density).toInt()
                setAttributionMargins(margin, 0, 0, 0)
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
            // Called on every frame while following: only write what is read then (the compass
            // letter when it turns visibly; where the rider is only once the map is panned away).
            m.addOnCameraMoveListener {
                val bearing = m.cameraPosition.bearing.toFloat()
                if (kotlin.math.abs(bearing - cameraBearing) >= 0.5f) cameraBearing = bearing
                if (!following) trackRider()
                wake.trySend(Unit)
            }
            // MapLibre calls the camera idle after every moveCamera, so on every frame while it
            // follows the rider. What waits for it (online map check, gravel roads, road name
            // ahead) restarted 60 times a second then; once a second is plenty, and the last one
            // always comes, so a pan that stops is still seen.
            var lastIdleTick = 0L
            val idleTick = Runnable { lastIdleTick = android.os.SystemClock.uptimeMillis(); cameraIdleTick++ }
            m.addOnCameraIdleListener {
                if (!following) trackRider()
                mapView.removeCallbacks(idleTick)
                val wait = lastIdleTick + IDLE_TICK_MS - android.os.SystemClock.uptimeMillis()
                if (wait <= 0) idleTick.run() else mapView.postDelayed(idleTick, wait)
            }
            m.addOnMapClickListener { p ->
                onTapState.value?.let { tap -> tap(GeoPoint(p.latitude, p.longitude)); return@addOnMapClickListener true }
                // A tap on a GPX waypoint shows it. Only the rail button switches map/dashboard,
                // so a stray tap on the map never takes the dashboard away.
                if (!canQuery) return@addOnMapClickListener false
                val pt = m.projection.toScreenLocation(p)
                val hit = m.queryRenderedFeatures(RectF(pt.x - 24, pt.y - 24, pt.x + 24, pt.y + 24), "$GPX_POIS-dots", "$GPX_POIS-icons").firstOrNull()
                val poi = hit?.getNumberProperty("i")?.toInt()?.let { poisState.value.getOrNull(it) }
                if (poi != null) onPoiClickState.value(poi)
                poi != null
            }
            m.addOnMapLongClickListener { p ->
                val at = GeoPoint(p.latitude, p.longitude)
                val editRoute = onEditRouteState.value
                val line = previewRoutesState.value.firstOrNull { it.selected }?.points.orEmpty()
                val pt = m.projection.toScreenLocation(p)
                when {
                    onTapState.value != null -> onTapState.value?.invoke(at)
                    editRoute != null && editorState.value == null && line.size >= 2 &&
                        grabEdit(RouteEditor(emptyList(), false, { _, _ -> }, { _, _ -> }, {}), line, m, pt.x, pt.y, mapView.resources.displayMetrics.density) != null -> editRoute()
                    else -> {
                        // The road under the finger, by name or number, as the map labels it.
                        val roads = if (!canQuery) emptyList() else runCatching {
                            val roadLayers = m.style?.layers?.map { it.id }?.filter { it.startsWith("roads_") }.orEmpty().toTypedArray()
                            m.queryRenderedFeatures(RectF(pt.x - 20, pt.y - 20, pt.x + 20, pt.y + 20), *roadLayers)
                        }.getOrNull().orEmpty()
                        onLongPressState.value(at, roadLabel(roads), mapRoad(roads))
                    }
                }
                true
            }
            // Editing a route: a finger on a handle or on the line drags it (the map doesn't pan);
            // anywhere else the map moves as usual.
            val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
            var grabbed: EditGrab? = null
            var downX = 0f
            var downY = 0f
            var dragAt: GeoPoint? = null
            // Marking a part of the route: the line it is on and how far along the finger has slid.
            var grabLine: List<GeoPoint> = emptyList()
            var markedTo: Double? = null
            var longPress: Runnable? = null
            fun cancelLongPress(v: android.view.View) { longPress?.let { v.removeCallbacks(it) }; longPress = null }
            fun clearDrag() = m.style?.getSourceAs<GeoJsonSource>(EDIT_DRAG)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            // Drawing an area: the stroke so far, null when no finger is drawing.
            var stroke: MutableList<GeoPoint>? = null
            var lastX = 0f
            var lastY = 0f
            fun showStroke(pts: List<GeoPoint>) = m.style?.getSourceAs<GeoJsonSource>(SKETCH)?.setGeoJson(lineFeatures(listOf(pts)))
            fun sketchTouch(v: android.view.View, e: android.view.MotionEvent, done: (List<GeoPoint>) -> Unit): Boolean {
                fun at(x: Float, y: Float) = m.projection.fromScreenLocation(android.graphics.PointF(x, y)).let { GeoPoint(it.latitude, it.longitude) }
                return when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        stroke = mutableListOf(at(e.x, e.y))
                        lastX = e.x; lastY = e.y
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val st = stroke ?: return false
                        if (kotlin.math.hypot(e.x - lastX, e.y - lastY) > 6 * v.resources.displayMetrics.density) {
                            st += at(e.x, e.y)
                            lastX = e.x; lastY = e.y
                            showStroke(st)
                            onSketchProgressState.value(st.toList())
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val st = stroke ?: return false
                        stroke = null
                        v.performClick()
                        if (st.size >= 3) done(st) else { showStroke(emptyList()); onSketchProgressState.value(emptyList()) }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> { stroke = null; true }
                    // A second finger: moving or zooming the map, not drawing (as when editing below).
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        if (stroke == null) return false
                        stroke = null
                        showStroke(emptyList())
                        val down = android.view.MotionEvent.obtain(e.downTime, e.eventTime, android.view.MotionEvent.ACTION_DOWN,
                            e.getX(0), e.getY(0), e.metaState)
                        v.onTouchEvent(down)
                        down.recycle()
                        false
                    }
                    else -> stroke != null
                }
            }
            mapView.setOnTouchListener { v, e ->
                onSketchState.value?.let { return@setOnTouchListener sketchTouch(v, e, it) }
                stroke = null
                val ed = editorState.value
                if (ed == null) { grabbed = null; return@setOnTouchListener false }
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val line = previewRoutesState.value.firstOrNull { it.selected }?.points.orEmpty()
                        val density = v.resources.displayMetrics.density
                        grabbed = if (ed.marking) grabSection(line, m, e.x, e.y, density) else grabEdit(ed, line, m, e.x, e.y, density)
                        grabLine = line
                        markedTo = null
                        dragAt = null
                        downX = e.x; downY = e.y
                        if (grabbed != null) v.parent?.requestDisallowInterceptTouchEvent(true)
                        // Held still on a point: its menu (remove it, …).
                        (grabbed as? EditGrab.Handle)?.let { h ->
                            longPress = Runnable {
                                if (grabbed !== h || dragAt != null) return@Runnable
                                grabbed = null
                                clearDrag()
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                editorState.value?.onLongPressHandle?.invoke(h.handle)
                            }.also { v.postDelayed(it, android.view.ViewConfiguration.getLongPressTimeout().toLong()) }
                        }
                        grabbed != null
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val g = grabbed ?: return@setOnTouchListener false
                        if (g is EditGrab.Section) {
                            // Marking: the finger slides along the line, wherever it is on the screen.
                            if (markedTo != null || kotlin.math.hypot(e.x - downX, e.y - downY) > slop) {
                                val to = alongAt(grabLine, m, e.x, e.y) ?: return@setOnTouchListener true
                                markedTo = to
                                m.style?.getSourceAs<GeoJsonSource>(EDIT_DRAG)?.setGeoJson(sectionFeatures(grabLine, g.at, to))
                            }
                        } else if (dragAt != null || kotlin.math.hypot(e.x - downX, e.y - downY) > slop) {
                            cancelLongPress(v)
                            if (ed.locked) {
                                // Locked: not a drag of the route but a pan. Hand the map the finger from where it went down.
                                grabbed = null
                                val down = android.view.MotionEvent.obtain(e.downTime, e.downTime, android.view.MotionEvent.ACTION_DOWN, downX, downY, e.metaState)
                                v.onTouchEvent(down)
                                down.recycle()
                                return@setOnTouchListener false
                            }
                            val ll = m.projection.fromScreenLocation(android.graphics.PointF(e.x, e.y))
                            val at = GeoPoint(ll.latitude, ll.longitude)
                            dragAt = at
                            m.style?.getSourceAs<GeoJsonSource>(EDIT_DRAG)?.setGeoJson(dragFeatures(g, at))
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        cancelLongPress(v)
                        val g = grabbed ?: return@setOnTouchListener false
                        grabbed = null
                        val at = dragAt
                        if (g is EditGrab.Section) {
                            val to = markedTo
                            val line = grabLine
                            // Too short to be a part of the route (a tap): nothing marked.
                            val a = m.projection.toScreenLocation(pointAlong(line, g.at).let { LatLng(it.latitude, it.longitude) })
                            val b = to?.let { m.projection.toScreenLocation(pointAlong(line, it).let { p -> LatLng(p.latitude, p.longitude) }) }
                            if (b == null || kotlin.math.hypot(a.x - b.x, a.y - b.y) < MIN_SECTION_DP * v.resources.displayMetrics.density) clearDrag()
                            else {
                                val from = minOf(g.at, to); val until = maxOf(g.at, to)
                                ed.onMarkSection(legAt(ed, line, from), pointAlong(line, from), legAt(ed, line, until), pointAlong(line, until))
                            }
                            v.performClick()
                            return@setOnTouchListener true
                        }
                        when {
                            at == null -> {
                                clearDrag()
                                when (g) {
                                    is EditGrab.Handle -> ed.onTapHandle(g.handle)
                                    else -> ed.onTapLine?.invoke(m.projection.fromScreenLocation(android.graphics.PointF(downX, downY)).let { GeoPoint(it.latitude, it.longitude) })
                                }
                            }
                            g is EditGrab.Handle -> ed.onMoveHandle(g.handle, at)
                            g is EditGrab.Line -> ed.onInsert(g.insertAt, at)
                        }
                        v.performClick()
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        cancelLongPress(v)
                        val had = grabbed != null
                        grabbed = null
                        if (had) clearDrag()
                        had
                    }
                    // A second finger: zooming or turning the map, not editing. Let go of the line or
                    // point and hand the gesture to the map, which never saw the first finger go down.
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        cancelLongPress(v)
                        if (grabbed == null) return@setOnTouchListener false
                        grabbed = null
                        clearDrag()
                        val down = android.view.MotionEvent.obtain(e.downTime, e.eventTime, android.view.MotionEvent.ACTION_DOWN,
                            e.getX(0), e.getY(0), e.metaState)
                        v.onTouchEvent(down)
                        down.recycle()
                        false
                    }
                    else -> grabbed != null
                }
            }
            map = m
        }
    }

    // Style: day or night. Switching style drops our layers, so they are re-added each time.
    LaunchedEffect(map, dark, mapVersion) {
        val m = map ?: return@LaunchedEffect
        val json = offlineMaps.styleJson(if (dark) "dark" else "light")
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            addOverlayLayers(s, dark)
            poiBadges.forEach { (name, bmp) -> s.addImage(name, bmp) }
            style = s
        }
    }

    // Active route line
    LaunchedEffect(style, routePoints, groundPreference) {
        val s = style ?: return@LaunchedEffect
        val color = when (groundPreference) {
            GroundPreference.OFFROAD -> "#D500F9"
            GroundPreference.GRAVEL -> "#FF9100"
            GroundPreference.MIXED -> "#00C853"
            GroundPreference.PAVED -> if (dark) "#00E5FF" else "#0091EA"
        }
        (s.getLayer("$ROUTE-line") as? LineLayer)?.setProperties(lineColor(color))
        if (previewRoutes.isEmpty()) {
            s.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(lineFeatures(if (routePoints.size >= 2) listOf(routePoints) else emptyList()))
        }
    }

    // Previewed alternatives: all of them dim, the selected one bright on top, its gravel and
    // tracks coloured over it.
    LaunchedEffect(style, previewRoutes) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(ROUTE_SURFACE)?.setGeoJson(surfaceRuns(previewRoutes.firstOrNull { it.selected }))
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
    // Where the map is looking, for the places kept on it. The following camera moves without ever
    // settling, so look now and then and report when the view has moved or zoomed enough to matter.
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
                onViewportState.value(b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast, m.cameraPosition.zoom)
            }
            kotlinx.coroutines.delay(800)
        }
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
            FeatureCollection.fromFeatures(markers.map { m ->
                Feature.fromGeometry(Point.fromLngLat(m.point.longitude, m.point.latitude)).also {
                    it.addStringProperty("label", m.label)
                    it.addStringProperty("color", when (m.kind) { PinKind.START -> PIN_START; PinKind.END -> PIN_END; PinKind.STOP -> PIN_STOP })
                    it.addStringProperty("text", if (m.kind == PinKind.END) "#FFFFFF" else "#0B0E14")
                    it.addNumberProperty("radius", if (m.kind == PinKind.STOP) 11f else 14f)
                }
            })
        )
    }

    LaunchedEffect(style, sketch) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SKETCH)?.setGeoJson(lineFeatures(listOf(if (sketch.size >= 3) sketch + sketch.first() else emptyList())))
    }

    LaunchedEffect(style, pickedPoint) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(PICK)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(pickedPoint).map { p -> Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)) })
        )
    }

    // Emptying an empty source still redraws the map, and location changes every second.
    var approachShown by remember { mutableStateOf(true) }
    LaunchedEffect(style, location, approachTo) {
        val s = style ?: return@LaunchedEffect
        val to = approachTo?.takeIf { haversine(location, it) > 20 }
        if (to == null && !approachShown) return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(APPROACH)?.setGeoJson(lineFeatures(listOfNotNull(to?.let { listOf(location, it) })))
        approachShown = to != null
    }
    LaunchedEffect(style) { approachShown = true }

    // Editing: the trip's places as handles. The dashed guess goes once the new route is in.
    LaunchedEffect(style, editor?.handles, dark) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(EDIT_HANDLES)?.setGeoJson(handleFeatures(editor?.handles.orEmpty(), dark))
    }
    LaunchedEffect(style, editor?.busy, editor == null, previewRoutes) {
        val s = style ?: return@LaunchedEffect
        if (editor?.busy != true) s.getSourceAs<GeoJsonSource>(EDIT_DRAG)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    // Preview: the camera stays where the rider puts it while they build or change the trip; the
    // fit button (zoom to extents) shows the whole of it. Only when a trip first appears and isn't
    // already in view is it fitted by itself. Leaving preview hands the camera back to following.
    val previewPoints = remember(previewRoutes) { previewRoutes.flatMap { it.points } }
    var fitTick by remember { mutableIntStateOf(0) }
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
    val inPreview = previewRoutes.isNotEmpty()
    // Back to following once the preview is really gone: not while picking places or editing, and
    // not for the moment the trip is empty while it is being worked out again (that flew the map
    // to the rider and back in the middle of planning).
    // A drawn area stays in view while its loop is worked out.
    val picking = onTap != null || editor != null || onSketch != null || sketch.isNotEmpty()
    // Drawing an area: flat, north up and zoomed out to about a day's ride round the rider.
    LaunchedEffect(map, onSketch != null) {
        val m = map ?: return@LaunchedEffect
        if (onSketch == null) return@LaunchedEffect
        following = false
        m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude)).zoom(minOf(m.cameraPosition.zoom, SKETCH_ZOOM))
            .bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), 800)
    }
    LaunchedEffect(inPreview, picking) {
        if (inPreview || picking) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        following = true
    }

    // Starting a route always brings the camera back to the rider.
    val hasRoute = routePoints.isNotEmpty()
    LaunchedEffect(hasRoute) { if (hasRoute) following = true }

    // Rider position + camera follow. Every frame, not every fix: the rider is carried forward
    // between the once-a-second fixes (RiderMotion) and the camera moves with them, so the map
    // glides along the road. Zoom, tilt and where the rider sits on screen ease towards their
    // targets, so changing speed or mode doesn't jolt the view.
    val motion = remember { com.curveon.data.location.RiderMotion() }
    LaunchedEffect(riderFix) { riderFix?.let { motion.onFix(it, System.nanoTime()) }; wake.trySend(Unit) }
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
    LaunchedEffect(map, style, following, inPreview, orientation, zoomBias, hasGpsFix, autoZoom) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val follow = following && !inPreview
        val threeD = orientation == Orientation.THREE_D
        // Start from where the camera is, so coming back to following is a glide too.
        var zoom = m.cameraPosition.zoom
        var tilt = m.cameraPosition.tilt
        var lift = m.cameraPosition.padding?.getOrNull(1) ?: 0.0
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
                val wantTilt = if (threeD) TILT_3D else 0.0
                // Heading up and 3D: the rider low on screen to see more road ahead. North up: centred.
                var wantLift = when {
                    threeD -> RIDER_LOW_3D * h
                    orientation == Orientation.HEADING_UP && moving -> RIDER_LOW_2D * h
                    else -> 0.0
                }
                // The rider sits at (height + lift) / 2: not so low that the arrow runs into the cards below.
                val clear = clearanceState.value.value * mapView.resources.displayMetrics.density
                if (clear > 0) wantLift = minOf(wantLift, (h - 2 * (clear + 40 * mapView.resources.displayMetrics.density)).coerceAtLeast(0.0))
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
                val changed = cam.target?.let { kotlin.math.abs(it.latitude - target.latitude) > 1e-8 || kotlin.math.abs(it.longitude - target.longitude) > 1e-8 } != false ||
                    kotlin.math.abs(cam.zoom - zoom) > 1e-3 || kotlin.math.abs(cam.tilt - tilt) > 0.05 ||
                    kotlin.math.abs(com.curveon.routing.Geo.angleDiff(cam.bearing, camBearing)) > 0.05 ||
                    kotlin.math.abs((cam.padding?.getOrNull(1) ?: 0.0) - lift) > 0.5
                settled = !changed
                if (changed) m.moveCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target)
                        .zoom(zoom)
                        .bearing(camBearing)
                        .tilt(tilt)
                        .padding(0.0, lift, 0.0, 0.0)
                        .build()
                ))
            }
            // The arrow is drawn over the map (RiderArrow), placed from the camera just set, so it
            // moves in step with the map. MapLibre's own location puck animates on its own clock
            // and can't take a new position every frame.
            val p = m.projection.toScreenLocation(target)
            if (!follow) riderScreen = androidx.compose.ui.geometry.Offset(p.x, p.y) // only shown once panned away
            val cam = m.cameraPosition
            val newPuck = Puck(p.x, p.y, ((pose?.bearingDeg ?: headingState.value.toDouble()) - cam.bearing).toFloat(), cam.tilt.toFloat())
            idle = settled && !moving && newPuck == puck
            puck = newPuck
            if (pose != null && now - lastTrace > 200_000_000L) {
                lastTrace = now
                com.curveon.data.location.NavTrace.drawn(pose.lat, pose.lon, pose.bearingDeg, m.cameraPosition.zoom, m.cameraPosition.tilt, follow)
            }
            lastPose = pose
        }
    }

    LaunchedEffect(style, cameraIdleTick) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasOfflineMap) return@LaunchedEffect // shown from the start
        val b = m.projection.visibleRegion.latLngBounds
        val zoom = m.cameraPosition.zoom
        val needed = withContext(Dispatchers.IO) {
            (0..4).any { i ->
                (0..4).any { j ->
                    !offlineMaps.covers(b.latitudeSouth + (b.latitudeNorth - b.latitudeSouth) * i / 4,
                        b.longitudeWest + (b.longitudeEast - b.longitudeWest) * j / 4, zoom)
                }
            }
        }
        val visibility = if (needed) Property.VISIBLE else Property.NONE
        if (s.isFullyLoaded && s.getLayer("online-land")?.visibility?.value != visibility) {
            android.util.Log.d("CurveonMap", "Online map ${if (needed) "on" else "off"} at zoom ${"%.1f".format(zoom)} $b")
            s.layers.filter { it.id.startsWith("online-") }.forEach { it.setProperties(visibility(visibility)) }
        }
    }

    // Gravel and soft roads, dashed over the map's own roads once zoomed in far enough to choose by them.
    var surfaceBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    LaunchedEffect(style, showSurfaces) {
        val s = style ?: return@LaunchedEffect
        val v = if (showSurfaces) Property.VISIBLE else Property.NONE
        s.layers.filter { it.id.startsWith(SURFACE) }.forEach { it.setProperties(visibility(v)) }
    }
    LaunchedEffect(style, cameraIdleTick, showSurfaces) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val fetch = surfaceWays?.takeIf { showSurfaces } ?: return@LaunchedEffect
        if (m.cameraPosition.zoom < SURFACE_ZOOM) return@LaunchedEffect
        val b = m.projection.visibleRegion.latLngBounds
        val have = surfaceBounds
        if (have != null && have.contains(b.northEast) && have.contains(b.southWest) && s.getSource(SURFACE) != null) return@LaunchedEffect
        // A margin around the view, so small pans need nothing new.
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.3
        val dLon = (b.longitudeEast - b.longitudeWest) * 0.3
        val area = LatLngBounds.from(b.latitudeNorth + dLat, b.longitudeEast + dLon, b.latitudeSouth - dLat, b.longitudeWest - dLon)
        val ways = runCatching { fetch(area.latitudeSouth, area.longitudeWest, area.latitudeNorth, area.longitudeEast) }
            .onFailure { android.util.Log.w("CurveonMap", "Surfaces not read", it) }.getOrNull() ?: return@LaunchedEffect
        val features = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            FeatureCollection.fromFeatures(ways.map { w ->
                Feature.fromGeometry(LineString.fromLngLats((w.coords.indices step 2).map { Point.fromLngLat(w.coords[it], w.coords[it + 1]) })).apply {
                    addStringProperty("kind", if (w.kind == com.curveon.routing.SurfaceKind.GRAVEL) "gravel" else "soft")
                    addNumberProperty("rank", w.rank)
                }
            })
        }
        if (style !== s || !s.isFullyLoaded) return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SURFACE)?.setGeoJson(features)
        surfaceBounds = area
    }
    LaunchedEffect(style) { surfaceBounds = null }

    // Road name after the next turn, read from the vector tiles once that spot is on screen.
    LaunchedEffect(map, style, roadNameProbe, cameraIdleTick, canQuery) {
        if (!canQuery) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val (index, point) = roadNameProbe ?: return@LaunchedEffect
        val screen = m.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
        if (screen.x < 0 || screen.y < 0 || screen.x > mapView.width || screen.y > mapView.height) return@LaunchedEffect
        val r = 24f
        val roadLayers = s.layers.map { it.id }.filter { it.startsWith("roads_") }.toTypedArray()
        val features = m.queryRenderedFeatures(RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r), *roadLayers)
        onRoadName(index, mapRoad(features))
    }

    // The road under the rider, for its number by the speed (abroad, where there's no road register).
    val onRoadHereState = rememberUpdatedState(onRoadHere)
    LaunchedEffect(map, style, canQuery, onRoadHere != null) {
        if (!canQuery || onRoadHere == null) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val roadLayers = s.layers.map { it.id }.filter { it.startsWith("roads_") }.toTypedArray()
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val p = puck ?: continue
            val r = 10f * mapView.resources.displayMetrics.density
            onRoadHereState.value?.invoke(mapRoad(m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), *roadLayers)))
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
        if (hasGpsFix && style != null) RiderArrow(arrowTipOnSpot) { puck }
        if (!following && !inPreview && hasGpsFix) riderScreen?.let { RiderEdgeGlow(it, dark, Modifier.matchParentSize()) }

        // Compass button: north up → heading up → 3D. Once the rider pans the map away, a second
        // button brings the camera back to them; it hides again while following. +/- are always
        // there: while following with auto-zoom they nudge the speed-based zoom, otherwise they zoom.
        if (!inPreview) Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(top = buttonsTopInset, bottom = buttonsBottomInset).padding(14.dp).tourTarget(TourTarget.MAP_BUTTONS),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MapButton(
                onClick = {
                    orientation = Orientation.entries[(orientation.ordinal + 1) % Orientation.entries.size]
                    prefs.edit().putString(if (navigating) "orientationNav" else "orientationBrowse", orientation.name).apply()
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

        // Zoom to extents: the whole trip being planned, at a tap.
        if (inPreview && hasPreviewTrip) Box(Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 14.dp)) {
            MapButton(onClick = { fitTick++ }, tag = "map_fit_trip_btn") {
                Icon(Icons.Default.ZoomOutMap, stringResource(R.string.map_fit_trip), tint = TextPrimary, modifier = Modifier.size(24.dp))
            }
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

/** Whether the device has a working internet connection right now; follows changes. */
@Composable
private fun rememberOnline(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cm = remember { context.getSystemService(android.net.ConnectivityManager::class.java) }
    fun now() = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    var online by remember { mutableStateOf(now()) }
    DisposableEffect(cm) {
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { online = now() }
            override fun onLost(network: android.net.Network) { online = now() }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return online
}

private const val AMBIENT_CACHE_BYTES = 300L * 1024 * 1024

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

/**
 * Gravel (dashed grey) and softer or unknown ground (dashed brown) over the map's roads, under
 * the road names, in quiet colours that sit with the map's own roads. Wider for bigger roads, like
 * the map draws them.
 */
private fun addSurfaceLayers(s: Style, dark: Boolean) {
    s.addSource(GeoJsonSource(SURFACE))
    fun width(base: Float) = E.interpolate(E.linear(), E.zoom(),
        E.stop(SURFACE_ZOOM, E.product(E.literal(base * 0.6f), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))),
        E.stop(14, E.product(E.literal(base), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))),
        E.stop(18, E.product(E.literal(base * 3f), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))))
    val gravel = E.eq(E.get("kind"), E.literal("gravel"))
    val soft = E.eq(E.get("kind"), E.literal("soft"))
    val layers = listOf(
        LineLayer("$SURFACE-soft", SURFACE).withProperties(
            lineColor(if (dark) SurfaceColors.SOFT_DARK else SurfaceColors.SOFT_LIGHT), lineWidth(width(2.2f)), lineDasharray(arrayOf(2f, 1.5f))
        ).withFilter(soft),
        LineLayer("$SURFACE-gravel", SURFACE).withProperties(
            lineColor(if (dark) SurfaceColors.GRAVEL_DARK else SurfaceColors.GRAVEL_LIGHT), lineWidth(width(2.8f)), lineDasharray(arrayOf(2.5f, 1.5f))
        ).withFilter(gravel)
    )
    // Under the downloaded map's first label layer, so road names stay on top (the online map's
    // layers, labels included, all lie underneath).
    val firstLabel = s.layers.firstOrNull { it is org.maplibre.android.style.layers.SymbolLayer && !it.id.startsWith("online-") }?.id
    layers.forEach { l ->
        l.minZoom = SURFACE_ZOOM
        if (firstLabel != null) s.addLayerBelow(l, firstLabel) else s.addLayer(l)
    }
}

private fun addOverlayLayers(s: Style, dark: Boolean) {
    addSurfaceLayers(s, dark)
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
    s.addSource(GeoJsonSource(APPROACH))
    s.addLayer(
        LineLayer("$APPROACH-line", APPROACH).withProperties(
            lineColor(if (dark) "#00E5FF" else "#0091EA"), lineWidth(4f), lineOpacity(0.85f),
            lineDasharray(arrayOf(0.6f, 1.6f)), lineCap(Property.LINE_CAP_ROUND)
        )
    )
    s.addLayer(
        LineLayer("$ALT-line", ALT).withProperties(
            lineColor(if (dark) "#5A6B7D" else "#8FA3B8"), lineWidth(6f), lineOpacity(0.85f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    // The line to follow: thick when riding zoomed in, thinner zoomed out so the map still shows.
    fun routeWidth(z10: Float, z14: Float, z17: Float) = lineWidth(
        org.maplibre.android.style.expressions.Expression.interpolate(
            org.maplibre.android.style.expressions.Expression.linear(), org.maplibre.android.style.expressions.Expression.zoom(),
            org.maplibre.android.style.expressions.Expression.stop(10, z10),
            org.maplibre.android.style.expressions.Expression.stop(14, z14),
            org.maplibre.android.style.expressions.Expression.stop(17, z17)
        )
    )
    s.addLayer(
        LineLayer("$ROUTE-casing", ROUTE).withProperties(
            lineColor(if (dark) "#00363D" else "#FFFFFF"), routeWidth(10f, 16f, 23f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addSource(GeoJsonSource(ROUTE_SURFACE))
    s.addLayer(
        LineLayer("$ROUTE-line", ROUTE).withProperties(
            lineColor(if (dark) "#00E5FF" else "#0091EA"), routeWidth(6.5f, 11f, 17f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer("$ROUTE_SURFACE-line", ROUTE_SURFACE).withProperties(
            lineColor(org.maplibre.android.style.expressions.Expression.match(
                org.maplibre.android.style.expressions.Expression.get("kind"),
                org.maplibre.android.style.expressions.Expression.literal(GRAVEL_COLOR),
                org.maplibre.android.style.expressions.Expression.stop(SURFACE_TRACK, TRACK_COLOR))),
            routeWidth(6.5f, 11f, 17f), lineCap(Property.LINE_CAP_BUTT), lineJoin(Property.LINE_JOIN_ROUND)
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
    // Where the trip starts (A, green), its stops (1, 2, …, orange) and where it ends (B, red).
    s.addLayer(
        CircleLayer("$MARKERS-dots", MARKERS).withProperties(
            circleRadius(org.maplibre.android.style.expressions.Expression.get("radius")),
            circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
        )
    )
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$MARKERS-labels", MARKERS).withProperties(
            textField(org.maplibre.android.style.expressions.Expression.get("label")),
            textFont(arrayOf("Noto Sans Medium")), textSize(15f),
            textColor(org.maplibre.android.style.expressions.Expression.get("text")),
            textAllowOverlap(true), textIgnorePlacement(true)
        )
    )
    addEditLayers(s, dark)
    s.addSource(GeoJsonSource(SKETCH))
    s.addLayer(
        LineLayer("$SKETCH-line", SKETCH).withProperties(
            lineColor("#FF9100"), lineWidth(5f), lineOpacity(0.9f),
            lineDasharray(arrayOf(1.2f, 1.2f)), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addSource(GeoJsonSource(PICK))
    s.addLayer(
        CircleLayer("$PICK-dot", PICK).withProperties(
            circleRadius(13f), circleColor(if (dark) "#00E5FF" else "#0091EA"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(4f)
        )
    )
}

/** What a pin on the map marks. */
enum class PinKind { START, STOP, END }

/** A place of the trip on the map: [label] is "A", "B" or the number of the stop. */
data class MapPin(val point: GeoPoint, val kind: PinKind, val label: String)

private const val PIN_START = "#00E676"
private const val PIN_STOP = "#FF9100"
private const val PIN_END = "#FF1744"

/** Orange for gravel, purple for tracks: as the gravel and offroad route colours. */
const val GRAVEL_COLOR = "#FF9100"
const val TRACK_COLOR = "#D500F9"

/** The unpaved stretches of [route], one line per run of the same kind. */
private fun surfaceRuns(route: MapRoute?): FeatureCollection {
    val kinds = route?.surfaces ?: return FeatureCollection.fromFeatures(emptyList())
    val pts = route.points
    val out = ArrayList<Feature>()
    var i = 0
    while (i < pts.size - 1) {
        val kind = kinds.getOrElse(i + 1) { SURFACE_PAVED }
        var j = i + 1
        while (j < pts.size - 1 && kinds.getOrElse(j + 1) { SURFACE_PAVED } == kind) j++
        if (kind != SURFACE_PAVED) out += Feature.fromGeometry(LineString.fromLngLats(pts.subList(i, j + 1).map { Point.fromLngLat(it.longitude, it.latitude) }))
            .also { it.addNumberProperty("kind", kind) }
        i = j
    }
    return FeatureCollection.fromFeatures(out)
}

private fun lineFeatures(lines: List<List<GeoPoint>>): FeatureCollection =
    FeatureCollection.fromFeatures(lines.filter { it.size >= 2 }.map { pts ->
        Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }))
    })

/** Number, class and name of the road among [features] (the first with a number, else the first). */
internal fun mapRoad(features: List<Feature>): com.curveon.data.road.MapRoad? {
    val f = features.firstOrNull { !it.getStringProperty("ref").isNullOrBlank() } ?: features.firstOrNull() ?: return null
    val name = features.firstNotNullOfOrNull { it.getStringProperty("name")?.takeIf { n -> n.isNotBlank() } }
    return com.curveon.data.road.MapRoad(f.getStringProperty("ref")?.takeIf { it.isNotBlank() }, f.getStringProperty("kind_detail"), name)
}

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

/**
 * How close to follow the rider. Like the usual car navigators (Google Maps, Mapbox's navigation
 * camera): in close when slow and twisty, out on fast roads so what's ahead stays in view, and in
 * again approaching a turn so the junction is big enough to read. Smooth in speed, so riding
 * around a threshold doesn't pump the zoom.
 */
private fun followZoom(speedKmh: Float, toTurnMeters: Int?, threeD: Boolean): Double {
    val bySpeed = when {
        speedKmh < 8f -> 16.5 // standing still: a bit of overview
        else -> 17.2 - ((speedKmh - 20f) / 70f).coerceIn(0f, 1f) * 1.6 // 17.2 at 20 km/h → 15.6 at 90
    }
    val nearTurn = toTurnMeters != null && toTurnMeters in 1..300 && speedKmh >= 8f
    val zoom = if (nearTurn) maxOf(bySpeed, 16.8) else bySpeed
    // Tilted, the road ahead runs off into the distance: a touch closer keeps the rider's
    // surroundings at about the same scale as the flat view.
    return zoom + if (threeD) 0.3 else 0.0
}

/** Moves [from] towards [to], [tau] seconds being roughly how long it takes to get two thirds there. */
private fun ease(from: Double, to: Double, dt: Double, tau: Double) = from + (to - from) * (1 - kotlin.math.exp(-dt / tau))

/** The rider arrow on screen: position in px, rotation from screen-up, and the map's tilt. */
private data class Puck(val x: Float, val y: Float, val rotation: Float, val tilt: Float)

/**
 * The rider arrow, drawn over the map. Read through [puck] inside the layer block, so moving it
 * every frame only redraws the arrow. Tilted with the map in 3D so it lies on the road.
 */
@Composable
private fun RiderArrow(tipOnSpot: Boolean, puck: () -> Puck?) {
    val size = 44.dp
    val half = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() / 2 }
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.curveon.R.drawable.ic_nav_arrow),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val p = puck()
                alpha = if (p == null) 0f else 1f
                if (p != null) {
                    translationX = p.x - half
                    translationY = p.y - half
                    rotationX = p.tilt * 0.8f
                    cameraDistance = 12f * density
                }
            }
            .graphicsLayer { rotationZ = puck()?.rotation ?: 0f }
            // Inside the turn, so it moves back along the arrow: the tip (1/48 from the top of
            // ic_nav_arrow) lands on the spot.
            .graphicsLayer { translationY = if (tipOnSpot) half * 23f / 24f else 0f }
    )
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
 * so it's clear at a glance where "me" went after panning around. Cyan alone vanishes into the day
 * map's light blue water, so there it sits in a dark ring, like the rider arrow's dark outline.
 */
@Composable
private fun RiderEdgeGlow(rider: androidx.compose.ui.geometry.Offset, dark: Boolean, modifier: Modifier) {
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
        // A small cap peeking in from the edge: the circle sits partly beyond it. On the day map the
        // cyan centre fades into a dark ring, on the night map straight into the map.
        val r = radius * 0.55f
        val at = edge + d / d.getDistance() * (r * 0.3f)
        val stops = if (dark) arrayOf(
            0f to ElectricCyan.copy(alpha = near),
            0.35f to ElectricCyan.copy(alpha = 0.8f * near),
            0.7f to ElectricCyan.copy(alpha = 0.2f * near),
            1f to Color.Transparent
        ) else arrayOf(
            0f to ElectricCyan.copy(alpha = near),
            0.35f to ElectricCyan.copy(alpha = 0.9f * near),
            0.55f to CockpitBackground.copy(alpha = 0.6f * near),
            1f to Color.Transparent
        )
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(*stops, center = at, radius = r), radius = r, center = at)
    }
}

private fun haversine(a: GeoPoint, b: GeoPoint): Double =
    com.curveon.routing.Geo.distance(a.latitude, a.longitude, b.latitude, b.longitude)
