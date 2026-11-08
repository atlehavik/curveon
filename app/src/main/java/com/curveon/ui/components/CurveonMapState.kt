package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.curveon.data.map.OfflineMaps
import com.curveon.ui.theme.*
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.*

/**
 * What [CurveonMapView] keeps while it is shown: the MapLibre view and map, the style loaded, the camera's mode and
 * where the rider is drawn. One object, so that the map's parts (its layers, the camera, the buttons) can be functions
 * of their own, each reading and changing what it needs here.
 */
@Stable
internal class CurveonMapState(context: android.content.Context, navigating: Boolean) {
    val offlineMaps = OfflineMaps(context.applicationContext)
    var map by mutableStateOf<MapLibreMap?>(null)
    /**
     * The map has drawn a frame and isn't destroyed: only then may rendered features be read
     * (before, e.g. just after turning the phone, MapLibre's renderer isn't there and it crashes).
     */
    var canQuery by mutableStateOf(false)
    var style by mutableStateOf<Style?>(null)
    // Camera: follows the rider (north up, heading up or 3D) until the rider pans the map.
    val prefs: android.content.SharedPreferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    fun savedOrientation(nav: Boolean) = runCatching { Orientation.valueOf(prefs.getString(if (nav) "orientationNav" else "orientationBrowse", null)!!) }
        .getOrDefault(if (nav) Orientation.THREE_D else Orientation.NORTH_UP)
    var orientation by mutableStateOf(savedOrientation(navigating))
    var orientationFor by mutableStateOf(navigating)
    // The angle of the 3D view (remembered): changed with the +/- that come beside the 3D button when it is held down.
    var tilt3d by mutableStateOf(prefs.getFloat("tilt3d", TILT_3D.toFloat()).toDouble().coerceIn(TILT_MIN, TILT_MAX))
    var tiltControls by mutableStateOf(false)
    var tiltPresses by mutableIntStateOf(0)
    var following by mutableStateOf(true)
    /** The map has been moved with the handlebar remote: the crosshair shows. */
    var remotePanned by mutableStateOf(false)
    var panPressedAt by mutableLongStateOf(0L)
    /** A long press at this point, as the finger would give it. */
    val longPressAt = mutableStateOf<((LatLng) -> Unit)?>(null)
    /** Where the rider is on screen (px), to hint at them from the edge once they're out of view. */
    var riderScreen by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    /** +/- while following nudges the speed-based zoom by this many levels. */
    var zoomBias by mutableDoubleStateOf(0.0)
    var cameraBearing by mutableFloatStateOf(0f)
    /** The map's zoom in tenths, kept only while it is shown (Settings → Display → Show zoom level); -1 otherwise. */
    var zoomTenths by mutableIntStateOf(-1)
    @Volatile var trackZoom = false
    var cameraIdleTick by mutableIntStateOf(0)
    /** Nudged when what the rider loop draws may change (a fix, the camera, the layout), to wake it. */
    val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** The rider stopped moving the map by hand: look at where it now shows at once, not at the next look. */
    val viewportWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** Where and how to draw the rider arrow this frame. */
    var puck by mutableStateOf<Puck?>(null)
    // Emptying an empty source still redraws the map, and location changes every second.
    var approachShown by mutableStateOf(true)
    /** Zoom to extents was pressed (counts the presses). */
    var fitTick by mutableIntStateOf(0)
    /** Rider position + camera follow: the rider carried forward between the once-a-second fixes. */
    val motion = com.curveon.data.location.RiderMotion()
    /** The area the gravel and soft roads drawn were read for. */
    var surfaceBounds by mutableStateOf<LatLngBounds?>(null)
    // Riding (from 10 km/h, until stopped again) hides the heat and weather buttons on the ordinary map.
    var moving by mutableStateOf(false)
    // The gravel heat: counted per routing data file in the background (once), shown for the files in view as they come.
    val heatCells = mutableStateMapOf<String, List<com.curveon.routing.HeatCell>>()
    val heatCounting = mutableStateListOf<String>()
    var heatTiles by mutableStateOf<List<String>?>(null)
    val mapView: MapView = run {
        MapLibre.getInstance(context)
        // Online map tiles seen once stay usable offline (MapLibre's default cache is 50 MB).
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, null)
        // Android 14 and later: drawn in a TextureView. The SurfaceView deadlocked on the Pixel (Android 17, three
        // "not responding" on 2026-10-21): coming back to the app resizes the window, the main thread waits for the
        // map to draw at the new size, and the map's eglSwapBuffers waits for the main thread (BLASTBufferQueue).
        // A TextureView draws with the rest of the screen and has no such wait; it costs a little more GPU and
        // battery (one more copy a frame). The X7 (Android 13) has never shown it and keeps the SurfaceView.
        val options = org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context)
            .textureMode(android.os.Build.VERSION.SDK_INT >= 34)
        MapView(context, options).apply {
            onCreate(null)
            // Keys are for the remote (RemoteControl): on its own the view would turn grey with focus and pan by itself.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            defaultFocusHighlightEnabled = false
        }
    }

    // Tap: north up → heading up → 3D; arriving in 3D shows two arrows beside it for the angle of the view
    // for a few seconds (held down shows them again).
    fun chooseOrientation(o: Orientation, navigating: Boolean, headingDeg: Float) {
        orientation = o
        tiltControls = o == Orientation.THREE_D
        tiltPresses++
        prefs.edit().putString(if (navigating) "orientationNav" else "orientationBrowse", orientation.name).apply()
        // Not following: turn the map where it is; following picks it up by itself.
        if (!following) map?.let { m ->
            m.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(m.cameraPosition)
                    .bearing(if (orientation == Orientation.NORTH_UP) 0.0 else headingDeg.toDouble())
                    .tilt(if (orientation == Orientation.THREE_D) tilt3d else 0.0)
                    .build()
            ), 600)
        }
    }

    fun tiltBy(delta: Double) {
        tilt3d = (tilt3d + delta).coerceIn(TILT_MIN, TILT_MAX)
        prefs.edit().putFloat("tilt3d", tilt3d.toFloat()).apply()
        tiltPresses++
        if (!following) map?.let { m -> m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).tilt(tilt3d).build()), 300) }
    }
}

@Composable
internal fun rememberCurveonMapState(navigating: Boolean): CurveonMapState {
    val context = LocalContext.current
    return remember { CurveonMapState(context, navigating) }
}

/** The tilt arrows hide again after a while without a press. */
@Composable
internal fun CurveonMapState.TiltControlsTimeout() {
    LaunchedEffect(tiltControls, tiltPresses) {
        if (!tiltControls) return@LaunchedEffect
        kotlinx.coroutines.delay(TILT_CONTROLS_MS)
        tiltControls = false
    }
}

@Composable
internal fun CurveonMapState.MapLifecycle() {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            canQuery = false
            mapView.onDestroy()
        }
    }
}
