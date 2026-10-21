package com.curveon.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.curveon.R
import com.curveon.ui.theme.ElectricCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Cutting a profile picture: the photo behind a round window, dragged and zoomed with fingers until the face is
 * in it. Once the rider is found in the photo, a style can lift them off the background (a sticker edge, neon, speed
 * lines in their [colour]). [onDone] gets the square the window shows (the rider sees the result next, in the profile).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoCropDialog(source: Bitmap, colour: Int, onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    // Where the photo's middle is, from the middle of the area.
    var offset by remember { mutableStateOf(Offset.Zero) }
    val diameter = minOf(box.width, box.height) * 0.86f
    // The photo always covers the round window: it cannot be zoomed out past that or dragged off.
    val minScale = if (diameter > 0f) diameter / minOf(source.width, source.height) else 1f

    // The rider cut out of the photo (null while looking, or when this phone can't).
    var subject by remember(source) { mutableStateOf<Bitmap?>(null) }
    var looking by remember(source) { mutableStateOf(true) }
    LaunchedEffect(source) {
        subject = PhotoStyles.findSubject(source)
        looking = false
    }
    var style by remember(source) { mutableStateOf(PhotoStyle.PLAIN) }
    val styled by produceState<Bitmap?>(null, source, subject, style, colour) {
        val cut = subject
        value = if (cut == null || style == PhotoStyle.PLAIN) null else withContext(Dispatchers.Default) { PhotoStyles.render(source, cut, style, colour) }
    }
    val shown = styled ?: source
    val image = remember(shown) { shown.asImageBitmap() }

    fun keepInside(o: Offset, s: Float): Offset {
        val maxX = maxOf(0f, source.width * s / 2 - diameter / 2)
        val maxY = maxOf(0f, source.height * s / 2 - diameter / 2)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** The part of the photo inside the window. */
    fun cut(): Bitmap {
        val s = scale
        val left = box.width / 2f - diameter / 2
        val top = box.height / 2f - diameter / 2
        val imageLeft = box.width / 2f + offset.x - source.width * s / 2
        val imageTop = box.height / 2f + offset.y - source.height * s / 2
        val x = ((left - imageLeft) / s).roundToInt().coerceIn(0, source.width - 1)
        val y = ((top - imageTop) / s).roundToInt().coerceIn(0, source.height - 1)
        val size = (diameter / s).roundToInt().coerceIn(1, minOf(source.width - x, source.height - y))
        return Bitmap.createBitmap(shown, x, y, size, size)
    }

    LaunchedEffect(box) { if (box != IntSize.Zero && scale == 0f) scale = minScale }

    val title = @Composable {
        Text(stringResource(R.string.group_photo_crop_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(stringResource(R.string.group_photo_crop_hint), color = Color(0xFFCBD5E1), fontSize = 14.sp)
    }
    val photo = @Composable { modifier: Modifier ->
        Box(
            // The photo is larger than the area it is cut in: whatever is outside the area is not drawn.
            modifier.clipToBounds().onSizeChanged { box = it }
                .pointerInput(minScale) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val s = (scale * zoom).coerceIn(minScale, minScale * 8)
                        scale = s
                        offset = keepInside(offset + pan, s)
                    }
                }
        ) {
            if (scale > 0f) Canvas(Modifier.fillMaxSize()) {
                val w = source.width * scale
                val h = source.height * scale
                val cx = size.width / 2
                val cy = size.height / 2
                drawImage(
                    image, dstOffset = IntOffset((cx + offset.x - w / 2).roundToInt(), (cy + offset.y - h / 2).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt())
                )
                // Everything outside the round window is dimmed.
                val window = Path().apply { addOval(Rect(Offset(cx, cy), diameter / 2)) }
                clipPath(window, clipOp = ClipOp.Difference) { drawRect(Color(0xCC000000)) }
                drawCircle(Color.White, radius = diameter / 2, center = Offset(cx, cy), style = Stroke(3.dp.toPx()))
            }
        }
    }
    // The styles show once the rider has been found in the photo; on a phone that can't, there is just the photo.
    val styles = @Composable {
        if (looking) Text(stringResource(R.string.group_photo_finding), color = Color(0xFF94A3B8), fontSize = 13.sp)
        else if (subject != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoStyle.entries.forEach { s ->
                val on = s == style
                Text(
                    stringResource(s.label), color = if (on) Color(0xFF00363D) else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) ElectricCyan else Color(0xFF1E293B))
                        .border(1.dp, if (on) ElectricCyan else Color(0xFF334155), RoundedCornerShape(20.dp))
                        .clickable { style = s }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("photo_style_${s.name.lowercase()}")
                )
            }
        }
    }
    val cancel = @Composable { modifier: Modifier ->
        OutlinedButton(onClick = onCancel, modifier = modifier.height(52.dp).testTag("photo_crop_cancel")) {
            Text(stringResource(R.string.cancel), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
    val done = @Composable { modifier: Modifier ->
        Button(
            onClick = { if (scale > 0f) onDone(cut()) },
            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan), modifier = modifier.height(52.dp).testTag("photo_crop_done")
        ) { Text(stringResource(R.string.group_photo_use), color = Color(0xFF00363D), fontWeight = FontWeight.Black, maxLines = 1) }
    }

    // Drawn edge to edge, under where the hidden bars would be: laid out in the safe area, so the buttons are never off screen.
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        HideSystemBars()
        // Compose sizes the window to the screen less the (hidden) bars: the strips left there are black too, not the map behind.
        val view = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(view) { (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(1f) }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp).testTag("photo_crop")) {
            if (maxWidth > maxHeight) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Lying down: the photo on the left, the words and buttons beside it.
                photo(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    title()
                    Spacer(Modifier.weight(1f))
                    styles()
                    done(Modifier.fillMaxWidth())
                    cancel(Modifier.fillMaxWidth())
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                title()
                photo(Modifier.weight(1f).fillMaxWidth())
                styles()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    cancel(Modifier.weight(1f))
                    done(Modifier.weight(1f))
                }
            }
        }
    }
}
