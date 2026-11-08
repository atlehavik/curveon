package com.curveon.ui.components

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.curveon.data.map.OfflineMaps
import com.curveon.data.model.GeoPoint
import com.curveon.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.maplibre.android.style.expressions.Expression as E
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** The online map, only where the offline maps on the device don't cover what is in view. */
@Composable
internal fun CurveonMapState.OnlineLayers(hasOfflineMap: Boolean) {
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
}

/** Gravel and soft roads, dashed over the map's own roads once zoomed in far enough to choose by them. */
@Composable
internal fun CurveonMapState.SurfaceLayers(
    surfaceWays: (suspend (Double, Double, Double, Double) -> List<com.curveon.routing.SurfaceWay>)?,
    surfaceMode: SurfaceMode,
    surfaceZoom: Float,
    roadShade: Pair<String, String>,
    dark: Boolean,
    /** Quieter towns: the town squares (CityAreas), where the tarmac drawn here below the map's own small roads is left out. */
    townSquares: Set<Long>? = null,
) {
    LaunchedEffect(style, surfaceMode) {
        val s = style ?: return@LaunchedEffect
        val v = if (surfaceMode != SurfaceMode.OFF) Property.VISIBLE else Property.NONE
        s.layers.filter { it.id.startsWith(SURFACE) }.forEach { it.setProperties(visibility(v)) }
    }
    // Quieter towns: these small roads (sorted road by road, towns left out) go on up to TOWN_ROADS_ZOOM, where the map's
    // own come in (see TownRoads); otherwise only until the map's own small roads are in its tiles.
    LaunchedEffect(style, townSquares != null && surfaceMode != SurfaceMode.OFF) {
        val s = style ?: return@LaunchedEffect
        s.getLayer("$SURFACE-road")?.setMaxZoom(if (townSquares != null && surfaceMode != SurfaceMode.OFF) TOWN_ROADS_ZOOM else MAP_ROADS_ZOOM)
    }
    // The zoom the dashes start at. The gravel heat fades out as the dashes come in.
    LaunchedEffect(style, surfaceZoom) {
        val s = style ?: return@LaunchedEffect
        s.layers.filter { it.id.startsWith(SURFACE) }.forEach { it.setMinZoom(surfaceZoom) }
        s.getLayer("$HEAT-layer")?.setProperties(heatmapOpacity(E.interpolate(E.linear(), E.zoom(),
            E.stop(surfaceZoom, if (dark) 0.8f else 0.7f), E.stop(surfaceZoom + 1.5f, 0f))))
    }
    LaunchedEffect(townSquares) { surfaceBounds = null }
    LaunchedEffect(style, cameraIdleTick, surfaceMode != SurfaceMode.OFF, surfaceZoom, townSquares) {
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
            // In a town only gravel and soft ground: its many small tarmac streets are what made towns a white mush when zoomed out.
            fun inTown(w: com.curveon.routing.SurfaceWay): Boolean {
                val towns = townSquares ?: return false
                val m = (w.coords.size / 2 / 2) * 2
                return com.curveon.routing.CityAreas.square(w.coords[m], w.coords[m + 1]) in towns
            }
            val shown = ways.filter { it.kind == com.curveon.routing.SurfaceKind.GRAVEL || it.kind == com.curveon.routing.SurfaceKind.SOFT || !inTown(it) }
            FeatureCollection.fromFeatures(shown.map { w ->
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
}

/** The gravel heat map while planning: counted per routing data file in the background (once), shown for the files in view. */
@Composable
internal fun CurveonMapState.GravelHeatLayer(gravelHeat: GravelHeatSource?, heatButton: Boolean, heatOn: Boolean, onHeatButtonShown: (Boolean) -> Unit) {
    val onHeatButtonShownState = rememberUpdatedState(onHeatButtonShown)
    LaunchedEffect(heatButton) { onHeatButtonShownState.value(heatButton) }
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
}

/** The weather: while drawing a loop as given; on the ordinary map only with its button shown and on. */
@Composable
internal fun CurveonMapState.WeatherSymbols(
    weatherButton: Boolean,
    onWeatherMap: ((Boolean) -> Unit)?,
    weatherMapOn: Boolean,
    onWeatherButtonShown: (Boolean) -> Unit,
    weatherSpots: () -> List<com.curveon.data.weather.MapWeather>,
) {
    val context = LocalContext.current
    val onWeatherButtonShownState = rememberUpdatedState(onWeatherButtonShown)
    LaunchedEffect(weatherButton) { onWeatherButtonShownState.value(weatherButton) }
    val weatherShownNow = rememberUpdatedState(onWeatherMap == null || (weatherButton && weatherMapOn))
    val weatherSpotsNow = rememberUpdatedState(weatherSpots)
    // The weather symbols (on land, and on roads where there are any, by WeatherController). They come one at a time: set on
    // the map as they do, without composing the screen again for each. Per style: a new style starts with an empty source.
    LaunchedEffect(style) {
        val s = style ?: return@LaunchedEffect
        val density = context.resources.displayMetrics.density
        var imagesAdded = false
        var onMap = emptyList<com.curveon.data.weather.MapWeather>()
        androidx.compose.runtime.snapshotFlow { if (weatherShownNow.value) weatherSpotsNow.value() else emptyList() }.collect { spots ->
            if (spots == onMap) return@collect
            if (!imagesAdded) {
                imagesAdded = true
                com.curveon.data.weather.MapWeather.Kind.entries.forEach { k ->
                    if (s.getImage(weatherImageName(k)) == null) s.addImage(weatherImageName(k), weatherImage(k, density))
                }
            }
            val features = spots.map { w ->
                Feature.fromGeometry(Point.fromLngLat(w.point.lon, w.point.lat)).also {
                    it.addStringProperty("icon", weatherImageName(w.kind))
                    it.addStringProperty("temp", "${w.tempC}°")
                }
            }
            s.getSourceAs<GeoJsonSource>(WEATHER)?.setGeoJson(FeatureCollection.fromFeatures(features))
            onMap = spots
        }
    }
}

/** The roads read off the map: the name after the next turn, the road under the rider, and the roads round them. */
@Composable
internal fun CurveonMapState.RoadReading(
    roadNameProbe: Pair<Int, GeoPoint>?,
    onRoadName: (Int, com.curveon.data.road.MapRoad?) -> Unit,
    onRoadHere: ((com.curveon.data.road.MapRoad?) -> Unit)?,
    onRoadsNear: ((com.curveon.routing.LatLon, List<com.curveon.routing.RoadSnap.Line>) -> Unit)?,
    location: GeoPoint,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val locationState = rememberUpdatedState(location)
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
            // ~70 m round the rider, however far the map is zoomed out (then it's a few pixels and little is read).
            val r = (ROADS_NEAR_M / m.projection.getMetersPerPixelAtLatitude(here.latitude)).toFloat().coerceIn(4f, 400f)
            val lines = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), *roadLayers).flatMap { f ->
                val kind = roadKind(f) ?: return@flatMap emptyList()
                when (val g = f.geometry()) {
                    is LineString -> listOf(g.coordinates())
                    is org.maplibre.geojson.MultiLineString -> g.coordinates()
                    else -> emptyList()
                }.map { pts -> pts.map { com.curveon.routing.LatLon(it.latitude(), it.longitude()) } to kind }
            }
                // A road is drawn in several layers (casing, fill, arrows, name): the same line once.
                .distinctBy { it.first }
                .map { (pts, kind) -> com.curveon.routing.RoadSnap.Line(pts, kind) }
            onRoadsNearState.value?.invoke(com.curveon.routing.LatLon(here.latitude, here.longitude), lines)
        }
    }
}

