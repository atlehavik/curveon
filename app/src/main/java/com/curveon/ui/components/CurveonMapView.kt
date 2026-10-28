package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
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
import kotlinx.coroutines.flow.first
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
import org.maplibre.android.style.layers.HeatmapLayer
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
private const val GROUP = "curveon-group"
/** Riders closer than this on screen (about a marker's width) are shown as one marker. */
private const val GROUP_CLUSTER_PX = 24
/** The source's own max zoom: further in, the map only enlarges what was made here, so riders on the very same spot stay one marker. */
private const val GROUP_CLUSTER_MAX_ZOOM = 18
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
private const val GIVE_WAY = "curveon-give-way"
private const val GIVE_WAY_IMAGE = "curveon-give-way-sign"
private const val SPEED_SIGNS = "curveon-speed-signs"
/** The weather round the rider while a loop is drawn. */
private const val WEATHER = "curveon-weather"
private const val STOP_SIGN_IMAGE = "curveon-stop-sign"
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
private const val HEAT = "curveon-gravel-heat"

/**
 * How gravel and soft ground are shown on the map (Settings → Display → Road surfaces): not at all,
 * as the road itself dashed (the road's own colour, never wider than it; brown for soft ground), or
 * coloured like the route is (orange gravel, purple soft ground).
 */
enum class SurfaceMode(val label: Int) {
    OFF(R.string.settings_surfaces_off), DASHED(R.string.surface_mode_dashed), COLOURED(R.string.surface_mode_coloured);

    /** The dash colour for gravel: the colour of the small roads on the day or night map ([road]). */
    fun gravel(road: String = com.curveon.data.map.OfflineMaps.HC_ROAD) = if (this == COLOURED) GRAVEL_COLOR else road
    /** The dash colour for soft ground (the route's track colour when coloured). */
    fun soft(dark: Boolean) = when { this == COLOURED -> TRACK_COLOR; dark -> "#A07850"; else -> "#7A5230" }
}

/** Settings → Display: whether the gravel heat map's button shows (planning and on the map); off for riders who don't look for gravel. */
enum class GravelHeatButton { OFF, MAP }

/** How much gravel there is where, for the heat map while planning (see [com.curveon.routing.GravelHeat]). */
interface GravelHeatSource {
    /** The routing data files covering the area (only those on the device). */
    suspend fun tiles(south: Double, west: Double, north: Double, east: Double): List<String>
    /** One file's cells; seconds the first time, then at once. */
    suspend fun cells(tile: String): List<com.curveon.routing.HeatCell>
}
/**
 * Gravel and soft roads from this zoom in (about 10 km across the X7's map) when the map is set to
 * respond fast, one step further out with more detail ([com.curveon.ui.viewmodel.MapDetail]). Further out they would clutter. The line widths
 * start here either way (they hold their width below it).
 */
private const val SURFACE_ZOOM = 11f
/** From this zoom the map's own tiles have the small roads. */
private const val MAP_ROADS_ZOOM = 12.2f
private const val FOLLOW_ZOOM = 15.5
/** 3D tilt; car navigators sit around 45–50 (Mapbox's navigation camera: 45). */
private const val TILT_3D = 55.0
/** The rider's own 3D angle: 0 is looking straight down, higher looks more along the road. Steps of the +/- buttons. */
private const val TILT_MIN = 20.0
private const val TILT_MAX = 60.0 // MapLibre 13.6.1 refuses more (checked: 61 is rejected)
private const val TILT_STEP = 5.0
/** The tilt arrows show when 3D is chosen and hide again after this long without a press. */
private const val TILT_CONTROLS_MS = 6_000L
/** Rider position below the middle, as a share of the map's height, given as top padding (×2 lower). */
private const val RIDER_LOW_3D = 0.6 // rider ~4/5 down the map
private const val RIDER_LOW_2D = 0.36

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

/** Another rider in the active group, drawn as a coloured ring with their name. [stale] when not heard from for a while. */
data class MapGroupRider(val id: String, val point: GeoPoint, val label: String, val color: Int, val stale: Boolean, val avatar: android.graphics.Bitmap? = null)

/**
 * The main map: MapLibre rendering the offline Norway vector map (day or night style), the
 * rider, the active route or the previewed alternatives, and stop markers.
 */
