package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.curveon.R
import com.curveon.data.gpx.GpxPoi
import com.curveon.data.model.GeoPoint
import com.curveon.data.osm.GpxParser
import com.curveon.ui.theme.*
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything the GPX file says about a waypoint tapped on the map: description and comments,
 * address, phone, links, elevation, when it was added, its categories, the track it came with
 * and where it is. One action: go there (and call, when it has a number and the phone is linked).
 */
@Composable
fun PoiDialog(poi: GpxPoi, from: GeoPoint?, onGo: () -> Unit, onCall: ((String) -> Unit)?, onDismiss: () -> Unit) {
    val color = Color(poi.category.color)
    val name = poi.displayName(LocalContext.current)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(
            Modifier.padding(24.dp).widthIn(max = 900.dp).clip(RoundedCornerShape(20.dp)).background(CockpitSurface)
                .border(1.dp, CockpitBorder, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Kind, and how far away it is.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(poi.kind.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    listOf(stringResource(poi.kind.label), poi.symbol).filter { it.isNotBlank() }.distinctBy { it.lowercase() }.joinToString(" · ").uppercase(),
                    color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                from?.let { Text(distanceText(it, poi), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
            Text(name, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)

            // Wide screens: the text on the left, the facts (address, phone, …) beside it so they
            // stay in view however long the description is. Narrow (a phone upright): one under the
            // other, all scrolling together; side by side the text would get almost no width.
            val description = tidy(poi.description).takeIf { it.isNotEmpty() && it != poi.name }
            val comment = tidy(poi.comment).takeIf { it.isNotEmpty() && it != poi.name && it != description }
            val texts: @Composable () -> Unit = {
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    description?.let { Text(linkified(it), color = TextPrimary, fontSize = 15.sp, lineHeight = 21.sp) }
                    comment?.let { Text(linkified(it), color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp) }
                } }
            }
            val facts: @Composable () -> Unit = {
                if (poi.address.isNotEmpty()) Fact(Icons.Default.Home, poi.address.joinToString("\n"))
                if (poi.phone.isNotBlank()) Fact(Icons.Default.Phone, poi.phone)
                // Links that the description already shows in full aren't repeated.
                poi.links.filter { (href, _) -> href !in poi.description && href !in poi.comment }.forEach { (href, text) ->
                    Fact(Icons.Default.Link, linkified(href, text.ifBlank { href.substringAfter("://").substringBefore('?').trimEnd('/') }))
                }
                poi.elevation?.let { Fact(Icons.Default.Terrain, stringResource(R.string.poi_elevation, it)) }
                dateText(poi.time)?.let { Fact(Icons.Default.Event, stringResource(R.string.poi_added, it)) }
                if (poi.categories.isNotEmpty()) Fact(Icons.Default.Sell, poi.categories.joinToString(", "))
                if (poi.trackName.isNotBlank()) Fact(Icons.Default.Route, poi.trackName)
                Fact(Icons.Default.Place, String.format(Locale.ROOT, "%.5f, %.5f", poi.lat, poi.lon))
            }
            val hasText = description != null || comment != null
            if (isNarrowScreen() || !hasText) Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasText) texts()
                facts()
            } else Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { texts() }
                Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { facts() }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = TextSecondary) }
                if (onCall != null && poi.phone.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    PillButton(Icons.Default.Call, stringResource(R.string.call), CockpitSurfaceVariant, TextPrimary) { onCall(poi.phone) }
                }
                Spacer(Modifier.width(8.dp))
                PillButton(Icons.Default.Navigation, stringResource(R.string.take_me_there), ElectricCyan, Color(0xFF00363D), onClick = onGo)
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) = Fact(icon, AnnotatedString(text))

@Composable
private fun Fact(icon: ImageVector, text: AnnotatedString) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        SelectionContainer { Text(text, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp) }
    }
}

/** Text from a GPX file without the empty lines and spaces some files are padded with. */
private fun tidy(text: String): String =
    text.replace("\r", "").lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n").trim()

private val URL = Regex("""(https?://|www\.)[^\s<>"]+""")

/** [text] with its web addresses tappable (they open in the browser when there is a connection). */
private fun linkified(text: String, label: String? = null): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = ElectricCyan, textDecoration = TextDecoration.Underline))
    if (label != null) {
        withLink(LinkAnnotation.Url(text, style)) { append(label) }
        return@buildAnnotatedString
    }
    var at = 0
    for (m in URL.findAll(text)) {
        append(text, at, m.range.first)
        val url = m.value.trimEnd('.', ',', ')', '!')
        withLink(LinkAnnotation.Url(if (url.startsWith("www.")) "https://$url" else url, style)) { append(url) }
        at = m.range.first + url.length
    }
    append(text, at, text.length)
}

/** "12 km NE" from the rider. */
@Composable
private fun distanceText(from: GeoPoint, poi: GpxPoi): String {
    val km = GpxParser.haversineDistanceKm(from.latitude, from.longitude, poi.lat, poi.lon)
    val la1 = Math.toRadians(from.latitude); val la2 = Math.toRadians(poi.lat); val dLon = Math.toRadians(poi.lon - from.longitude)
    val bearing = (Math.toDegrees(atan2(sin(dLon) * cos(la2), cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon))) + 360) % 360
    val dir = stringResource(R.string.compass_points).split(',')[((bearing + 22.5) / 45).toInt() % 8]
    val dist = if (km < 1) stringResource(R.string.distance_m, km * 1000) else if (km < 10) stringResource(R.string.distance_km, km) else stringResource(R.string.distance_km_whole, km)
    return "$dist $dir"
}

/** "2025-03-15T11:39:09Z" → "15 Mar 2025". */
private fun dateText(iso: String): String? = runCatching {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso.take(10))!!
    java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d)
}.getOrNull()
