package com.curveon.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import com.curveon.data.map.OfflineMaps
import com.curveon.ui.theme.*
import kotlinx.coroutines.flow.first
import org.maplibre.android.style.expressions.Expression as E
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/**
 * Gravel (dashed grey) and softer or unknown ground (dashed brown) over the map's roads, under
 * the road names, in quiet colours that sit with the map's own roads. Wider for bigger roads, like
 * the map draws them.
 */
/** Thin dashes, a little under the map's own small roads (which are at least 1.6 px), so a gravel road is never the heaviest line on the map. */
internal fun roadWidth(scale: Float = 1f, extra: Float = 0f) = E.interpolate(E.exponential(1.6f), E.zoom(),
    E.stop(SURFACE_ZOOM, 1f * scale + extra), E.stop(14.5, 1.2f * scale + extra), E.stop(15, 1.6f * scale + extra), E.stop(18, 7f * scale + extra))

/** Plain dashes: thin far out, then as wide as the map's own small roads (11 px at zoom 18), so the dashes are the road. */
internal fun dashedRoadWidth() = E.interpolate(E.exponential(1.6f), E.zoom(),
    E.stop(SURFACE_ZOOM, 1f), E.stop(12.5, 1.6f), E.stop(15, 2f), E.stop(18, 11f))

/** Ground under plain dashes: covers the map's own road (bigger ones up to 13 px at zoom 18) and its edge, from where the map draws roads. */
internal fun dashedRoadGround() = E.interpolate(E.exponential(1.6f), E.zoom(),
    E.stop(12, 0f), E.stop(12.5, 3f), E.stop(15, 5f), E.stop(18, 16f))

internal fun surfaceWidth(base: Float) = E.interpolate(E.linear(), E.zoom(),
    E.stop(SURFACE_ZOOM, E.product(E.literal(base * 0.6f), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))),
    E.stop(14, E.product(E.literal(base), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))),
    E.stop(18, E.product(E.literal(base * 3f), E.match(E.get("rank"), E.literal(1f), E.stop(2, 1.8f), E.stop(1, 1.3f)))))

internal fun addSurfaceLayers(s: Style, dark: Boolean) {
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
internal fun addSurfaceOverLayers(s: Style, dark: Boolean) {
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
internal fun raisePlaceNames(s: Style) {
    s.layers.filter { l -> l.id.substringBefore('~').let { it.startsWith("places_") || it == "online-places" } }.forEach { l ->
        if (s.removeLayer(l)) s.addLayer(l)
    }
}

internal fun addOverlayLayers(s: Style, dark: Boolean) {
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
    // A track shown that the route runs along: a thin stripe in its colour inside the route line, with a thin edge so it
    // shows also when the track has the route's own colour.
    s.addSource(GeoJsonSource(GPX_ON_ROUTE))
    s.addLayer(
        LineLayer("$GPX_ON_ROUTE-casing", GPX_ON_ROUTE).withProperties(
            lineColor(if (dark) "#0B0F14" else "#FFFFFF"), routeWidth(2.6f, 4.4f, 6.8f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer("$GPX_ON_ROUTE-line", GPX_ON_ROUTE).withProperties(
            lineColor(org.maplibre.android.style.expressions.Expression.get("color")), routeWidth(1.4f, 2.4f, 3.8f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
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
