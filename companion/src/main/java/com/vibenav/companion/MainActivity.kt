package com.vibenav.companion

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

/** Setup screen: grant access once, then the service runs in the background. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = Surface)) {
                Screen()
            }
        }
    }

    @Composable
    private fun Screen() {
        val status by CompanionHub.status.collectAsStateWithLifecycle()
        var refresh by remember { mutableIntStateOf(0) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh++ } }

        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
        val notificationAccess = remember(refresh) { hasNotificationAccess() }
        val runtimeGranted = remember(refresh) { RUNTIME_PERMISSIONS.associateWith { has(it) } }

        Column(
            Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("VibeNav Companion", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(
                "Shares music, messages and calls from this phone with the VibeNav tablet over Bluetooth. " +
                    "Pair the phone and tablet in Android's Bluetooth settings first.",
                color = Muted, fontSize = 14.sp
            )

            Card("Status") {
                Line("Service", if (status.serviceRunning) "Running" else "Stopped", status.serviceRunning)
                Line("Tablet", status.tabletName ?: "Not connected", status.tabletName != null)
                Line("Mirrored messages", status.mirroredNotifications.toString(), true)
                Line("Now playing", status.mediaTitle ?: "–", status.mediaTitle != null)
                if (status.lastEvent.isNotEmpty()) Text(status.lastEvent, color = Muted, fontSize = 12.sp)
            }

            Card("Setup") {
                Step("Notification access (messages, music)", notificationAccess) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                Step("Bluetooth, microphone, contacts", runtimeGranted.values.all { it }) {
                    permissions.launch(RUNTIME_PERMISSIONS.toTypedArray())
                }
            }

            Button(
                onClick = {
                    if (status.serviceRunning) CompanionService.stop(this@MainActivity)
                    else CompanionService.start(this@MainActivity)
                },
                enabled = status.serviceRunning || has(Manifest.permission.BLUETOOTH_CONNECT),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (status.serviceRunning) "Stop sharing" else "Start sharing with tablet", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title.uppercase(), color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }

    @Composable
    private fun Line(label: String, value: String, ok: Boolean) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Muted, modifier = Modifier.weight(1f))
            Text(value, color = if (ok) Color.White else Amber, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun Step(label: String, done: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text((if (done) "✓  " else "•  ") + label, color = if (done) Green else Color.White, modifier = Modifier.weight(1f))
            if (!done) OutlinedButton(onClick = onClick) { Text("Grant") }
        }
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess(): Boolean {
        val me = ComponentName(this, VibeNotificationListener::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(me) == true
    }

    companion object {
        private val Bg = Color(0xFF0B1016)
        private val Surface = Color(0xFF151C24)
        private val Cyan = Color(0xFF00E5FF)
        private val Muted = Color(0xFF8A97A6)
        private val Amber = Color(0xFFFFB300)
        private val Green = Color(0xFF00E676)

        private val RUNTIME_PERMISSIONS = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
