package com.curveon.companion

import com.curveon.phone.*
import android.Manifest
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
import androidx.compose.ui.res.stringResource
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
        joinFromIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = Surface)) {
                Screen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        joinFromIntent(intent)
    }

    /** An invite link (curveon://join#...) opened on this phone. */
    private fun joinFromIntent(intent: Intent?) {
        val uri = intent?.dataString ?: return
        intent.data = null // handled once, not again after a rotation
        GroupHub.start(applicationContext)
        GroupHub.handle(com.curveon.protocol.Message.GroupJoin(uri))
    }

    override fun onResume() {
        super.onResume()
        GroupHub.start(applicationContext)
        // Opening the app is enough to (re)start sharing, e.g. after an update or a reboot.
        if (!PhoneHub.status.value.serviceRunning && has(Manifest.permission.BLUETOOTH_CONNECT) && !stoppedByUser) {
            CompanionService.start(this)
        }
    }

    /** Remembers an explicit "Stop sharing" so we don't restart behind the rider's back. */
    private var stoppedByUser: Boolean
        get() = getSharedPreferences("companion", MODE_PRIVATE).getBoolean("stopped", false)
        set(v) { getSharedPreferences("companion", MODE_PRIVATE).edit().putBoolean("stopped", v).apply() }

    @Composable
    private fun Screen() {
        val status by PhoneHub.status.collectAsStateWithLifecycle()
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
            Text("Curveon Companion", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(
                "Shares music, messages and calls from this phone with the Curveon tablet over Bluetooth. " +
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
                Step("Bluetooth, microphone, contacts, calls", runtimeGranted.values.all { it }) {
                    permissions.launch(RUNTIME_PERMISSIONS.toTypedArray())
                }
            }

            GroupCard()

            BatteryTestCard(notificationAccess)

            if (BuildConfig.DEV) Card("Test") {
                Text("Send a fake message or call through the real chain to the tablet. " +
                    "What the tablet sends back (reply, answer, decline) shows up under Status.", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { TestScenarios.postMessage(this@MainActivity) }, modifier = Modifier.weight(1f)) { Text("Message") }
                    OutlinedButton(onClick = { TestScenarios.postCall(this@MainActivity) }, modifier = Modifier.weight(1f)) { Text("Incoming call") }
                    OutlinedButton(onClick = { TestScenarios.clear(this@MainActivity) }, modifier = Modifier.weight(0.7f)) { Text("Clear") }
                }
            }

            Button(
                onClick = {
                    if (status.serviceRunning) { stoppedByUser = true; CompanionService.stop(this@MainActivity) }
                    else { stoppedByUser = false; CompanionService.start(this@MainActivity) }
                },
                enabled = status.serviceRunning || has(Manifest.permission.BLUETOOTH_CONNECT),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (status.serviceRunning) "Stop sharing" else "Start sharing with tablet", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    /** The active group, and what only this phone can set: relay address, nickname, data saver, invites. */
    @Composable
    private fun GroupCard() {
        val summary by GroupHub.summary.collectAsStateWithLifecycle()
        val store = remember { GroupStore(this@MainActivity) }
        var relay by remember { mutableStateOf(store.relayHost) }
        var nick by remember { mutableStateOf(store.profile?.nickname.orEmpty()) }
        var saver by remember { mutableStateOf(store.dataSaver) }
        // Saved a moment after the last keystroke, so the group is not told about every letter.
        LaunchedEffect(relay) { kotlinx.coroutines.delay(600); store.relayHost = relay }
        LaunchedEffect(nick) {
            kotlinx.coroutines.delay(800)
            if (nick.isNotBlank() && nick != store.profile?.nickname) {
                val old = store.profile
                GroupHub.handle(com.curveon.protocol.Message.GroupProfile(nick.trim(), old?.colour ?: 0xFF00E5FF.toInt(), old?.icon, old?.photoJpegBase64))
                store.profile = com.curveon.protocol.Message.GroupProfile(nick.trim(), old?.colour ?: 0xFF00E5FF.toInt(), old?.icon, old?.photoJpegBase64)
            }
        }
        LaunchedEffect(Unit) {
            GroupHub.errors.collect { code ->
                val text = when (code) {
                    com.curveon.protocol.Message.GroupErrorCode.INVALID_INVITE -> R.string.group_error_invalid_invite
                    com.curveon.protocol.Message.GroupErrorCode.EXPIRED_INVITE -> R.string.group_error_expired_invite
                    com.curveon.protocol.Message.GroupErrorCode.ALREADY_MEMBER -> R.string.group_error_already_member
                    com.curveon.protocol.Message.GroupErrorCode.NO_RELAY -> R.string.group_error_no_relay
                    else -> R.string.group_error_other
                }
                android.widget.Toast.makeText(this@MainActivity, text, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        Card(stringResource(R.string.group_ride)) {
            val s = summary
            if (s == null) Text(stringResource(R.string.group_status_none), color = Muted, fontSize = 13.sp)
            else {
                val status = stringResource(when (s.status) {
                    com.curveon.protocol.Message.GroupStatus.CONNECTED -> R.string.group_connected
                    com.curveon.protocol.Message.GroupStatus.OFFLINE -> R.string.group_offline
                    com.curveon.protocol.Message.GroupStatus.PAUSED -> R.string.group_paused
                    com.curveon.protocol.Message.GroupStatus.REMOVED -> R.string.group_removed
                })
                Text(
                    stringResource(R.string.group_status_line, s.name ?: stringResource(R.string.group_unnamed), s.members, status),
                    color = if (s.status == com.curveon.protocol.Message.GroupStatus.CONNECTED) Green else Amber,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = {
                    GroupHub.inviteLink(s.groupId)?.let { link ->
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, getString(R.string.group_invite_text, link)), null
                        ))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.group_invite)) }
            }
            OutlinedTextField(nick, { nick = it }, label = { Text(stringResource(R.string.group_nickname)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(relay, { relay = it }, label = { Text(stringResource(R.string.group_relay)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.group_relay_hint), color = Muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.group_data_saver), color = Color.White, fontSize = 14.sp)
                    Text(stringResource(R.string.group_data_saver_hint), color = Muted, fontSize = 12.sp)
                }
                Switch(saver, { saver = it; store.dataSaver = it; GroupHub.setDataSaver(it) })
            }
        }
    }

    /** Start/stop the battery test, and mAh per hour for each app that navigated. */
    @Composable
    private fun BatteryTestCard(notificationAccess: Boolean) {
        val test by BatteryTest.state.collectAsStateWithLifecycle()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(test.running) { while (test.running) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) } }
        Card(stringResource(R.string.battery_test)) {
            Text(stringResource(R.string.battery_test_hint), color = Muted, fontSize = 13.sp)
            if (!notificationAccess) Text(stringResource(R.string.battery_test_no_access), color = Amber, fontSize = 13.sp)
            if (test.running) Text(
                stringResource(R.string.battery_test_running, duration(now - test.startedAt), bucketName(test.now)),
                color = Color.White, fontSize = 14.sp
            ) else if (test.file != null) Text(stringResource(R.string.battery_test_last), color = Color.White, fontSize = 14.sp)
            BatteryTest.Bucket.entries.forEach { bucket ->
                val t = test.totals[bucket] ?: return@forEach
                val name = bucketName(bucket)
                Text(
                    if (t.mahPerHour.isNaN()) stringResource(R.string.battery_test_short, "$name · ${duration(t.ms)}")
                    else stringResource(R.string.battery_test_row, "$name · ${duration(t.ms)}",
                        "%.0f".format(t.mah), "%.0f".format(t.mahPerHour), t.brightness),
                    color = if (bucket == BatteryTest.Bucket.NONE) Muted else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
            if (test.chargingMs > 0) Text(stringResource(R.string.battery_test_charging, duration(test.chargingMs)), color = Amber, fontSize = 13.sp)
            test.file?.let { Text(stringResource(R.string.battery_test_file, it.name), color = Muted, fontSize = 12.sp) }
            Button(
                onClick = { if (test.running) BatteryTest.stop(this@MainActivity) else BatteryTest.start(this@MainActivity) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(if (test.running) R.string.battery_test_stop else R.string.battery_test_start), fontWeight = FontWeight.Bold) }
        }
    }

    @Composable
    private fun bucketName(b: BatteryTest.Bucket) = stringResource(when (b) {
        BatteryTest.Bucket.NONE -> R.string.battery_nav_none
        BatteryTest.Bucket.GOOGLE_MAPS -> R.string.battery_nav_maps
        BatteryTest.Bucket.CURVEON -> R.string.battery_nav_curveon
    })

    private fun duration(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
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

    private fun hasNotificationAccess() = CurveonNotificationListener.isEnabled(this)

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
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.CALL_PHONE)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
