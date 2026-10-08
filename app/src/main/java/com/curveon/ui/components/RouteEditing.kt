package com.curveon.ui.components

import android.graphics.PointF
import com.curveon.data.model.GeoPoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.hypot

enum class HandleKind { START, STOP, VIA, DESTINATION }

/** A point of the trip that can be dragged while editing it on the map; [stopIndex] for stops and via points. */
data class RouteHandle(val point: GeoPoint, val kind: HandleKind, val stopIndex: Int = -1, val label: String = "")

/**
 * Editing the previewed trip on the map. Drag a handle to move that place; drag the line itself
 * to make the route go through where you let go. A tap on a handle opens it.
 * [handles] are in trip order: the start (when planned from elsewhere), stops and via points, the destination.
 */
class RouteEditor(
    val handles: List<RouteHandle>,
    /** A new route is being calculated: the dashed guess stays until it is there. */
    val busy: Boolean,
    val onMoveHandle: (RouteHandle, GeoPoint) -> Unit,
    /** Route through [at], as stop number [stopIndex] (0 = first). */
    val onInsert: (stopIndex: Int, at: GeoPoint) -> Unit,
    val onTapHandle: (RouteHandle) -> Unit
)

internal const val EDIT_HANDLES = "curveon-edit-handles"
internal const val EDIT_DRAG = "curveon-edit-drag"

/** What a finger put down on the map took hold of, and the places either side of it (for the dashed guess). */
internal sealed interface EditGrab {
    val prev: GeoPoint?
    val next: GeoPoint?

    data class Handle(val handle: RouteHandle, override val prev: GeoPoint?, override val next: GeoPoint?) : EditGrab
    data class Line(val insertAt: Int, override val prev: GeoPoint?, override val next: GeoPoint?) : EditGrab
}

/** Handles win over the line; null when the finger is on neither (the map pans as usual). */
internal fun grabEdit(editor: RouteEditor, line: List<GeoPoint>, m: MapLibreMap, x: Float, y: Float, density: Float): EditGrab? {
    val handles = editor.handles
    val start = handles.firstOrNull { it.kind == HandleKind.START }?.point ?: line.firstOrNull()
    val between = handles.filter { it.kind == HandleKind.STOP || it.kind == HandleKind.VIA }
    val dest = handles.firstOrNull { it.kind == HandleKind.DESTINATION }?.point ?: line.lastOrNull()

    val hit = handles.withIndex().map { (i, h) ->
        val p = m.projection.toScreenLocation(LatLng(h.point.latitude, h.point.longitude))
        Triple(i, h, hypot(p.x - x, p.y - y))
    }.filter { it.third < HANDLE_GRAB_DP * density }.minByOrNull { it.third }
    if (hit != null) {
        val (i, h) = hit
        val prev = if (h.kind == HandleKind.START) null else handles.getOrNull(i - 1)?.point ?: start
        val next = if (h.kind == HandleKind.DESTINATION) null else handles.getOrNull(i + 1)?.point
        return EditGrab.Handle(h, prev, next)
    }

    if (line.size < 2) return null
    val at = m.projection.fromScreenLocation(PointF(x, y))
    val reach = LINE_GRAB_DP * density * m.projection.getMetersPerPixelAtLatitude(at.latitude)
    val segment = nearestSegment(line, at.latitude, at.longitude, reach) ?: return null
    // Which leg of the trip that is: count the stops that come before it along the line.
    var from = 0
    val stopAt = between.map { h -> nearestIndex(line, h.point, from).also { from = it } }
    val insertAt = stopAt.count { it <= segment }
    return EditGrab.Line(insertAt, between.getOrNull(insertAt - 1)?.point ?: start, between.getOrNull(insertAt)?.point ?: dest)
}

/** The dashed guess while dragging: from the place before, through the finger, to the place after. */
internal fun dragFeatures(grab: EditGrab, finger: GeoPoint): FeatureCollection {
    val pts = listOfNotNull(grab.prev, finger, grab.next).map { Point.fromLngLat(it.longitude, it.latitude) }
    return FeatureCollection.fromFeatures(listOfNotNull(
        if (pts.size >= 2) Feature.fromGeometry(LineString.fromLngLats(pts)) else null,
        Feature.fromGeometry(Point.fromLngLat(finger.longitude, finger.latitude))
    ))
}