/**
 * What kind of way a feature of the map's road layers is, for putting the rider on it, or null for one the
 * rider can't be on: rail, runways, taxiways, piers.
 */
private fun roadKind(f: org.maplibre.geojson.Feature): com.curveon.routing.RoadSnap.Kind? {
    val kind = f.getStringProperty("kind")
    val detail = f.getStringProperty("kind_detail")
    return when {
        kind == "rail" || detail in setOf("runway", "taxiway", "pier", "platform") -> null
        detail in setOf("footway", "cycleway", "path", "pedestrian", "steps", "bridleway", "corridor", "sidewalk", "crossing") ->
            com.curveon.routing.RoadSnap.Kind.PATH
        detail == "service" -> com.curveon.routing.RoadSnap.Kind.SERVICE
        else -> com.curveon.routing.RoadSnap.Kind.ROAD
    }
}

/** The buildings standing up in the 3D view (Settings → Display → 3D buildings). */
internal const val BUILDINGS_3D = "cv-buildings-3d"

/**
 * In the 3D view with the setting on, the buildings stand up from zoom 15, growing to their height by 16, in the downloaded
 * maps and in the online map. Off (or in a flat view) the layers are taken out again, so the map draws nothing more than
 * before. They lie under the road names and everything Curveon draws (route, tracks, pins), so the route is never hidden
 * behind a house; a building the map knows no height of is a low one.
 */