@Composable
fun CurveonMapView(
    location: GeoPoint,
    /** The latest fix, carried forward frame by frame so the rider glides instead of hopping. */
    riderFix: com.curveon.data.location.RiderMotion.Fix?,
    /** The position is simulated (demo ride): a DEMO tag sits at the arrow. */
    demoRide: Boolean = false,
    /** The DEMO tag is tapped: the demo's tricks (lose the signal, take a wrong turn). */
    onDemoTap: () -> Unit = {},
    /** Settings → Display: the day map with dark grey roads and black road edges. */
    headingDeg: Float,
    speedKmh: Float,
    speedLimitKmh: Int,
    hasGpsFix: Boolean,
    /** The GPS signal is lost (or the position only guessed, in a tunnel): the arrow is a ghost with a no-satellite badge. */
    gpsLost: Boolean = false,
    routePoints: List<GeoPoint>,
    /** What is underfoot at each of [routePoints] (see [MapRoute]): gravel and tracks are drawn solid over the route's line. */
    routeSurfaces: List<Int>? = null,
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
    /** The day map's small roads and light paths (Settings → Display → Roads). */
    roadShade: Pair<String, String> = com.curveon.data.map.OfflineMaps.HC_ROAD to com.curveon.data.map.OfflineMaps.HC_PATH,
    previewRoutes: List<MapRoute> = emptyList(),
    markers: List<MapPin> = emptyList(),
    /** The other riders in the active group. */
    groupRiders: List<MapGroupRider> = emptyList(),
    roadNameProbe: Pair<Int, GeoPoint>? = null,
    onRoadName: (Int, com.curveon.data.road.MapRoad?) -> Unit = { _, _ -> },
    /** Called now and then with the road under the rider as the tiles have it; null = don't read it. */
    onRoadHere: ((com.curveon.data.road.MapRoad?) -> Unit)? = null,
    /** Called every second with where the rider is and the road lines the map draws round them; null = don't read them. */
    onRoadsNear: ((com.curveon.routing.LatLon, List<List<com.curveon.routing.LatLon>>) -> Unit)? = null,
    gpxTracks: List<com.curveon.data.gpx.OverlayTrack> = emptyList(),
    gpxPois: List<com.curveon.data.gpx.GpxPoi> = emptyList(),
    /** Give way and stop signs that apply to the rider, stood beside the road before their junctions. */
    giveWaySigns: List<com.curveon.data.routing.GiveWayAlert> = emptyList(),
    /** Where the speed limit changes ahead on the route: a sign on the right of the road. */
    speedSigns: List<com.curveon.data.routing.SpeedChange> = emptyList(),
    /** Drawing a loop: the weather round where it starts (sun, cloud, rain, wind and how warm), to draw where it is dry. */
    weatherSpots: List<com.curveon.data.weather.MapWeather> = emptyList(),
    /** The weather map button (full-screen map, not navigating, standing still): on or off, and switching it; null = no button. */
    weatherMapOn: Boolean = false,
    onWeatherMap: ((Boolean) -> Unit)? = null,
    /** Whether the weather button can be seen now, so the weather is only fetched then. */
    onWeatherButtonShown: (Boolean) -> Unit = {},
    trackHint: com.curveon.data.gpx.TrackHint? = null,
    onPoiClick: (com.curveon.data.gpx.GpxPoi) -> Unit = {},
    /** A tap on another rider or a friend on the map: their [MapGroupRider.id], or several where they stand together. */
    onGroupRiderClick: (List<String>) -> Unit = {},
    /** Where the map is looking once it settles: south, north, west, east, and the zoom. */
    /**
     * Where the map is looking: its bounds, and how wide the middle of the screen is ([widthKm]) with where the camera is
     * ([centerLat], [centerLon]). In 3D the bounds reach the horizon and say little about how zoomed in the map is.
     */
    /** Whether the gravel heat button is on the map (top left), so the road card goes under it. */
    onHeatButtonShown: (Boolean) -> Unit = {},
    onViewport: (south: Double, north: Double, west: Double, east: Double, zoom: Double, widthKm: Double, centerLat: Double, centerLon: Double) -> Unit =
        { _, _, _, _, _, _, _, _ -> },
    /** Zoom follows speed (in when slow and twisty, out on fast roads). */
    autoZoom: Boolean = true,
    /** Test: the arrow's tip, not its middle, where the rider is. */
    /** Changes when the offline map file was downloaded/replaced/deleted: reload the style. */
    mapVersion: Int = 0,
    /**
     * A finger held on the map: the place, the road there as the map labels it, its number, class
     * and name (null if none), and the GPX tracks shown there (name and colour, "#RRGGBB").
     */
    onLongPress: (GeoPoint, String?, com.curveon.data.road.MapRoad?, List<Pair<String, String>>) -> Unit = { _, _, _, _ -> },
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
    surfaceMode: SurfaceMode = SurfaceMode.DASHED,
    /** Settings → Display → Map detail: the zoom the gravel dashes and small roads start at. */
    surfaceZoom: Float = SURFACE_ZOOM,
    /** Settings → Display: the dashes' colours, and whether only while planning (quiet otherwise). */
    /** Where the gravel is, as a heat map, with its button while planning (and [showGravelHeat] on). */
    gravelHeat: GravelHeatSource? = null,
    showGravelHeat: Boolean = false,
    /** Settings → Display: the heat map button also on the ordinary map (not while riding). */
    gravelHeatOnMap: Boolean = false,
    /** A trip is being planned (its sheet is open, even before a route has come back). */
    planningTrip: Boolean = false,
    onShowGravelHeat: ((Boolean) -> Unit)? = null,
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
    // The angle of the 3D view (remembered): changed with the +/- that come beside the 3D button when it is held down.
    var tilt3d by remember { mutableStateOf(prefs.getFloat("tilt3d", TILT_3D.toFloat()).toDouble().coerceIn(TILT_MIN, TILT_MAX)) }
    var tiltControls by remember { mutableStateOf(false) }
    var tiltPresses by remember { mutableIntStateOf(0) }
    LaunchedEffect(tiltControls, tiltPresses) {
        if (!tiltControls) return@LaunchedEffect
        kotlinx.coroutines.delay(TILT_CONTROLS_MS)
        tiltControls = false
    }
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
    val onGroupRiderClickState = rememberUpdatedState(onGroupRiderClick)
    val onViewportState = rememberUpdatedState(onViewport)
    val poisState = rememberUpdatedState(gpxPois)
    val poiBadges = rememberPoiBadges()
    val signImages = remember { roadSignImages(context.resources.displayMetrics.density) }

    val mapView = remember {
        MapLibre.getInstance(context)
        // Online map tiles seen once stay usable offline (MapLibre's default cache is 50 MB).
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, null)
        // Android 14 and later: drawn in a TextureView. The SurfaceView deadlocked on the Pixel (Android 17, three
        // "not responding" on 2026-10-21): coming back to the app resizes the window, the main thread waits for the
        // map to draw at the new size, and the map's eglSwapBuffers waits for the main thread (BLASTBufferQueue).
        // A TextureView draws with the rest of the screen and has no such wait; it costs a little more GPU and
        // battery (one more copy a frame). The X7 (Android 13) has never shown it and keeps the SurfaceView.
        val options = org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context)
            .textureMode(android.os.Build.VERSION.SDK_INT >= 34)
        MapView(context, options).apply { onCreate(null) }
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
                        // And the GPX tracks drawn there, so the card can say which one this is.
                        val tracks = if (!canQuery) emptyList() else runCatching {
                            m.queryRenderedFeatures(RectF(pt.x - 20, pt.y - 20, pt.x + 20, pt.y + 20), "$GPX-line")
                                .mapNotNull { f -> f.getStringProperty("name")?.let { it to (f.getStringProperty("color") ?: "#FFFFFF") } }
                                .distinctBy { it.first }.take(3)
                        }.getOrNull().orEmpty()
                        onLongPressState.value(at, roadLabel(roads), mapRoad(roads), tracks)
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
    LaunchedEffect(map, dark, mapVersion, roadShade) {
        val m = map ?: return@LaunchedEffect
        val json = offlineMaps.styleJson(if (dark) "dark" else "light", roadShade.first, roadShade.second)
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            addOverlayLayers(s, dark)
            poiBadges.forEach { (name, bmp) -> s.addImage(name, bmp) }
            signImages.forEach { (name, bmp) -> s.addImage(name, bmp) }
            style = s
        }
    }

    // Active route line
    // The line is the asphalt blue; gravel and tracks lie solid over it (the route surface layer).
    LaunchedEffect(style, routePoints) {
        val s = style ?: return@LaunchedEffect
        if (previewRoutes.isEmpty()) {
            // A long route is a couple of hundred thousand points: made into GeoJSON off the main thread.
            val features = withContext(Dispatchers.Default) { lineFeatures(if (routePoints.size >= 2) listOf(routePoints) else emptyList()) }
            // A preview that came meanwhile draws the route line itself.
            if (style === s && s.isFullyLoaded && previewRoutesState.value.isEmpty()) s.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(features)
        }
    }

    // Previewed alternatives: all of them dim, the selected one bright on top, its gravel and
    // tracks coloured over it.
    LaunchedEffect(style, previewRoutes) {
        val s = style ?: return@LaunchedEffect
        val selected = previewRoutes.filter { it.selected }.map { it.points }
        val others = previewRoutes.filter { !it.selected }.map { it.points }
        val (alt, line) = withContext(Dispatchers.Default) {
            Pair(
                lineFeatures(others),
                lineFeatures(if (previewRoutes.isNotEmpty()) selected else if (routePoints.size >= 2) listOf(routePoints) else emptyList())
            )
        }
        if (style !== s || !s.isFullyLoaded) return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(ALT)?.setGeoJson(alt)
        s.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(line)
    }

    // Orange gravel and purple tracks over the line: of the selected preview, else of the route being ridden.
    // Then the map's own see-through dashes over the route are not wanted.
    LaunchedEffect(style, previewRoutes, routePoints, routeSurfaces) {
        val s = style ?: return@LaunchedEffect
        val shown = previewRoutes.firstOrNull { it.selected } ?: if (previewRoutes.isEmpty() && routeSurfaces != null && routePoints.size >= 2) MapRoute(routePoints, true, routeSurfaces) else null
        val runs = withContext(Dispatchers.Default) { surfaceRuns(shown) }
        if (style !== s || !s.isFullyLoaded) return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(ROUTE_SURFACE)?.setGeoJson(runs)
        val dashes = if (previewRoutes.isEmpty() && routePoints.size >= 2) Property.NONE else Property.VISIBLE
        for (kind in listOf("soft", "gravel")) for (suffix in listOf("", "-casing"))
            s.getLayer("$SURFACE-over-$kind$suffix")?.setProperties(visibility(dashes))
    }

    // GPX overlays: tracks in their colour with direction arrows, and their waypoints.
    LaunchedEffect(style, gpxTracks) {
        val s = style ?: return@LaunchedEffect
        // Every shown track, a country's worth of points at times: made into GeoJSON off the main thread.
        val features = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(gpxTracks.filter { it.points.size >= 2 }.map { t ->
                Feature.fromGeometry(LineString.fromLngLats(t.points.map { Point.fromLngLat(it.longitude, it.latitude) })).also {
                    it.addStringProperty("color", hex(t.color))
                    it.addStringProperty("name", t.name)
                }
            })
        }
        if (style === s && s.isFullyLoaded) s.getSourceAs<GeoJsonSource>(GPX)?.setGeoJson(features)
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
            kotlinx.coroutines.delay(800)
            lifecycleOwner.lifecycle.untilStarted()
        }
    }
    // Give way signs: on the right of the road, a few metres before the junction, as they stand.
    LaunchedEffect(style, giveWaySigns) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(GIVE_WAY)?.setGeoJson(FeatureCollection.fromFeatures(giveWaySigns.map { g ->
            val back = com.curveon.routing.Geo.destination(com.curveon.routing.LatLon(g.lat, g.lon), g.bearingDeg + 180, 14.0)
            val at = com.curveon.routing.Geo.destination(back, g.bearingDeg + 90, 6.0)
            Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)).also {
                it.addStringProperty("icon", if (g.stop) STOP_SIGN_IMAGE else GIVE_WAY_IMAGE)
            }
        }))
    }
    // Speed limit signs where the limit changes, on the right of the road as they stand.
    LaunchedEffect(style, speedSigns) {
        val s = style ?: return@LaunchedEffect
        val density = context.resources.displayMetrics.density
        speedSigns.map { it.limit }.distinct().forEach { l -> if (s.getImage(speedSignImageName(l)) == null) s.addImage(speedSignImageName(l), speedSignImage(l, density)) }
        s.getSourceAs<GeoJsonSource>(SPEED_SIGNS)?.setGeoJson(FeatureCollection.fromFeatures(speedSigns.map { c ->
            val at = com.curveon.routing.Geo.destination(com.curveon.routing.LatLon(c.lat, c.lon), c.bearingDeg + 90.0, 6.0)
            Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)).also { it.addStringProperty("icon", speedSignImageName(c.limit)) }
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

    // Riders with a picture get it as a round marker; the image is made once per picture and per style.
    val groupImages = remember(style) { HashMap<String, android.graphics.Bitmap>() }
    val groupDensity = androidx.compose.ui.platform.LocalDensity.current.density
    LaunchedEffect(style, groupRiders) {
        val s = style ?: return@LaunchedEffect
        groupRiders.forEach { r ->
            val a = r.avatar
            if (a != null && groupImages[r.id] !== a) {
                s.addImage("grp-${r.id}", ringedAvatar(a, r.color, groupDensity))
                groupImages[r.id] = a
            }
        }
        s.getSourceAs<GeoJsonSource>(GROUP)?.setGeoJson(
            FeatureCollection.fromFeatures(groupRiders.map { r ->
                Feature.fromGeometry(Point.fromLngLat(r.point.longitude, r.point.latitude)).also {
                    if (r.avatar != null) it.addStringProperty("icon", "grp-${r.id}")
                    it.addStringProperty("id", r.id)
                    it.addStringProperty("label", r.label)
                    it.addStringProperty("color", hex(r.color))
                    it.addNumberProperty("alpha", if (r.stale) 0.45f else 1f)
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
    // already in view is it fitted by itself. Leaving preview leaves the camera where it is.
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

    // The camera is only taken back to the rider when navigation starts or the rider presses the
    // recenter button. Ending a preview, an edit or a pick leaves the map where the rider put it.
    // Starting a route always brings the camera back to the rider.
    val hasRoute = routePoints.isNotEmpty()
    LaunchedEffect(hasRoute) { if (hasRoute) following = true }

    // Rider position + camera follow. Every frame, not every fix: the rider is carried forward
    // between the once-a-second fixes (RiderMotion) and the camera moves with them, so the map
    // glides along the road. Zoom, tilt and where the rider sits on screen ease towards their
    // targets, so changing speed or mode doesn't jolt the view.
    val motion = remember { com.curveon.data.location.RiderMotion() }
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
    LaunchedEffect(style, surfaceMode) {
        val s = style ?: return@LaunchedEffect
        val v = if (surfaceMode != SurfaceMode.OFF) Property.VISIBLE else Property.NONE
        s.layers.filter { it.id.startsWith(SURFACE) }.forEach { it.setProperties(visibility(v)) }
    }
    // The zoom the dashes start at. The gravel heat fades out as the dashes come in.
    LaunchedEffect(style, surfaceZoom) {
        val s = style ?: return@LaunchedEffect
        s.layers.filter { it.id.startsWith(SURFACE) }.forEach { it.setMinZoom(surfaceZoom) }
        s.getLayer("$HEAT-layer")?.setProperties(heatmapOpacity(E.interpolate(E.linear(), E.zoom(),
            E.stop(surfaceZoom, if (dark) 0.8f else 0.7f), E.stop(surfaceZoom + 1.5f, 0f))))
    }
    LaunchedEffect(style, cameraIdleTick, surfaceMode != SurfaceMode.OFF, surfaceZoom) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val fetch = surfaceWays?.takeIf { surfaceMode != SurfaceMode.OFF } ?: return@LaunchedEffect
        if (m.cameraPosition.zoom < surfaceZoom) return@LaunchedEffect
        val b = m.projection.visibleRegion.latLngBounds
        val have = surfaceBounds
        if (have != null && have.contains(b.northEast) && have.contains(b.southWest) && s.getSource(SURFACE) != null) return@LaunchedEffect
        // A margin around the view, so small pans need nothing new.
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.3
        val dLon = (b.longitudeEast - b.longitudeWest) * 0.3
        val area = LatLngBounds.from(b.latitudeNorth + dLat, b.longitudeEast + dLon, b.latitudeSouth - dLat, b.longitudeWest - dLon)
        val ways = runCatching { fetch(area.latitudeSouth, area.longitudeWest, area.latitudeNorth, area.longitudeEast) }
            .onFailure { android.util.Log.w("CurveonMap", "Surfaces not read", it) }.getOrNull()
            ?.let { com.curveon.routing.SurfaceReader.leadingToGravel(it) } ?: return@LaunchedEffect
        val features = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            FeatureCollection.fromFeatures(ways.map { w ->
                Feature.fromGeometry(LineString.fromLngLats((w.coords.indices step 2).map { Point.fromLngLat(w.coords[it], w.coords[it + 1]) })).apply {
                    addStringProperty("kind", when (w.kind) {
                        com.curveon.routing.SurfaceKind.GRAVEL -> "gravel"
                        com.curveon.routing.SurfaceKind.SOFT -> "soft"
                        else -> "road" // tarmac leading to gravel
                    })
                    addNumberProperty("rank", w.rank)
                }
            })
        }
        if (style !== s || !s.isFullyLoaded) return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SURFACE)?.setGeoJson(features)
        surfaceBounds = area
    }
    LaunchedEffect(style) { surfaceBounds = null }

    // Planning a trip: a preview, editing it, picking places or drawing an area on the map.
    val planning = planningTrip || previewRoutes.isNotEmpty() || editor != null || onTap != null || onSketch != null
    LaunchedEffect(style, surfaceMode, roadShade) {
        val s = style ?: return@LaunchedEffect
        val coloured = surfaceMode == SurfaceMode.COLOURED
        // Dashed: the road itself, as wide as the map draws it; coloured: a little wider than the thin dashes, so it reads at a glance.
        val gravelWidth = if (coloured) roadWidth(1.2f) else dashedRoadWidth()
        val softWidth = gravelWidth
        s.getLayer("$SURFACE-road")?.setProperties(lineColor(roadShade.first))
        s.getLayer("$SURFACE-gravel")?.setProperties(lineColor(surfaceMode.gravel(roadShade.first)), lineWidth(gravelWidth))
        s.getLayer("$SURFACE-soft")?.setProperties(lineColor(surfaceMode.soft(dark)), lineWidth(softWidth))
        // Under the dashes: coloured ones on the day map get a dark line, a little wider (orange and purple alone on
        // the green map have no contrast; the night map has its own dark ground). Plain dashes get the ground's colour
        // over the map's own road and its edge, or the road fills the gaps and looks solid once zoomed in.
        val land = if (dark) com.curveon.data.map.OfflineMaps.LAND_DARK else com.curveon.data.map.OfflineMaps.LAND_LIGHT
        s.getLayer("$SURFACE-base")?.setProperties(
            lineColor(if (coloured) "#1F1F1F" else land),
            lineWidth(if (coloured) roadWidth(1.2f, 0.8f) else dashedRoadGround()),
            visibility(if (coloured && !dark || surfaceMode == SurfaceMode.DASHED) Property.VISIBLE else Property.NONE))
        // The same over the route and GPX tracks, as wide as the dashes.
        s.getLayer("$SURFACE-over-gravel")?.setProperties(lineColor(surfaceMode.gravel(roadShade.first)), lineWidth(gravelWidth))
        s.getLayer("$SURFACE-over-soft")?.setProperties(lineColor(surfaceMode.soft(dark)), lineWidth(softWidth))
    }

    // Gravel heat map while planning: counted per routing data file in the background (once), shown
    // for the files in view as they come.
    // Riding (from 10 km/h, until stopped again) hides the button on the ordinary map.
    var moving by remember { mutableStateOf(false) }
    if (speedKmh >= 10f) moving = true else if (speedKmh < 3f) moving = false
    val heatButton = gravelHeat != null && onShowGravelHeat != null && (planning || (gravelHeatOnMap && !navigating && !moving))
    val onHeatButtonShownState = rememberUpdatedState(onHeatButtonShown)
    LaunchedEffect(heatButton) { onHeatButtonShownState.value(heatButton) }
    val heatOn = showGravelHeat && heatButton
    // The weather: while drawing a loop as given; on the ordinary map only with its button shown and on.
    val weatherButton = onWeatherMap != null && !planning && !navigating && !moving
    val onWeatherButtonShownState = rememberUpdatedState(onWeatherButtonShown)
    LaunchedEffect(weatherButton) { onWeatherButtonShownState.value(weatherButton) }
    val shownWeather = if (onWeatherMap == null || (weatherButton && weatherMapOn)) weatherSpots else emptyList()
    // Only on land: a symbol over the sea moves to land close by (in the same forecast cell, within 6 km), else it is left
    // out. The map's own water tells which is which, so it is looked at again when the view has moved.
    LaunchedEffect(style, map, shownWeather) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val density = context.resources.displayMetrics.density
        com.curveon.data.weather.MapWeather.Kind.entries.forEach { k ->
            if (s.getImage(weatherImageName(k)) == null) s.addImage(weatherImageName(k), weatherImage(k, density))
        }
        var lastCamera: org.maplibre.android.camera.CameraPosition? = null
        var looks = 0
        while (true) {
            val camera = m.cameraPosition
            // Also the first few seconds: until the map's tiles are drawn, all of it looks like land.
            if (camera != lastCamera || looks++ < 4) {
                lastCamera = camera
                val water = s.layers.map { it.id }.filter { it.substringBefore('~').removePrefix("online-") == "water" }.toTypedArray()
                s.getSourceAs<GeoJsonSource>(WEATHER)?.setGeoJson(FeatureCollection.fromFeatures(shownWeather.mapNotNull { w ->
                    val at = onLand(m, mapView, water, w.point) ?: return@mapNotNull null
                    Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)).also {
                        it.addStringProperty("icon", weatherImageName(w.kind))
                        it.addStringProperty("temp", "${w.tempC}°")
                    }
                }))
            }
            if (shownWeather.isEmpty()) break
            kotlinx.coroutines.delay(1000)
            lifecycleOwner.lifecycle.untilStarted()
        }
    }
    val heatCells = remember { mutableStateMapOf<String, List<com.curveon.routing.HeatCell>>() }
    val heatCounting = remember { mutableStateListOf<String>() }
    var heatTiles by remember { mutableStateOf<List<String>?>(null) }
    val heatScope = rememberCoroutineScope()
    LaunchedEffect(style, heatOn) {
        style?.getLayer("$HEAT-layer")?.setProperties(visibility(if (heatOn) Property.VISIBLE else Property.NONE))
    }
    LaunchedEffect(style, cameraIdleTick, heatOn) {
        val m = map ?: return@LaunchedEffect
        val source = gravelHeat?.takeIf { heatOn } ?: run { heatTiles = null; return@LaunchedEffect }
        val b = m.projection.visibleRegion.latLngBounds
        val tiles = source.tiles(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
        heatTiles = tiles
        tiles.filter { it !in heatCells && it !in heatCounting }.forEach { tile ->
            heatCounting += tile
            heatScope.launch {
                runCatching { source.cells(tile) }.onSuccess { heatCells[tile] = it }
                    .onFailure { android.util.Log.w("CurveonMap", "Gravel heat $tile not counted", it) }
                heatCounting -= tile
            }
        }
    }
    val heatShown = heatTiles.orEmpty().filter { it in heatCells }
    LaunchedEffect(style, heatShown) {
        val s = style ?: return@LaunchedEffect
        val cells = heatShown.flatMap { heatCells[it].orEmpty() }
        val features = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            FeatureCollection.fromFeatures(cells.map { c -> Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply { addNumberProperty("w", c.weight) } })
        }
        if (style === s && s.isFullyLoaded) s.getSourceAs<GeoJsonSource>(HEAT)?.setGeoJson(features)
    }

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
            lifecycleOwner.lifecycle.untilStarted()
            val p = puck ?: continue
            val r = 10f * mapView.resources.displayMetrics.density
            onRoadHereState.value?.invoke(mapRoad(m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), *roadLayers)))
        }
    }

    // The roads round the rider as the map draws them, to put the rider on the one they ride when there's no
    // route: the same map in every country, offline or online.
    val onRoadsNearState = rememberUpdatedState(onRoadsNear)
    LaunchedEffect(map, style, canQuery, onRoadsNear != null) {
        if (!canQuery || onRoadsNear == null) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val roadLayers = s.layers.map { it.id }.filter { it.startsWith("roads_") }.toTypedArray()
        while (true) {
            kotlinx.coroutines.delay(1_000)
            lifecycleOwner.lifecycle.untilStarted()
            val p = puck ?: continue
            val here = locationState.value
            // ~40 m round the rider, however far the map is zoomed out (then it's a few pixels and little is read).
            val r = (ROADS_NEAR_M / m.projection.getMetersPerPixelAtLatitude(here.latitude)).toFloat().coerceIn(4f, 400f)
            val lines = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), *roadLayers).flatMap { f ->
                when (val g = f.geometry()) {
                    is LineString -> listOf(g.coordinates())
                    is org.maplibre.geojson.MultiLineString -> g.coordinates()
                    else -> emptyList()
                }
            }.map { pts -> pts.map { com.curveon.routing.LatLon(it.latitude(), it.longitude()) } }
            onRoadsNearState.value?.invoke(com.curveon.routing.LatLon(here.latitude, here.longitude), lines)
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

        // Compass button: north up → heading up → 3D. Once the rider pans the map away, a second
        // button brings the camera back to them; it hides again while following. +/- are always
        // there: while following with auto-zoom they nudge the speed-based zoom, otherwise they zoom.
        if (!inPreview) Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(top = buttonsTopInset, bottom = buttonsBottomInset).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            // The weather map on and off, standing still on the full-screen map (top left is the give way sign's).
            if (weatherButton) MapButton(onClick = { onWeatherMap?.invoke(!weatherMapOn) }, highlighted = weatherMapOn, tag = "map_weather_btn") {
                Icon(Icons.Default.WbSunny, stringResource(R.string.map_weather),
                    tint = if (weatherMapOn) Color(0xFF00363D) else TextPrimary, modifier = Modifier.size(24.dp))
            }
            // Tap: north up → heading up → 3D; arriving in 3D shows two arrows beside it for the angle of the view
            // for a few seconds (held down shows them again).
            fun chooseOrientation(o: Orientation) {
                orientation = o
                tiltControls = o == Orientation.THREE_D
                tiltPresses++
                prefs.edit().putString(if (navigating) "orientationNav" else "orientationBrowse", orientation.name).apply()
                // Not following: turn the map where it is; following picks it up by itself.
                if (!following) map?.let { m ->
                    m.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(m.cameraPosition)
                            .bearing(if (orientation == Orientation.NORTH_UP) 0.0 else headingDeg.toDouble())
                            .tilt(if (orientation == Orientation.THREE_D) tilt3d else 0.0)
                            .build()
                    ), 600)
                }
            }
            fun tiltBy(delta: Double) {
                tilt3d = (tilt3d + delta).coerceIn(TILT_MIN, TILT_MAX)
                prefs.edit().putFloat("tilt3d", tilt3d.toFloat()).apply()
                tiltPresses++
                if (!following) map?.let { m -> m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).tilt(tilt3d).build()), 300) }
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
                    onClick = { chooseOrientation(Orientation.entries[(orientation.ordinal + 1) % Orientation.entries.size]) },
                    onLongClick = {
                        if (orientation != Orientation.THREE_D) chooseOrientation(Orientation.THREE_D)
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
/** The give way and stop signs on a short pole, for standing on the map ([density]: px per dp). */
private fun roadSignImages(density: Float): Map<String, android.graphics.Bitmap> {
    val w = (26 * density).toInt()
    val h = (40 * density).toInt()
    val red = android.graphics.Color.rgb(0xD5, 0x2B, 0x1E)
    fun sign(plate: android.graphics.Canvas.(android.graphics.Paint) -> Unit): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // The pole: grey, from under the plate to the ground (the bottom, where the sign stands).
        p.color = android.graphics.Color.rgb(0x8A, 0x93, 0x9E)
        c.drawRect(w / 2f - 1.5f * density, w * 0.6f, w / 2f + 1.5f * density, h.toFloat(), p)
        c.plate(p)
        return bmp
    }
    val giveWay = sign { p ->
        fun tri(inset: Float) = android.graphics.Path().apply {
            val top = w * 0.07f; val ht = w * 0.87f; val cy = top + ht / 3; val k = 1 - inset / (ht / 3)
            moveTo(w / 2f - w / 2f * k, cy + (top - cy) * k)
            lineTo(w / 2f + w / 2f * k, cy + (top - cy) * k)
            lineTo(w / 2f, cy + (top + ht - cy) * k)
            close()
        }
        p.color = android.graphics.Color.WHITE; drawPath(tri(0f), p)
        p.color = red; drawPath(tri(w * 0.05f), p)
        p.color = android.graphics.Color.WHITE; drawPath(tri(w * 0.17f), p)
    }
    val stop = sign { p ->
        fun oct(inset: Float) = android.graphics.Path().apply {
            val c = w / 2f
            val r = (c - inset) / kotlin.math.cos(Math.PI / 8).toFloat()
            for (i in 0 until 8) {
                val a = Math.PI / 8 + i * Math.PI / 4
                val x = c + r * kotlin.math.cos(a).toFloat(); val y = c + r * kotlin.math.sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        p.color = android.graphics.Color.WHITE; drawPath(oct(0f), p)
        p.color = red; drawPath(oct(w * 0.07f), p)
        p.color = android.graphics.Color.WHITE
        p.textAlign = android.graphics.Paint.Align.CENTER; p.textSize = w * 0.28f
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        drawText("STOP", w / 2f, w / 2f + p.textSize * 0.36f, p)
    }
    return mapOf(GIVE_WAY_IMAGE to giveWay, STOP_SIGN_IMAGE to stop)
}

/**
 * [p], or the first place near it on land: rings of 3 and 6 km round it. Water is what the map draws as water there (the
 * [water] layers); a place off the screen can't be told, so it stays where it is (it isn't seen anyway). Null: all water.
 */
private fun onLand(m: MapLibreMap, view: android.view.View, water: Array<String>, p: com.curveon.routing.LatLon): com.curveon.routing.LatLon? {
    if (water.isEmpty()) return p
    val tries = listOf(p) + listOf(3_000.0, 6_000.0).flatMap { r -> (0 until 8).map { com.curveon.routing.Geo.destination(p, it * 45.0, r) } }
    var anyOnScreen = false
    for (t in tries) {
        val at = m.projection.toScreenLocation(LatLng(t.lat, t.lon))
        if (at.x < 0 || at.y < 0 || at.x > view.width || at.y > view.height) continue
        anyOnScreen = true
        if (m.queryRenderedFeatures(at, *water).isEmpty()) return t
    }
    return if (anyOnScreen) null else p
}

private fun weatherImageName(kind: com.curveon.data.weather.MapWeather.Kind) = "curveon-weather-${kind.name.lowercase()}"

/** A small weather symbol on a dark disc: a sun, a cloud, a cloud with rain, or wind lines. */
private fun weatherImage(kind: com.curveon.data.weather.MapWeather.Kind, density: Float): android.graphics.Bitmap {
    val d = (34 * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(d, d, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = d / 2f
    p.color = android.graphics.Color.argb(215, 0x12, 0x18, 0x20); c.drawCircle(r, r, r, p)
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.5f * density
    p.color = android.graphics.Color.argb(160, 0xFF, 0xFF, 0xFF); c.drawCircle(r, r, r - p.strokeWidth, p)
    p.style = android.graphics.Paint.Style.FILL
    fun cloud(color: Int, cy: Float) {
        p.color = color
        c.drawCircle(r - d * 0.12f, cy, d * 0.13f, p)
        c.drawCircle(r + d * 0.05f, cy - d * 0.06f, d * 0.16f, p)
        c.drawRoundRect(r - d * 0.27f, cy - d * 0.02f, r + d * 0.27f, cy + d * 0.12f, d * 0.07f, d * 0.07f, p)
    }
    when (kind) {
        com.curveon.data.weather.MapWeather.Kind.SUN -> {
            p.color = android.graphics.Color.rgb(0xFF, 0xC1, 0x07)
            c.drawCircle(r, r, d * 0.15f, p)
            p.strokeWidth = 2f * density; p.strokeCap = android.graphics.Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                c.drawLine(r + (d * 0.22f * kotlin.math.cos(a)).toFloat(), r + (d * 0.22f * kotlin.math.sin(a)).toFloat(),
                    r + (d * 0.31f * kotlin.math.cos(a)).toFloat(), r + (d * 0.31f * kotlin.math.sin(a)).toFloat(), p)
            }
        }
        com.curveon.data.weather.MapWeather.Kind.CLOUD -> cloud(android.graphics.Color.rgb(0xB8, 0xC2, 0xCC), r + d * 0.02f)
        com.curveon.data.weather.MapWeather.Kind.RAIN -> {
            cloud(android.graphics.Color.rgb(0x9A, 0xA6, 0xB2), r - d * 0.08f)
            p.color = android.graphics.Color.rgb(0x40, 0xA9, 0xFF); p.strokeWidth = 2f * density; p.strokeCap = android.graphics.Paint.Cap.ROUND
            for (x in listOf(-0.14f, 0f, 0.14f)) c.drawLine(r + d * x, r + d * 0.12f, r + d * (x - 0.05f), r + d * 0.27f, p)
        }
        com.curveon.data.weather.MapWeather.Kind.WIND -> {
            p.color = android.graphics.Color.rgb(0xFF, 0x91, 0x00); p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = 2.2f * density; p.strokeCap = android.graphics.Paint.Cap.ROUND
            for ((y, len) in listOf(-0.12f to 0.5f, 0f to 0.36f, 0.12f to 0.44f)) c.drawLine(r - d * 0.25f, r + d * y, r - d * 0.25f + d * len, r + d * y, p)
        }
    }
    return bmp
}

private fun speedSignImageName(limit: Int) = "curveon-speed-sign-$limit"

/** A speed limit sign on a short pole, as the road signs: red ring, white disc, [limit] in black. */
private fun speedSignImage(limit: Int, density: Float): android.graphics.Bitmap {
    val w = (30 * density).toInt()
    val h = (46 * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.rgb(0x8A, 0x93, 0x9E)
    c.drawRect(w / 2f - 1.5f * density, w * 0.8f, w / 2f + 1.5f * density, h.toFloat(), p)
    val r = w / 2f
    p.color = android.graphics.Color.rgb(0xD5, 0x2B, 0x1E); c.drawCircle(r, r, r, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(r, r, r * 0.74f, p)
    p.color = android.graphics.Color.BLACK
    p.textAlign = android.graphics.Paint.Align.CENTER
    p.typeface = android.graphics.Typeface.DEFAULT_BOLD
    p.textSize = w * if (limit >= 100) 0.34f else 0.42f
    c.drawText(limit.toString(), r, r + p.textSize * 0.36f, p)
    return bmp
}

/** A rider's picture as a round marker with a white edge and a ring in their colour. */
private fun ringedAvatar(photo: android.graphics.Bitmap, color: Int, density: Float): android.graphics.Bitmap {
    val d = (44 * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(d, d, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = d / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(r, r, r, paint)
    paint.color = color
    canvas.drawCircle(r, r, r - 3 * density, paint)
    val inner = r - 6 * density
    val size = (inner * 2).toInt().coerceAtLeast(1)
    val shader = android.graphics.BitmapShader(
        android.graphics.Bitmap.createScaledBitmap(photo, size, size, true),
        android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
    )
    shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(r - inner, r - inner) })
    paint.shader = shader
    canvas.drawCircle(r, r, inner, paint)
    return bmp
}

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
/** Thin dashes, a little under the map's own small roads (which are at least 1.6 px), so a gravel road is never the heaviest line on the map. */
private fun roadWidth(scale: Float = 1f, extra: Float = 0f) = E.interpolate(E.exponential(1.6f), E.zoom(),
    E.stop(SURFACE_ZOOM, 1f * scale + extra), E.stop(14.5, 1.2f * scale + extra), E.stop(15, 1.6f * scale + extra), E.stop(18, 7f * scale + extra))

/** Plain dashes: thin far out, then as wide as the map's own small roads (11 px at zoom 18), so the dashes are the road. */
private fun dashedRoadWidth() = E.interpolate(E.exponential(1.6f), E.zoom(),
    E.stop(SURFACE_ZOOM, 1f), E.stop(12.5, 1.6f), E.stop(15, 2f), E.stop(18, 11f))

/** Ground under plain dashes: covers the map's own road (bigger ones up to 13 px at zoom 18) and its edge, from where the map draws roads. */
private fun dashedRoadGround() = E.interpolate(E.exponential(1.6f), E.zoom(),
    E.stop(12, 0f), E.stop(12.5, 3f), E.stop(15, 5f), E.stop(18, 16f))

private fun surfaceWidth(base: Float) = E.interpolate(E.linear(), E.zoom(),
    E.stop(SURFACE_ZOOM, E.product(E.literal(base * 0.6f), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))),
    E.stop(14, E.product(E.literal(base), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))),
    E.stop(18, E.product(E.literal(base * 3f), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))))

private fun addSurfaceLayers(s: Style, dark: Boolean) {
    s.addSource(GeoJsonSource(SURFACE))
    s.addSource(GeoJsonSource(HEAT))
    fun width(base: Float) = surfaceWidth(base)
    val gravel = E.eq(E.get("kind"), E.literal("gravel"))
    val soft = E.eq(E.get("kind"), E.literal("soft"))
    // The tarmac roads leading to gravel, thin in the small roads' colour, until the map's own small roads
    // are in its tiles (from zoom 12): without them a gravel road seems to join nothing.
    // Dashes over the map's own roads: as wide as the road when plain, so the road reads as dashed.
    val layers = listOf(
        LineLayer("$SURFACE-road", SURFACE).withProperties(
            lineColor(if (dark) "#EBEBEB" else com.curveon.data.map.OfflineMaps.HC_ROAD), lineWidth(roadWidth()),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        ).withFilter(E.eq(E.get("kind"), E.literal("road"))).also { it.maxZoom = MAP_ROADS_ZOOM },
        LineLayer("$SURFACE-base", SURFACE).withProperties(
            lineColor("#1F1F1F"), lineWidth(roadWidth(1.2f, 0.8f)), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            visibility(Property.NONE)
        ).withFilter(E.any(E.eq(E.get("kind"), E.literal("gravel")), E.eq(E.get("kind"), E.literal("soft")))),
        LineLayer("$SURFACE-soft", SURFACE).withProperties(
            lineColor(SurfaceMode.DASHED.soft(dark)), lineWidth(roadWidth()), lineDasharray(arrayOf(2f, 1.5f))
        ).withFilter(soft),
        LineLayer("$SURFACE-gravel", SURFACE).withProperties(
            lineColor(SurfaceMode.DASHED.gravel(if (dark) com.curveon.data.map.OfflineMaps.NIGHT_ROAD_HC else com.curveon.data.map.OfflineMaps.HC_ROAD)), lineWidth(roadWidth()), lineDasharray(arrayOf(1.8f, 1.7f))
        ).withFilter(gravel)
    )
    // Under the downloaded map's first label layer, so road names stay on top (the online map's
    // layers, labels included, all lie underneath).
    // With no downloaded map there are only the online map's labels: the lines go under those, not on top of everything
    // (which put them over the place names).
    val firstLabel = (s.layers.firstOrNull { it is org.maplibre.android.style.layers.SymbolLayer && !it.id.startsWith("online-") && !it.id.startsWith("org.maplibre") }
        ?: s.layers.firstOrNull { it is org.maplibre.android.style.layers.SymbolLayer && !it.id.startsWith("org.maplibre") })?.id
    // Gravel heat: warm where there is much gravel per 1/16° square (about 6 km of gravel road
    // counts as a lot), the squares' blobs overlapping into one surface, fading out as the dashes take over when zoomed in.
    val heat = HeatmapLayer("$HEAT-layer", HEAT).withProperties(
        heatmapWeight(E.interpolate(E.linear(), E.get("w"), E.stop(0, 0f), E.stop(6, 1f))),
        heatmapIntensity(E.interpolate(E.linear(), E.zoom(), E.stop(4, 0.8f), E.stop(8, 1.5f), E.stop(11, 2.5f))),
        heatmapRadius(E.interpolate(E.exponential(2f), E.zoom(), E.stop(4, 6f), E.stop(7, 20f), E.stop(10, 90f), E.stop(12, 220f))),
        heatmapOpacity(E.interpolate(E.linear(), E.zoom(), E.stop(10, if (dark) 0.8f else 0.7f), E.stop(12.5, 0f))),
        heatmapColor(E.interpolate(E.linear(), E.heatmapDensity(),
            E.stop(0, E.rgba(0, 0, 0, 0)),
            E.stop(0.15, E.rgba(255, 145, 0, 0.25)),
            E.stop(0.4, E.rgba(255, 145, 0, 0.6)),
            E.stop(0.7, E.rgba(255, 61, 0, 0.8)),
            E.stop(1, E.rgba(255, 234, 0, 0.9)))),
        visibility(Property.NONE)
    ).also { it.maxZoom = 13f }
    if (firstLabel != null) s.addLayerBelow(heat, firstLabel) else s.addLayer(heat)
    layers.forEach { l ->
        l.minZoom = SURFACE_ZOOM
        if (firstLabel != null) s.addLayerBelow(l, firstLabel) else s.addLayer(l)
    }
}

/**
 * The gravel and soft dashes again, over the route and the GPX tracks (which are drawn above the
 * ones under the road names and would hide them). Same source, filters, colours and width as the
 * dashes below (set with them), shown and hidden with them; no edge, so they are never heavier
 * than the road they sit on.
 */
private fun addSurfaceOverLayers(s: Style, dark: Boolean) {
    fun over(kind: String, dash: Array<Float>, color: String) = LineLayer("$SURFACE-over-$kind", SURFACE).withProperties(
        lineColor(color), lineWidth(roadWidth()), lineDasharray(dash),
        lineCap(Property.LINE_CAP_BUTT), lineJoin(Property.LINE_JOIN_ROUND)
    ).withFilter(E.eq(E.get("kind"), E.literal(kind)))
    listOf(over("soft", arrayOf(2f, 1.5f), SurfaceMode.DASHED.soft(dark)), over("gravel", arrayOf(1.8f, 1.7f), SurfaceMode.DASHED.gravel(if (dark) com.curveon.data.map.OfflineMaps.NIGHT_ROAD_HC else com.curveon.data.map.OfflineMaps.HC_ROAD))).forEach { l ->
        l.minZoom = SURFACE_ZOOM
        s.addLayer(l)
    }
}

/**
 * Place names above every line layer added so far (route, GPX tracks, dashes): the map's own layers
 * keep them last, but our lines are added after them and would cover them. Pins and signs
 * added after this still sit above the names.
 */
private fun raisePlaceNames(s: Style) {
    s.layers.filter { l -> l.id.substringBefore('~').let { it.startsWith("places_") || it == "online-places" } }.forEach { l ->
        if (s.removeLayer(l)) s.addLayer(l)
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
    addSurfaceOverLayers(s, dark)
    // The weather while drawing a loop: under the place names (raised next) and the drawn line, there to draw by.
    s.addSource(GeoJsonSource(WEATHER))
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$WEATHER-icons", WEATHER).withProperties(
            iconImage(org.maplibre.android.style.expressions.Expression.get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
            textField(org.maplibre.android.style.expressions.Expression.get("temp")), textFont(arrayOf("Noto Sans Medium")), textSize(13f),
            textColor(if (dark) "#E8EEF5" else "#1B2430"), textHaloColor(if (dark) "#0B0F14" else "#FFFFFF"), textHaloWidth(1.5f),
            // The temperature gives way to a place name it would cover (the names are placed first, being above).
            textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.4f)), textOptional(true)
        )
    )
    raisePlaceNames(s)
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
    // The other riders of a group ride: a ring in their colour with the name under it, dimmed when not heard from for a while.
    // Riders so close that their markers would lie on top of each other (standing together) are one marker: how many, and
    // their names under it. Done by the map itself, so it costs nothing while the map moves.
    s.addSource(GeoJsonSource(GROUP, org.maplibre.android.style.sources.GeoJsonOptions()
        .withCluster(true).withClusterRadius(GROUP_CLUSTER_PX).withClusterMaxZoom(GROUP_CLUSTER_MAX_ZOOM)
        // (name, how two are put together, the value of one rider)
        .withClusterProperty("names",
            org.maplibre.android.style.expressions.Expression.concat(org.maplibre.android.style.expressions.Expression.accumulated(),
                org.maplibre.android.style.expressions.Expression.literal(", "), org.maplibre.android.style.expressions.Expression.get("names")),
            org.maplibre.android.style.expressions.Expression.get("label"))
        .withClusterProperty("alpha",
            org.maplibre.android.style.expressions.Expression.max(org.maplibre.android.style.expressions.Expression.accumulated(),
                org.maplibre.android.style.expressions.Expression.get("alpha")),
            org.maplibre.android.style.expressions.Expression.get("alpha"))
    ))
    val single = org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("point_count"))
    val together = org.maplibre.android.style.expressions.Expression.has("point_count")
    val noPicture = org.maplibre.android.style.expressions.Expression.all(single,
        org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("icon")))
    val hasPicture = org.maplibre.android.style.expressions.Expression.all(single, org.maplibre.android.style.expressions.Expression.has("icon"))
    s.addLayer(
        CircleLayer("$GROUP-dots", GROUP).withProperties(
            circleRadius(10f),
            circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
            circleOpacity(org.maplibre.android.style.expressions.Expression.get("alpha")),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
            circleStrokeOpacity(org.maplibre.android.style.expressions.Expression.get("alpha"))
        ).also { it.setFilter(noPicture) }
    )
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$GROUP-photos", GROUP).withProperties(
            iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
            iconOpacity(org.maplibre.android.style.expressions.Expression.get("alpha")),
            iconAllowOverlap(true), iconIgnorePlacement(true)
        ).also { it.setFilter(hasPicture) }
    )
    // Riders together: a bigger ring with how many, the names under it.
    s.addLayer(
        CircleLayer("$GROUP-clusters", GROUP).withProperties(
            circleRadius(15f), circleColor("#00E5FF"),
            circleOpacity(org.maplibre.android.style.expressions.Expression.get("alpha")),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
            circleStrokeOpacity(org.maplibre.android.style.expressions.Expression.get("alpha"))
        ).also { it.setFilter(together) }
    )
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$GROUP-cluster-count", GROUP).withProperties(
            textField(org.maplibre.android.style.expressions.Expression.toString(org.maplibre.android.style.expressions.Expression.get("point_count"))),
            textFont(arrayOf("Noto Sans Medium")), textSize(15f), textColor("#0B0E14"),
            textAllowOverlap(true), textIgnorePlacement(true)
        ).also { it.setFilter(together) }
    )
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$GROUP-cluster-labels", GROUP).withProperties(
            textField(org.maplibre.android.style.expressions.Expression.get("names")),
            textFont(arrayOf("Noto Sans Medium")), textSize(14f), textMaxWidth(12f),
            textColor(if (dark) "#FFFFFF" else "#0B0E14"),
            textHaloColor(if (dark) "#0B0E14" else "#FFFFFF"), textHaloWidth(2f),
            textOpacity(org.maplibre.android.style.expressions.Expression.get("alpha")),
            textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.4f)),
            textAllowOverlap(true), textIgnorePlacement(true)
        ).also { it.setFilter(together) }
    )
    // The name sits under the dot, or further down under a picture, which is bigger.
    for ((id, filter, offset) in listOf(Triple("$GROUP-labels", noPicture, 1f), Triple("$GROUP-photo-labels", hasPicture, 2.6f))) {
        s.addLayer(
            org.maplibre.android.style.layers.SymbolLayer(id, GROUP).withProperties(
                textField(org.maplibre.android.style.expressions.Expression.get("label")),
                textFont(arrayOf("Noto Sans Medium")), textSize(14f),
                textColor(if (dark) "#FFFFFF" else "#0B0E14"),
                textHaloColor(if (dark) "#0B0E14" else "#FFFFFF"), textHaloWidth(2f),
                textOpacity(org.maplibre.android.style.expressions.Expression.get("alpha")),
                textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, offset)),
                textAllowOverlap(true), textIgnorePlacement(true)
            ).also { it.setFilter(filter) }
        )
    }
    addEditLayers(s, dark)
    s.addSource(GeoJsonSource(SKETCH))
    s.addLayer(
        LineLayer("$SKETCH-line", SKETCH).withProperties(
            lineColor("#FF9100"), lineWidth(5f), lineOpacity(0.9f),
            lineDasharray(arrayOf(1.2f, 1.2f)), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addSource(GeoJsonSource(GIVE_WAY))
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$GIVE_WAY-signs", GIVE_WAY).withProperties(
            iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
            iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true)
        ).also { it.minZoom = 13f }
    )
    s.addSource(GeoJsonSource(SPEED_SIGNS))
    s.addLayer(
        org.maplibre.android.style.layers.SymbolLayer("$SPEED_SIGNS-signs", SPEED_SIGNS).withProperties(
            iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
            iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true)
        ).also { it.minZoom = 13f }
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
    return com.curveon.data.road.MapRoad(f.getStringProperty("ref")?.takeIf { it.isNotBlank() }, f.getStringProperty("kind_detail"), name,
        f.getStringProperty("name")?.takeIf { it.isNotBlank() })
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
    // surroundings at about the same scale as the flat view. All of it one step (one press on -)
    // further out than it used to be: the rider asked to see more around the bike.
    return zoom - 1.0 + if (threeD) 0.3 else 0.0
}