internal fun handleFeatures(handles: List<RouteHandle>, dark: Boolean): FeatureCollection =
    FeatureCollection.fromFeatures(handles.map { h ->
        Feature.fromGeometry(Point.fromLngLat(h.point.longitude, h.point.latitude)).also {
            val cyan = if (dark) "#00E5FF" else "#0091EA"
            val (r, fill, ring) = when (h.kind) {
                HandleKind.STOP -> Triple(14f, "#FF9100", "#FFFFFF")
                HandleKind.VIA -> Triple(9f, "#FFFFFF", cyan)
                HandleKind.START -> Triple(12f, "#FFFFFF", cyan)
                HandleKind.DESTINATION -> Triple(15f, cyan, "#FFFFFF")
            }
            it.addNumberProperty("r", r)
            it.addStringProperty("fill", fill)
            it.addStringProperty("ring", ring)
            it.addStringProperty("label", h.label)
        }
    })

internal fun addEditLayers(s: Style, dark: Boolean) {
    val cyan = if (dark) "#00E5FF" else "#0091EA"
    val isPoint = Expression.eq(Expression.geometryType(), Expression.literal("Point"))
    s.addSource(GeoJsonSource(EDIT_DRAG))
    s.addSource(GeoJsonSource(EDIT_HANDLES))
    s.addLayer(
        LineLayer("$EDIT_DRAG-line", EDIT_DRAG).withProperties(
            lineColor(cyan), lineWidth(5f), lineOpacity(0.9f), lineDasharray(arrayOf(1.2f, 1.2f)),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        ).withFilter(Expression.not(isPoint))
    )
    s.addLayer(
        CircleLayer("$EDIT_HANDLES-dots", EDIT_HANDLES).withProperties(
            circleRadius(Expression.get("r")), circleColor(Expression.get("fill")),
            circleStrokeColor(Expression.get("ring")), circleStrokeWidth(3f)
        )
    )
    s.addLayer(
        SymbolLayer("$EDIT_HANDLES-labels", EDIT_HANDLES).withProperties(
            textField(Expression.get("label")), textFont(arrayOf("Noto Sans Medium")), textSize(13f),
            textColor("#000000"), textAllowOverlap(true), textIgnorePlacement(true)
        )
    )
    s.addLayer(
        CircleLayer("$EDIT_DRAG-dot", EDIT_DRAG).withProperties(
            circleRadius(13f), circleColor(cyan), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
        ).withFilter(isPoint)
    )
}

/** Index of the segment of [line] nearest to (lat, lon), if it is within [reachMeters]. */
private fun nearestSegment(line: List<GeoPoint>, lat: Double, lon: Double, reachMeters: Double): Int? {
    // Flat metres around the finger: plenty accurate over a few hundred metres.
    val kx = cos(Math.toRadians(lat)) * 111_320.0
    val ky = 110_540.0
    var best = -1
    var bestD = reachMeters
    for (i in 0 until line.size - 1) {
        val ax = (line[i].longitude - lon) * kx; val ay = (line[i].latitude - lat) * ky
        val bx = (line[i + 1].longitude - lon) * kx; val by = (line[i + 1].latitude - lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
        val d = hypot(ax + t * dx, ay + t * dy)
        if (d < bestD) { bestD = d; best = i }
    }
    return best.takeIf { it >= 0 }
}

/** Index of the point of [line] nearest to [p], searching from [from] on (stops come in order). */
private fun nearestIndex(line: List<GeoPoint>, p: GeoPoint, from: Int): Int {
    val kx = cos(Math.toRadians(p.latitude))
    var best = from
    var bestD = Double.MAX_VALUE
    for (i in from until line.size) {
        val d = hypot((line[i].longitude - p.longitude) * kx, line[i].latitude - p.latitude)
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

private const val HANDLE_GRAB_DP = 30f
private const val LINE_GRAB_DP = 22f