@Composable
internal fun CurveonMapState.Buildings3d(on: Boolean, dark: Boolean) {
    LaunchedEffect(style, on, dark) {
        val s = style ?: return@LaunchedEffect
        s.layers.filter { it.id.startsWith(BUILDINGS_3D) || it.id.startsWith("online-$BUILDINGS_3D") }.forEach { s.removeLayer(it) }
        if (!on) return@LaunchedEffect
        val firstLabel = (s.layers.firstOrNull { it is org.maplibre.android.style.layers.SymbolLayer && !it.id.startsWith("online-") && !it.id.startsWith("org.maplibre") }
            ?: s.layers.firstOrNull { it is org.maplibre.android.style.layers.SymbolLayer && !it.id.startsWith("org.maplibre") })?.id
        fun standing(id: String, source: String, sourceLayer: String, height: String, base: String, filter: E?) =
            org.maplibre.android.style.layers.FillExtrusionLayer(id, source).withSourceLayer(sourceLayer).withProperties(
                fillExtrusionColor(if (dark) "#2B3644" else "#D8D2C8"),
                fillExtrusionOpacity(0.9f),
                fillExtrusionHeight(E.interpolate(E.linear(), E.zoom(), E.stop(15, 0f), E.stop(16, E.coalesce(E.get(height), E.literal(5f))))),
                fillExtrusionBase(E.interpolate(E.linear(), E.zoom(), E.stop(15, 0f), E.stop(16, E.coalesce(E.get(base), E.literal(0f))))),
            ).apply { minZoom = 15f; filter?.let { setFilter(it) } }
        val layers = buildList {
            // The downloaded maps' own buildings layer, once per map ("buildings", "buildings~1", …).
            s.layers.filterIsInstance<org.maplibre.android.style.layers.FillLayer>().filter { it.id.substringBefore('~') == "buildings" }.forEachIndexed { i, flat ->
                add(standing("$BUILDINGS_3D-$i", flat.sourceId, "buildings", "height", "min_height", flat.filter))
            }
            // The online map's, shown and hidden with the rest of it ("online-" ids, see OnlineLayers).
            (s.getLayer("online-building") as? org.maplibre.android.style.layers.FillLayer)?.let { flat ->
                add(standing("online-$BUILDINGS_3D", flat.sourceId, "building", "render_height", "render_min_height", flat.filter)
                    .apply { setProperties(visibility(flat.visibility.value ?: Property.VISIBLE)) })
            }
        }
        layers.forEach { if (firstLabel != null) s.addLayerBelow(it, firstLabel) else s.addLayer(it) }
    }
}

/** The map's layers of small roads and paths that wait until [TOWN_ROADS_ZOOM] inside a town (by id, before any "~n"). */
private val TOWN_QUIET_LAYERS = setOf(
    "roads_minor", "roads_minor_casing", "roads_minor_service", "roads_minor_service_casing", "roads_other",
    "roads_tunnels_minor", "roads_tunnels_minor_casing", "roads_tunnels_other", "roads_tunnels_other_casing",
    "roads_bridges_minor", "roads_bridges_minor_casing", "roads_bridges_other", "roads_bridges_other_casing",
    "online-roads_minor", "online-roads_minor_casing", "online-roads_other",
)
/** The layers that draw footpaths and cycleways (with tracks, which stay), and their names. */
private val PATH_LAYERS = setOf(
    "roads_other", "roads_tunnels_other", "roads_tunnels_other_casing", "roads_bridges_other", "roads_bridges_other_casing",
    "roads_labels_minor", "online-roads_other",
)
/** Footways, cycleways, paths, pavements, steps and squares: never roads a motorbike rides. Tracks are not among them. */
private val NO_PATHS = E.not(E.`in`(E.get("kind_detail"), E.literal(arrayOf<Any>(
    "footway", "cycleway", "path", "sidewalk", "steps", "pedestrian", "crossing", "corridor", "bridleway"))))
/** The online map has them all as class "path" (tracks are class "track"). */
private val ONLINE_NO_PATHS = E.neq(E.get("class"), E.literal("path"))

/** Inside a town the small roads show from this zoom (out in the countryside, as before). */
internal const val TOWN_ROADS_ZOOM = 14f

/**
 * Settings → Display → Quieter towns: in a town (see CityAreas) the small roads and paths show only from
 * [TOWN_ROADS_ZOOM]; everywhere else, and with the setting off ([squares] null), the layers keep the filters they had,
 * so the map is exactly as before. The main roads always show.
 *
 * The map's tiles hold all their small roads as one line each, so it is decided tile by tile: a tile at least half
 * town ([com.curveon.routing.CityAreas.townTiles]) is quiet whole. Each such tile is a rectangle a little bigger than
 * the tile (its lines reach just past its edge), and a tile's lines lie within its own rectangle and no other.
 */
