package com.curveon.ui.components

import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.curveon.data.model.GeoPoint
import com.curveon.ui.theme.*
import kotlinx.coroutines.flow.first
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/** What the map's listeners read: registered once, they see the newest values through these. */
internal class MapCallbacks(
    val locationState: State<GeoPoint>,
    val onLongPressState: State<(GeoPoint, String?, com.curveon.data.road.MapRoad?, List<Pair<String, String>>) -> Unit>,
    val onTapState: State<((GeoPoint) -> Unit)?>,
    val editorState: State<RouteEditor?>,
    val onSketchState: State<((List<GeoPoint>) -> Unit)?>,
    val onSketchProgressState: State<(List<GeoPoint>) -> Unit>,
    val onEditRouteState: State<(() -> Unit)?>,
    val previewRoutesState: State<List<MapRoute>>,
    val onPoiClickState: State<(com.curveon.data.gpx.GpxPoi) -> Unit>,
    val onGroupRiderClickState: State<(List<String>) -> Unit>,
    val poisState: State<List<com.curveon.data.gpx.GpxPoi>>,
)

/** The map once it is ready: its settings and camera, and what the camera, taps, long presses and fingers on it do. */
@Composable
internal fun CurveonMapState.MapListeners(
    location: GeoPoint,
    onLongPress: (GeoPoint, String?, com.curveon.data.road.MapRoad?, List<Pair<String, String>>) -> Unit,
    onTap: ((GeoPoint) -> Unit)?,
    editor: RouteEditor?,
    onSketch: ((List<GeoPoint>) -> Unit)?,
    onSketchProgress: (List<GeoPoint>) -> Unit,
    onEditRoute: (() -> Unit)?,
    previewRoutes: List<MapRoute>,
    onPoiClick: (com.curveon.data.gpx.GpxPoi) -> Unit,
    onGroupRiderClick: (List<String>) -> Unit,
    gpxPois: List<com.curveon.data.gpx.GpxPoi>,
) {
    val context = LocalContext.current
    // Map listeners are registered once, so read changing values through State holders.
    val cb = MapCallbacks(
        rememberUpdatedState(location), rememberUpdatedState(onLongPress), rememberUpdatedState(onTap), rememberUpdatedState(editor),
        rememberUpdatedState(onSketch), rememberUpdatedState(onSketchProgress), rememberUpdatedState(onEditRoute),
        rememberUpdatedState(previewRoutes), rememberUpdatedState(onPoiClick), rememberUpdatedState(onGroupRiderClick), rememberUpdatedState(gpxPois)
    )
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
            listenToCamera(m, cb)
            listenToTaps(m, cb)
            listenToLongPress(m, cb)
            listenToFingers(m, cb, context)
            map = m
        }
    }
}

/** The camera moved by a finger stops following; while it moves and when it settles, what depends on it is told. */
internal fun CurveonMapState.listenToCamera(m: MapLibreMap, cb: MapCallbacks) = with(cb) {
    m.addOnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
            following = false
            if (!fingerMoving) { fingerMoving = true; mapView.setMaximumFps(FINGER_FPS) }
        }
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
        if (trackZoom) (m.cameraPosition.zoom * 10).toInt().let { if (it != zoomTenths) zoomTenths = it }
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
        if (fingerMoving) { fingerMoving = false; mapView.setMaximumFps(MAX_FPS) }
        if (!following) { trackRider(); viewportWake.trySend(Unit) }
        mapView.removeCallbacks(idleTick)
        val wait = lastIdleTick + IDLE_TICK_MS - android.os.SystemClock.uptimeMillis()
        if (wait <= 0) idleTick.run() else mapView.postDelayed(idleTick, wait)
    }
}

/** A tap: a place being picked, another rider or a friend, or a GPX waypoint. */
internal fun CurveonMapState.listenToTaps(m: MapLibreMap, cb: MapCallbacks) = with(cb) {
    m.addOnMapClickListener { p ->
        onTapState.value?.let { tap -> tap(GeoPoint(p.latitude, p.longitude)); return@addOnMapClickListener true }
        // A tap on a GPX waypoint shows it. Only the rail button switches map/dashboard,
        // so a stray tap on the map never takes the dashboard away.
        if (!canQuery) return@addOnMapClickListener false
        val pt = m.projection.toScreenLocation(p)
        // Another rider or a friend: what is known about them, and a way to get their attention. Riders standing
        // together are one marker: all of them.
        m.queryRenderedFeatures(
            RectF(pt.x - 28, pt.y - 28, pt.x + 28, pt.y + 28),
            "$GROUP-dots", "$GROUP-photos", "$GROUP-labels", "$GROUP-photo-labels", "$GROUP-clusters", "$GROUP-cluster-labels"
        ).firstOrNull()?.let { f ->
            val ids = if (f.hasProperty("point_count")) runCatching {
                m.style?.getSourceAs<GeoJsonSource>(GROUP)?.getClusterLeaves(f, 50, 0)?.features()?.mapNotNull { it.getStringProperty("id") }
            }.getOrNull().orEmpty() else listOfNotNull(f.getStringProperty("id"))
            if (ids.isNotEmpty()) { onGroupRiderClickState.value(ids); return@addOnMapClickListener true }
        }
        val hit = m.queryRenderedFeatures(RectF(pt.x - 24, pt.y - 24, pt.x + 24, pt.y + 24), "$GPX_POIS-dots", "$GPX_POIS-icons").firstOrNull()
        val poi = hit?.getNumberProperty("i")?.toInt()?.let { poisState.value.getOrNull(it) }
        if (poi != null) onPoiClickState.value(poi)
        poi != null
    }
}

/** A finger held on the map (or the remote's "what's here"): the place, the road and the tracks there, or editing the route. */
internal fun CurveonMapState.listenToLongPress(m: MapLibreMap, cb: MapCallbacks) = with(cb) {
    val pressAt: (LatLng) -> Unit = { p ->
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
                // And the GPX tracks drawn there, so the card can say which one this is.
                val tracks = if (!canQuery) emptyList() else runCatching {
                    m.queryRenderedFeatures(RectF(pt.x - 20, pt.y - 20, pt.x + 20, pt.y + 20), "$GPX-line")
                        .mapNotNull { f -> f.getStringProperty("name")?.let { it to (f.getStringProperty("color") ?: "#FFFFFF") } }
                        .distinctBy { it.first }.take(3)
                }.getOrNull().orEmpty()
                onLongPressState.value(at, roadLabel(roads), mapRoad(roads), tracks)
            }
        }
    }
    longPressAt.value = pressAt
    m.addOnMapLongClickListener { p -> pressAt(p); true }
}

/**
 * Fingers on the map while editing a route (a handle or the line dragged, a part of it marked) or drawing an area;
 * anywhere else the map moves as usual.
 */
internal fun CurveonMapState.listenToFingers(m: MapLibreMap, cb: MapCallbacks, context: android.content.Context) = with(cb) {
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
}