/** Moves [from] towards [to], [tau] seconds being roughly how long it takes to get two thirds there. */
private fun ease(from: Double, to: Double, dt: Double, tau: Double) = from + (to - from) * (1 - kotlin.math.exp(-dt / tau))

/** The rider arrow on screen: position in px, rotation from screen-up, and the map's tilt. */
/** Where the arrow is drawn, and the halo's radii round it (px). */
private data class Puck(val x: Float, val y: Float, val rotation: Float, val tilt: Float, val haloRx: Float = 0f, val haloRy: Float = 0f)

/** The smallest halo: a ring just outside the arrow (44 dp, its tip ~21 dp from the middle). */
private const val HALO_MIN_DP = 27f

/** How far round the rider the map's roads are read to put the rider on one (see onRoadsNear). */
private const val ROADS_NEAR_M = 40.0

/**
 * A see-through disc under the arrow, as big as the GPS's accuracy: a small ring round the arrow
 * normally, wider where the fix is poor or the position is guessed (tunnels). White
 * on the dark map; the deeper blue of the route on the light one, where cyan would fade into it.
 */
@Composable
private fun RiderHalo(dark: Boolean, color: Color, puck: () -> Puck?) {
    // White on the dark map: a tinted halo vanished into it.
    val fill = if (dark) Color.White.copy(alpha = 0.18f) else Color(0x2E0091EA)
    val edge = if (dark) Color.White.copy(alpha = 0.7f) else Color(0x990091EA)
    val stroke = with(androidx.compose.ui.platform.LocalDensity.current) { 1.5.dp.toPx() }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val p = puck() ?: return@Canvas
        if (p.haloRx <= 0f) return@Canvas
        val topLeft = androidx.compose.ui.geometry.Offset(p.x - p.haloRx, p.y - p.haloRy)
        val size = androidx.compose.ui.geometry.Size(2 * p.haloRx, 2 * p.haloRy)
        drawOval(fill, topLeft, size)
        drawOval(edge, topLeft, size, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
    }
}

