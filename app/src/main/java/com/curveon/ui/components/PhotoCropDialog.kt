package com.curveon.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.roundToInt

/**
 * Cutting a profile picture: the photo behind a round window, dragged and zoomed with fingers until the face is
 * in it. [onDone] gets the square the window shows (the rider sees the result next, in the profile).
 */
@Composable
fun PhotoCropDialog(source: Bitmap, onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val image = remember(source) { source.asImageBitmap() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    // Where the photo's middle is, from the middle of the area.
    var offset by remember { mutableStateOf(Offset.Zero) }
    val diameter = minOf(box.width, box.height) * 0.86f
    // The photo always covers the round window: it cannot be zoomed out past that or dragged off.
    val minScale = if (diameter > 0f) diameter / minOf(source.width, source.height) else 1f

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
        return Bitmap.createBitmap(source, x, y, size, size)
    }

    LaunchedEffect(box) { if (box != IntSize.Zero && scale == 0f) scale = minScale }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBars()
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp).testTag("photo_crop"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.group_photo_crop_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(stringResource(R.string.group_photo_crop_hint), color = Color(0xFFCBD5E1), fontSize = 14.sp)
            Box(
                // The photo is larger than the area it is cut in: whatever is outside the area is not drawn.
                Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { box = it }
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp).testTag("photo_crop_cancel")) {
                    Text(stringResource(R.string.cancel), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { if (scale > 0f) onDone(cut()) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan), modifier = Modifier.weight(1f).height(52.dp).testTag("photo_crop_done")
                ) { Text(stringResource(R.string.group_photo_use), color = Color(0xFF00363D), fontWeight = FontWeight.Black) }
            }
        }
    }
}
