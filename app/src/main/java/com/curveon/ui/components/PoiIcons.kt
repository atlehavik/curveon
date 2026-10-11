package com.curveon.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.curveon.data.gpx.PoiKind
import kotlin.math.roundToInt

val PoiKind.icon: ImageVector
    get() = when (this) {
        PoiKind.CAMPING -> Icons.Default.Festival
        PoiKind.FUEL -> Icons.Default.LocalGasStation
        PoiKind.LODGING -> Icons.Default.Hotel
        PoiKind.RESTAURANT -> Icons.Default.Restaurant
        PoiKind.CAFE -> Icons.Default.LocalCafe
        PoiKind.SHOP -> Icons.Default.LocalGroceryStore
        PoiKind.SCENIC -> Icons.Default.PhotoCamera
        PoiKind.PICNIC -> Icons.Default.Deck
        PoiKind.SWIMMING -> Icons.Default.Pool
        PoiKind.MUSEUM -> Icons.Default.Museum
        PoiKind.FERRY -> Icons.Default.DirectionsBoat
        PoiKind.WATER -> Icons.Default.WaterDrop
        PoiKind.TOILET -> Icons.Default.Wc
        PoiKind.PARKING -> Icons.Default.LocalParking
        PoiKind.TOWN -> Icons.Default.LocationCity
        PoiKind.INFO -> Icons.Default.Info
        PoiKind.ALERT -> Icons.Default.Warning
        PoiKind.CAMERA -> Icons.Default.Videocam
        PoiKind.PASS -> Icons.Default.Landscape
        PoiKind.WORKSHOP -> Icons.Default.Build
    }

/** Map image name for a kind's badge. */
fun poiImageName(kind: PoiKind) = "curveon-poi-${kind.name.lowercase()}"

/**
 * One badge per [PoiKind] for the map: the kind's icon in white on a disc in its category colour,
 * ringed in white like the zoomed-out dots, so the two read as the same thing.
 */
@Composable
fun rememberPoiBadges(): Map<String, android.graphics.Bitmap> {
    val density = LocalDensity.current
    val painters = PoiKind.entries.map { rememberVectorPainter(it.icon) }
    return remember(density) {
        val px = with(density) { 30.dp.toPx() }.roundToInt()
        val ring = with(density) { 2.dp.toPx() }
        PoiKind.entries.mapIndexed { i, kind ->
            val img = ImageBitmap(px, px)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(img), Size(px.toFloat(), px.toFloat())) {
                val r = size.minDimension / 2
                drawCircle(Color.White, r, Offset(r, r))
                drawCircle(Color(kind.category.color), r - ring, Offset(r, r))
                val inset = px * 0.2f
                translate(inset, inset) {
                    with(painters[i]) { draw(Size(px - 2 * inset, px - 2 * inset), colorFilter = ColorFilter.tint(Color.White)) }
                }
            }
            poiImageName(kind) to img.asAndroidBitmap()
        }.toMap()
    }
}
