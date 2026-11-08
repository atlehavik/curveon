package com.curveon.ui.components

import com.curveon.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.curveon.data.map.OfflineMaps
import com.curveon.data.model.GeoPoint
import com.curveon.data.model.GroundPreference
import com.curveon.data.model.TurnInstruction
import com.curveon.ui.theme.*
import kotlinx.coroutines.flow.first
import org.maplibre.android.MapLibre
import org.maplibre.android.style.layers.PropertyFactory.*

/** One press of the remote moves the map by this share of its shorter side. */
internal const val REMOTE_PAN_SHARE = 0.18f
internal const val REMOTE_PAN_MS = 160L
internal const val REMOTE_PAN_RETURN_MS = 12_000L

internal const val ROUTE = "curveon-route"
internal const val ALT = "curveon-alternatives"
internal const val MARKERS = "curveon-markers"
internal const val GROUP = "curveon-group"
/** Riders closer than this on screen (about a marker's width) are shown as one marker. */
internal const val GROUP_CLUSTER_PX = 24
/** The source's own max zoom: further in, the map only enlarges what was made here, so riders on the very same spot stay one marker. */
internal const val GROUP_CLUSTER_MAX_ZOOM = 18
internal const val PICK = "curveon-pick"
internal const val APPROACH = "curveon-approach"
/** At most this often is a camera that keeps moving (following the rider) reported as settled. */
internal const val IDLE_TICK_MS = 1_000L
/**
 * The map, camera and rider arrow move 30 times a second, not on every 60 Hz frame: it looks the
 * same on the bike and costs about a quarter less battery (measured against 60 on a Pixel 9a).
 * Frames closer than this to the last one drawn are skipped.
 */
internal const val FRAME_NS = 25_000_000L
internal const val MAX_FPS = 30
internal const val GPX = "curveon-gpx"
internal const val GPX_POIS = "curveon-gpx-pois"
internal const val GPX_ON_ROUTE = "curveon-gpx-on-route"
internal const val GIVE_WAY = "curveon-give-way"
internal const val GIVE_WAY_IMAGE = "curveon-give-way-sign"
internal const val SPEED_SIGNS = "curveon-speed-signs"
/** The weather round the rider while a loop is drawn. */
internal const val WEATHER = "curveon-weather"
internal const val STOP_SIGN_IMAGE = "curveon-stop-sign"
internal const val HINT = "curveon-track-hint"
internal const val SKETCH = "curveon-sketch"
internal const val ROUTE_SURFACE = "curveon-route-surface"
internal const val SKETCH_ZOOM = 9.0
internal const val ARROW_IMAGE = "curveon-arrow"
/** GPX waypoints turn from dots into icons from this zoom in (about 30 km across the map). */
internal const val POI_ICON_ZOOM = 11f
/** A marked part of the route shorter than this on screen is a tap, not a part. */
internal const val MIN_SECTION_DP = 24f
internal const val SURFACE = "curveon-surface"
internal const val HEAT = "curveon-gravel-heat"

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
internal const val SURFACE_ZOOM = 11f
/** From this zoom the map's own tiles have the small roads. */
internal const val MAP_ROADS_ZOOM = 12.2f
internal const val FOLLOW_ZOOM = 15.5
/** 3D tilt; car navigators sit around 45–50 (Mapbox's navigation camera: 45). */
internal const val TILT_3D = 55.0
/** The rider's own 3D angle: 0 is looking straight down, higher looks more along the road. Steps of the +/- buttons. */
internal const val TILT_MIN = 20.0
internal const val TILT_MAX = 60.0 // MapLibre 13.6.1 refuses more (checked: 61 is rejected)
internal const val TILT_STEP = 5.0
/** The tilt arrows show when 3D is chosen and hide again after this long without a press. */
internal const val TILT_CONTROLS_MS = 6_000L
/** Rider position below the middle, as a share of the map's height, given as top padding (×2 lower). */
internal const val RIDER_LOW_3D = 0.6 // rider ~4/5 down the map
internal const val RIDER_LOW_2D = 0.36

