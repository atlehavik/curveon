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
) {
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
