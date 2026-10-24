package com.curveon.qrscan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

private val Amber = Color(0xFFFF9100)

/**
 * Takes the rider's own picture inside Curveon, on the front camera. The camera shows the mirror view, and the picture is
 * saved the way it was shown. The phone's camera app is not used because whether it saves a selfie mirrored or not is
 * a setting of that app (and differs between phones), so the picture could come back the other way round from what the
 * rider looked at. The picture is written to [file]; [onPicture] is called when it is there. The camera permission is
 * asked for only now.
 */
@Composable
fun SelfieCameraDialog(file: File, onPicture: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val main = remember { ContextCompat.getMainExecutor(context) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(true) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    // Whether the lens that is really running is the front one: that is what decides if the picture is saved mirrored, so it
    // is always as the preview was (the rider may have asked for the front lens on a phone that has only one lens).
    var frontRunning by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    // (Re)start the camera when it is allowed and when the rider switches between the front and the back lens.
    DisposableEffect(granted, front) {
        var cancelled = false
        if (granted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (cancelled) return@addListener
                val provider = future.get()
                var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                // A device with one lens only: use that one.
                if (!provider.hasCamera(selector)) selector = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                // A picture for a 192 px profile needs no more than this, and it is quick to take and to read.
                val photo = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .build()
                runCatching {
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, photo)
                    frontRunning = camera.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT
                    capture = photo
                }.onFailure { failed = true }
            }, main)
        }
        onDispose {
            cancelled = true
            capture = null
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    fun shoot() {
        val photo = capture ?: return
        if (busy) return
        busy = true
        failed = false
        photo.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        // The front lens's picture is saved mirrored, as the preview showed it.
        val options = ImageCapture.OutputFileOptions.Builder(file)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = frontRunning })
            .build()
        photo.takePicture(options, main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) { busy = false; onPicture() }
            override fun onError(exception: ImageCaptureException) { busy = false; failed = true }
        })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideBars()
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("selfie_camera")) {
            if (granted) AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().displayCutoutPadding().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RoundButton(Icons.Default.Close, stringResource(R.string.qr_scan_cancel), onDismiss, "selfie_cancel")
                RoundButton(Icons.Default.Refresh, stringResource(R.string.selfie_switch), { front = !front }, "selfie_switch")
            }
            if (denied || failed) Text(
                stringResource(if (denied) R.string.selfie_denied else R.string.selfie_failed), color = Amber, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xAA000000)).padding(12.dp)
            )
            // The shutter, as in a camera app: a white disc in a ring.
            Box(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 28.dp).size(84.dp)
                    .border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape)
                    .background(if (busy || capture == null) Color(0x88FFFFFF) else Color.White)
                    .clickable(enabled = capture != null && !busy) { shoot() }
                    .testTag("selfie_shutter")
            )
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, tag: String) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(28.dp)) }
}

/** Full screen, without the system bars; see [QrScanDialog] for why the cutout mode is set. */
@Composable
private fun HideBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    LaunchedEffect(window) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