/** How the camera turns while following the rider; the rider picks it with the compass button. */
internal enum class Orientation { NORTH_UP, HEADING_UP, THREE_D }

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
    onRoadsNear: ((com.curveon.routing.LatLon, List<com.curveon.routing.RoadSnap.Line>) -> Unit)? = null,
    gpxTracks: List<com.curveon.data.gpx.OverlayTrack> = emptyList(),
    gpxPois: List<com.curveon.data.gpx.GpxPoi> = emptyList(),
    /** Give way and stop signs that apply to the rider, stood beside the road before their junctions. */
    giveWaySigns: List<com.curveon.data.routing.GiveWayAlert> = emptyList(),
    /** Where the speed limit changes ahead on the route: a sign on the right of the road. */
    speedSigns: List<com.curveon.routing.SpeedChange> = emptyList(),
    /** The rider rides in mph: the speed signs show mph too. */
    useMph: Boolean = false,
    /** The weather to show (sun, cloud, rain, wind and how warm): round where a loop starts while drawing it, or from the button. */
    weatherSpots: () -> List<com.curveon.data.weather.MapWeather> = { emptyList() },
    /** The weather map button (full-screen map, not navigating, standing still): on or off, and switching it; null = no button. */
    weatherMapOn: Boolean = false,
    onWeatherMap: ((Boolean) -> Unit)? = null,
    /** Whether the weather button can be seen now, so the weather is only fetched then. */
    onWeatherButtonShown: (Boolean) -> Unit = {},
    trackHint: com.curveon.data.gpx.TrackHint? = null,
    /** While navigating: where a track shown runs along the route, as a thin stripe in its colour inside the route line. */
    gpxOnRoute: List<Pair<Int, List<List<GeoPoint>>>> = emptyList(),
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
    /** Settings → Display: the buildings stand up in the 3D view. */
    buildings3d: Boolean = false,
    /** The routing data files covering an area (for quieter towns on the online map). */
    routingTiles: (suspend (Double, Double, Double, Double) -> List<String>)? = null,
    /** Settings → Display → Footpaths and cycleways (off by default). */
    showPaths: Boolean = false,
    /** Settings → Display → Show zoom level. */
    showZoom: Boolean = false,
    /** Settings → Display → Quieter towns: where the towns are (CityAreas squares; null: off, or not counted yet). */
    townSquares: Set<Long>? = null,
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

    val navigating = routePoints.size >= 2
    val h = rememberCurveonMapState(navigating)
    // Remembered separately for browsing the map (north up unless chosen otherwise) and for navigating (3D),
    // and switched when a trip starts or ends.
    if (h.orientationFor != navigating) { h.orientationFor = navigating; h.orientation = h.savedOrientation(navigating) }
    val hasOfflineMap = remember(mapVersion) { h.offlineMaps.installedMaps().isNotEmpty() }
    h.TiltControlsTimeout()
    h.MapLifecycle()
    h.MapListeners(location, onLongPress, onTap, editor, onSketch, onSketchProgress, onEditRoute, previewRoutes, onPoiClick, onGroupRiderClick, gpxPois)
    h.MapStyleLoader(dark, mapVersion, roadShade)
    h.RouteLines(routePoints, previewRoutes, routeSurfaces)
    h.TrackLines(gpxTracks, gpxOnRoute)
    h.ViewportReports(onViewport)
    h.SignsAndPois(giveWaySigns, speedSigns, gpxPois, trackHint, useMph)
    h.PlanMarks(markers, groupRiders, sketch, pickedPoint, location, approachTo, editor, previewRoutes, dark)
    val hasPreviewTrip = h.PreviewCamera(previewRoutes, editor, previewSheetMinimized, location, onSketch)
    val inPreview = previewRoutes.isNotEmpty()
    h.FollowCamera(routePoints, riderFix, location, headingDeg, speedKmh, currentTurnInstruction, bottomClearance, inPreview, hasGpsFix, autoZoom)
    h.OnlineLayers(hasOfflineMap)
    h.SurfaceLayers(surfaceWays, surfaceMode, surfaceZoom, roadShade, dark, townSquares)
    h.Buildings3d(buildings3d && h.orientation == Orientation.THREE_D, dark)
    h.TownRoads(townSquares, showPaths, ownRoads = surfaceWays != null && surfaceMode != SurfaceMode.OFF, routingTiles = routingTiles)

    // Planning a trip: a preview, editing it, picking places or drawing an area on the map.
    val planning = planningTrip || previewRoutes.isNotEmpty() || editor != null || onTap != null || onSketch != null
    // Gravel heat map while planning: counted per routing data file in the background (once), shown
    // for the files in view as they come.
    // Riding (from 10 km/h, until stopped again) hides the button on the ordinary map.
    if (speedKmh >= 10f) h.moving = true else if (speedKmh < 3f) h.moving = false
    val heatButton = gravelHeat != null && onShowGravelHeat != null && (planning || (gravelHeatOnMap && !navigating && !h.moving))
    val heatOn = showGravelHeat && heatButton
    // The weather: while drawing a loop as given; on the ordinary map only with its button shown and on.
    val weatherButton = onWeatherMap != null && !planning && !navigating && !h.moving
    h.GravelHeatLayer(gravelHeat, heatButton, heatOn, onHeatButtonShown)
    h.WeatherSymbols(weatherButton, onWeatherMap, weatherMapOn, onWeatherButtonShown, weatherSpots)
    h.RoadReading(roadNameProbe, onRoadName, onRoadHere, onRoadsNear, location)

    h.MapContent(
        modifier = modifier, isFullScreen = isFullScreen, location = location, hasGpsFix = hasGpsFix, gpsLost = gpsLost, dark = dark,
        demoRide = demoRide, onDemoTap = onDemoTap, inPreview = inPreview, navigating = navigating, headingDeg = headingDeg,
        speedKmh = speedKmh, autoZoom = autoZoom, onSketch = onSketch, buttonsTopInset = buttonsTopInset,
        buttonsBottomInset = buttonsBottomInset, weatherButton = weatherButton, onWeatherMap = onWeatherMap, weatherMapOn = weatherMapOn,
        hasPreviewTrip = hasPreviewTrip, heatButton = heatButton, onShowGravelHeat = onShowGravelHeat, showGravelHeat = showGravelHeat,
        heatOn = heatOn, hasOfflineMap = hasOfflineMap, showZoom = showZoom, overlays = overlays
    )
}

/** Whether the device has a working internet connection right now; follows changes. */
@Composable
internal fun rememberOnline(): Boolean {
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

internal const val AMBIENT_CACHE_BYTES = 300L * 1024 * 1024

internal fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

/** What a pin on the map marks. */
enum class PinKind { START, STOP, END }

/** A place of the trip on the map: [label] is "A", "B" or the number of the stop. */
data class MapPin(val point: GeoPoint, val kind: PinKind, val label: String)

internal const val PIN_START = "#00E676"
internal const val PIN_STOP = "#FF9100"
internal const val PIN_END = "#FF1744"

/** Orange for gravel, purple for tracks: as the gravel and offroad route colours. */
const val GRAVEL_COLOR = "#FF9100"
const val TRACK_COLOR = "#D500F9"
