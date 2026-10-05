package com.vibenav

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
import com.vibenav.ui.screens.DashboardScreen
import com.vibenav.ui.theme.CockpitBackground
import com.vibenav.ui.theme.VibeNavTheme
import com.vibenav.ui.viewmodel.DashboardViewModel

class MainActivity : ComponentActivity() {

    private val dashboardViewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()

        // Keep screen awake for motorcycle mounting
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
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
                    RideService.start(this@MainActivity)
                }
            }

            LaunchedEffect(Unit) {
                if (hasLocationPermission()) {
                    dashboardViewModel.startGps()
                    RideService.start(this@MainActivity)
                } else permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        *(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),
                        *(if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray())
                    )
                )
            }

            VibeNavTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CockpitBackground
                ) {
                    DashboardScreen(viewModel = dashboardViewModel)
                }
            }
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