/** An amber satellite up to the right of the (ghost) arrow: no GPS signal (or only a guess, in a tunnel). */
@Composable
private fun RiderNoSatellite(puck: () -> Puck?) {
    val off = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
    Box(
        Modifier
            .graphicsLayer {
                val p = puck()
                alpha = if (p == null) 0f else 1f
                if (p != null) { translationX = p.x + off - size.width / 2; translationY = p.y - off - size.height / 2 }
            }
            .size(30.dp).clip(CircleShape).background(CockpitSurface).border(1.5.dp, NeonAmber, CircleShape)
            .testTag("rider_no_gps"),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.SatelliteAlt, stringResource(R.string.gps_lost), tint = NeonAmber, modifier = Modifier.size(19.dp))
    }
}

/** "DEMO" right under the arrow while the ride is a simulation, so it can't be taken for a real one. */
@Composable
private fun RiderDemoTag(onTap: () -> Unit, puck: () -> Puck?) {
    val below = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    // Placed (not just drawn) at the arrow, so a tap on it lands on it: it opens the demo's tricks.
    Box(
        Modifier
            .offset { puck()?.let { androidx.compose.ui.unit.IntOffset((it.x - width / 2).toInt(), (it.y + below).toInt()) } ?: androidx.compose.ui.unit.IntOffset(-10_000, 0) }
            .onSizeChanged { size -> width = size.width }
            .clip(RoundedCornerShape(50)).clickable(onClick = onTap).background(NeonAmber).border(2.dp, Color.Black, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .testTag("demo_ride_tag")
    ) {
        Text(stringResource(R.string.map_demo_ride), color = Color.Black, fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/**
 * The rider arrow, drawn over the map. Read through [puck] inside the layer block, so moving it
 * every frame only redraws the arrow. Tilted with the map in 3D so it lies on the road.
 */
@Composable
private fun RiderArrow(orange: Boolean, ghost: Boolean = false, puck: () -> Puck?) {
    val size = 44.dp
    val half = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() / 2 }
    // Without a fix: grey and see-through, a ghost of where the rider was.
    val grey = remember { androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) }
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(if (orange) com.curveon.R.drawable.ic_nav_arrow_night else com.curveon.R.drawable.ic_nav_arrow),
        contentDescription = null,
        colorFilter = if (ghost) grey else null,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val p = puck()
                alpha = if (p == null) 0f else if (ghost) 0.45f else 1f
                if (p != null) {
                    translationX = p.x - half
                    translationY = p.y - half
                    rotationX = p.tilt * 0.8f
                    cameraDistance = 12f * density
                }
            }
            .graphicsLayer { rotationZ = puck()?.rotation ?: 0f }
    )
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun MapButton(
    onClick: () -> Unit,
    tag: String,
    highlighted: Boolean = false,
    /** Held down; the button is then a box that tells taps and holds apart. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (onLongClick != null) return Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (highlighted) ElectricCyan else CockpitSurface)
            .border(1.dp, CockpitBorder, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) { content() }
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
private fun RiderEdgeGlow(rider: androidx.compose.ui.geometry.Offset, dark: Boolean, color: Color, modifier: Modifier) {
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
            0f to color.copy(alpha = near),
            0.35f to color.copy(alpha = 0.8f * near),
            0.7f to color.copy(alpha = 0.2f * near),
            1f to Color.Transparent
        ) else arrayOf(
            0f to color.copy(alpha = near),
            0.35f to color.copy(alpha = 0.9f * near),
            0.55f to CockpitBackground.copy(alpha = 0.6f * near),
            1f to Color.Transparent
        )
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(*stops, center = at, radius = r), radius = r, center = at)
    }
}

private fun haversine(a: GeoPoint, b: GeoPoint): Double =
    com.curveon.routing.Geo.distance(a.latitude, a.longitude, b.latitude, b.longitude)

/** Waits while the screen is out of sight, so a looping effect doesn't run in the background. */
internal suspend fun Lifecycle.untilStarted() {
    currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
}
