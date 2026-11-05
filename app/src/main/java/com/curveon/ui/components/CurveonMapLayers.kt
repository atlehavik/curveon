package com.curveon.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.curveon.data.model.GeoPoint
import com.curveon.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Style: day or night. Switching style drops our layers, so they are re-added each time. */
@Composable
internal fun CurveonMapState.MapStyleLoader(dark: Boolean, mapVersion: Int, roadShade: Pair<String, String>) {
    val context = LocalContext.current
    val poiBadges = rememberPoiBadges()
    val signImages = remember { roadSignImages(context.resources.displayMetrics.density) }
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
}

/** The route being ridden, or the trips previewed, with their gravel and tracks over them. */
@Composable
internal fun CurveonMapState.RouteLines(routePoints: List<GeoPoint>, previewRoutes: List<MapRoute>, routeSurfaces: List<Int>?) {
    val previewRoutesState = rememberUpdatedState(previewRoutes)
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
}

/** GPX tracks shown on the map, and where they run along the route being ridden. */
@Composable
internal fun CurveonMapState.TrackLines(gpxTracks: List<com.curveon.data.gpx.OverlayTrack>, gpxOnRoute: List<Pair<Int, List<List<GeoPoint>>>>) {
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
    LaunchedEffect(style, gpxOnRoute) {
        val s = style ?: return@LaunchedEffect
        val features = FeatureCollection.fromFeatures(gpxOnRoute.map { (color, runs) ->
            Feature.fromGeometry(org.maplibre.geojson.MultiLineString.fromLngLats(runs.map { run -> run.map { Point.fromLngLat(it.longitude, it.latitude) } }))
                .also { it.addStringProperty("color", hex(color)) }
        })
        if (s.isFullyLoaded) s.getSourceAs<GeoJsonSource>(GPX_ON_ROUTE)?.setGeoJson(features)
    }
}

/** Give way, stop and speed signs along the road, GPX waypoints, and the way to the nearest track. */
@Composable
internal fun CurveonMapState.SignsAndPois(
    giveWaySigns: List<com.curveon.data.routing.GiveWayAlert>,
    speedSigns: List<com.curveon.routing.SpeedChange>,
    gpxPois: List<com.curveon.data.gpx.GpxPoi>,
    trackHint: com.curveon.data.gpx.TrackHint?,
) {
    val context = LocalContext.current
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
}

/** The trip's pins, the other riders, an area drawn, the place picked, the way to the route's start, and editing handles. */
@Composable
internal fun CurveonMapState.PlanMarks(
    markers: List<MapPin>,
    groupRiders: List<MapGroupRider>,
    sketch: List<GeoPoint>,
    pickedPoint: GeoPoint?,
    location: GeoPoint,
    approachTo: GeoPoint?,
    editor: RouteEditor?,
    previewRoutes: List<MapRoute>,
    dark: Boolean,
) {
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
}
