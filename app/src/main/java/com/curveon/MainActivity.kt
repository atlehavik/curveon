package com.curveon

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.curveon.ui.screens.DashboardScreen
import com.curveon.ui.theme.CockpitBackground
import com.curveon.ui.theme.CurveonTheme
import com.curveon.ui.viewmodel.DashboardViewModel

class MainActivity : ComponentActivity() {

    private val dashboardViewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)

        // The language may just have changed (the screen restarts then): redo texts made earlier.
        dashboardViewModel.refreshTexts()

        // Turns with the device, or locked one way (Settings → Display) so bumps can't turn it.
        requestedOrientation = dashboardViewModel.screenOrientation.value
        enableEdgeToEdge()
        hideSystemBars()

        // Keep screen awake for motorcycle mounting
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val orientation by dashboardViewModel.screenOrientation.collectAsState()
            LaunchedEffect(orientation) { if (requestedOrientation != orientation) requestedOrientation = orientation }
            val isFullScreenMap by dashboardViewModel.isFullScreenMap.collectAsState()

            // Back handler: if full screen map, return to dashboard
            BackHandler(enabled = isFullScreenMap) {
                dashboardViewModel.toggleFullScreenMap()
            }

            // Location permissions launcher for real GPS telemetry
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { granted ->
                dashboardViewModel.phoneCompanion.restart() // Bluetooth permission may just have been granted
                if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                    dashboardViewModel.startGps()
                }
            }

            LaunchedEffect(Unit) {
                if (hasLocationPermission()) {
                    dashboardViewModel.startGps()
                } else permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        *(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),
                        *(if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray())
                    )
                )
            }

            CurveonTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CockpitBackground
                ) {
                    DashboardScreen(viewModel = dashboardViewModel)
                }
            }
        }
    }

    /**
     * The status and navigation bars stay hidden while riding, so the app fills the whole screen.
     * Swipe in from the top or bottom edge and they appear over the app for a few seconds, then
     * hide again. Dialogs and permission prompts can bring them back, so they are re-hidden
     * whenever the app regains focus.
     */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        dashboardViewModel.setOnScreen(true)
    }

    // Out of sight with no trip to navigate: Curveon sleeps until it is back on screen.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) dashboardViewModel.setOnScreen(false)
    }

    override fun onResume() {
        super.onResume()
        dashboardViewModel.phoneCompanion.reconnectSoon() // the rider is looking: don't wait out a back-off
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    companion object {
        /** The screen, so "Close Curveon" in the notification can close it. */
        @Volatile var current: java.lang.ref.WeakReference<MainActivity>? = null
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