@Composable
internal fun CurveonMapState.TownRoads(squares: Set<Long>?, showPaths: Boolean = true, ownRoads: Boolean = false) {
    // Each layer's own filter, from before any change (per style: a new style brings its layers afresh).
    val own = remember(style) { HashMap<String, E?>() }
    // The box the town tiles were chosen for: a move within it changes nothing.
    var covered by remember(style, squares, showPaths, ownRoads) { mutableStateOf<LatLngBounds?>(null) }
    LaunchedEffect(style, squares, showPaths, ownRoads, cameraIdleTick) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val view = m.projection.visibleRegion.latLngBounds
        val have = covered
        if (have != null && have.contains(view.northEast) && have.contains(view.southWest)) return@LaunchedEffect
        // Two views' width round the view (at least about 20 km), so panning about a town changes nothing for a while.
        val dLat = maxOf((view.latitudeNorth - view.latitudeSouth) * 2, 0.1)
        val dLon = maxOf((view.longitudeEast - view.longitudeWest) * 2, 0.2)
        val box = LatLngBounds.from(view.latitudeNorth + dLat, view.longitudeEast + dLon, view.latitudeSouth - dLat, view.longitudeWest - dLon)
        val quiet = squares?.let { all ->
            val near = withContext(Dispatchers.Default) {
                val x0 = Math.floor(box.longitudeWest * com.curveon.routing.CityAreas.LON_DIV).toInt()
                val x1 = Math.floor(box.longitudeEast * com.curveon.routing.CityAreas.LON_DIV).toInt()
                val y0 = Math.floor(box.latitudeSouth * com.curveon.routing.CityAreas.LAT_DIV).toInt()
                val y1 = Math.floor(box.latitudeNorth * com.curveon.routing.CityAreas.LAT_DIV).toInt()
                all.filter { k -> val (x, y) = com.curveon.routing.CityAreas.unsquare(k); x in x0..x1 && y in y0..y1 }
            }
            fun inTownTile(zoom: Int): E? {
                val rects = com.curveon.routing.CityAreas.townTiles(near, zoom).map { (tx, ty) ->
                    val (south, west, north, east) = com.curveon.routing.CityAreas.tileBounds(tx, ty, zoom)
                    val mLat = (north - south) * TILE_MARGIN; val mLon = (east - west) * TILE_MARGIN
                    val ring = listOf(west - mLon to south - mLat, east + mLon to south - mLat, east + mLon to north + mLat, west - mLon to north + mLat, west - mLon to south - mLat)
                    E.within(org.maplibre.geojson.Polygon.fromLngLats(listOf(ring.map { org.maplibre.geojson.Point.fromLngLat(it.first, it.second) })))
                }
                return when (rects.size) { 0 -> null; 1 -> rects.first(); else -> E.any(*rects.toTypedArray()) }
            }
            // Decided on the zoom 12 tiles for every zoom below 14: a zoom 13 tile lies inside its zoom 12 tile's rectangle,
            // so a place is town or not the same way all the way in, and its small roads don't come and go while zooming.
            inTownTile(TOWN_TILE_ZOOM)?.let { E.any(E.gte(E.zoom(), E.literal(TOWN_ROADS_ZOOM)), E.not(it)) }
        }
        if (style !== s) return@LaunchedEffect
        covered = box
        for (layer in s.layers) {
            val id = layer.id.substringBefore('~')
            val pathLayer = id in PATH_LAYERS
            if (id !in TOWN_QUIET_LAYERS && !pathLayer) continue
            val base = own.getOrPut(layer.id) { (layer as? org.maplibre.android.style.layers.LineLayer)?.filter ?: (layer as? org.maplibre.android.style.layers.SymbolLayer)?.filter }
            // Settings → Display → Footpaths and cycleways off: those are left out (tracks, piers and the rest stay).
            val noPaths = if (pathLayer && !showPaths) (if (id.startsWith("online-")) ONLINE_NO_PATHS else NO_PATHS) else null
            // The downloaded maps' small roads, below TOWN_ROADS_ZOOM, are drawn from the routing data instead, road by road with
            // the towns left out ([ownRoads], SurfaceLayers): the map's own lines are one per tile and kind, too coarse to tell.
            val townQuiet = when {
                id !in TOWN_QUIET_LAYERS -> null
                // (Footpaths, when shown, are not in the routing data: those keep the map's own, tile by tile.)
                squares != null && ownRoads && !id.startsWith("online-") && !(showPaths && id.endsWith("_other")) -> E.gte(E.zoom(), E.literal(TOWN_ROADS_ZOOM))
                else -> quiet
            }
            val parts = listOfNotNull(base, townQuiet, noPaths)
            val filter = when (parts.size) { 0 -> E.literal(true); 1 -> parts.first(); else -> E.all(*parts.toTypedArray()) }
            when (layer) {
                is org.maplibre.android.style.layers.LineLayer -> layer.setFilter(filter)
                is org.maplibre.android.style.layers.SymbolLayer -> layer.setFilter(filter)
            }
        }
    }
}

/** The map tiles a town is decided on (see [TownRoads]). */
private const val TOWN_TILE_ZOOM = 12

/** How much bigger than a tile its rectangle is: the map's tiles reach 64/4096 (1.6%) past their edge. */
private const val TILE_MARGIN = 0.03
