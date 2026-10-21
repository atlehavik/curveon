package com.curveon.qrscan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.curveon.phone.QrCode
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val Amber = Color(0xFFFF9100)

/** True on a device with a camera at all; the scan button is left out on one without. */
@Composable
fun hasCamera(): Boolean = LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/** What a Curveon group invite (or the link for the rider's other device) looks like, in a QR code. */
private fun isInvite(text: String) = text.startsWith("curveon://join#") || text.contains("/join#v=")

/**
 * Scans a group invite with the camera. The camera permission is asked for only now, when scanning starts. The picture
 * is read here on the device (ZXing), and nothing of it is kept or sent anywhere. [onInvite] gets the link.
 */
@Composable
fun QrScanDialog(onInvite: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideBars()
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("qr_scan")) {
            if (granted) CameraScan(onInvite = onInvite, onOther = { wrong = true })
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xAA000000)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.qr_scan_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(
                    stringResource(when { denied -> R.string.qr_scan_denied; wrong -> R.string.qr_scan_wrong; else -> R.string.qr_scan_hint }),
                    color = if (denied || wrong) Amber else Color.White, fontSize = 15.sp, textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).height(56.dp).widthIn(min = 220.dp).testTag("qr_scan_cancel")
            ) { Text(stringResource(R.string.qr_scan_cancel), color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Full screen, without the system bars. */
@Composable
private fun HideBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    LaunchedEffect(window) {
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** The camera's picture on screen, and every frame looked at for a QR code, the latest first (older ones are skipped). */
@Composable
private fun CameraScan(onInvite: (String) -> Unit, onOther: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisThread = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val main = remember { ContextCompat.getMainExecutor(context) }
    val latestInvite by rememberUpdatedState(onInvite)
    val latestOther by rememberUpdatedState(onOther)

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisThread.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { view ->
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    // Big enough to read a dense code held at arm's length, small enough to keep up.
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisThread) { image -> look(image, done, main, { latestInvite(it) }, { latestOther() }) }
                    runCatching {
                        future.get().apply {
                            unbindAll()
                            bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }
                }, main)
            }
        }
    )
}

/** One frame: its brightness plane to ZXing. A Curveon invite ends the scan; any other code just says so. */
private fun look(image: ImageProxy, done: AtomicBoolean, main: Executor, invite: (String) -> Unit, other: () -> Unit) {
    try {
        if (done.get()) return
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val buffer = plane.buffer
        val stride = plane.rowStride
        val luma = ByteArray(w * h)
        for (row in 0 until h) {
            buffer.position(row * stride)
            buffer.get(luma, row * w, w)
        }
        val text = QrCode.read(luma, w, h) ?: return
        if (isInvite(text)) { if (done.compareAndSet(false, true)) main.execute { invite(text) } }
        else main.execute { other() }
    } finally {
        image.close()
    }
}
