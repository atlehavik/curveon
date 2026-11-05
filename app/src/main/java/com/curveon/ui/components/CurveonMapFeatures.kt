package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import com.curveon.data.model.GeoPoint
import com.curveon.ui.theme.*
import kotlinx.coroutines.flow.first
import org.maplibre.android.style.expressions.Expression as E
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** The unpaved stretches of [route], one line per run of the same kind. */
internal fun surfaceRuns(route: MapRoute?): FeatureCollection {
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

internal fun lineFeatures(lines: List<List<GeoPoint>>): FeatureCollection =
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
internal fun followZoom(speedKmh: Float, toTurnMeters: Int?, threeD: Boolean): Double {
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
internal fun ease(from: Double, to: Double, dt: Double, tau: Double) = from + (to - from) * (1 - kotlin.math.exp(-dt / tau))

internal fun haversine(a: GeoPoint, b: GeoPoint): Double =
    com.curveon.routing.Geo.distance(a.latitude, a.longitude, b.latitude, b.longitude)

/** Waits while the screen is out of sight, so a looping effect doesn't run in the background. */
internal suspend fun Lifecycle.untilStarted() {
    currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
}
