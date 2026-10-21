package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.bluetooth.MediaApp
import com.curveon.data.bluetooth.PhoneCompanionManager
import com.curveon.ui.theme.*

/**
 * Settings → Phone: how the linked phone is doing (with Forget), its music apps to mark as
 * favorites, the other paired phones to switch to, this device as its own phone, and demo data.
 */
@Composable
fun PhoneLinkContent(manager: PhoneCompanionManager, modifier: Modifier = Modifier) {
    val state by manager.connectionState.collectAsStateWithLifecycle()
    val name by manager.connectedDeviceName.collectAsStateWithLifecycle()
    val installed by manager.installedApps.collectAsStateWithLifecycle()
    val favorites by manager.favoriteApps.collectAsStateWithLifecycle()
    val sessions by manager.mediaApps.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val phones = remember(refresh, state) { manager.pairedPhones() }
    val selected = remember(refresh, state) { manager.selectedPhoneAddress() }
    var confirmForget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        manager.restart(); refresh++
    }
    val demo = state == CompanionConnectionState.DEMO
    val thisDevice by manager.thisDevice.collectAsStateWithLifecycle()
    // Back from Android's settings, where access may have been granted.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { manager.refreshThisDevice() } }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (demo || selected != null || thisDevice) LinkedPhoneCard(manager, onForget = { if (thisDevice) { manager.forgetPhone(); refresh++ } else confirmForget = true })
        else Text(
            stringResource(if (state == CompanionConnectionState.NO_PERMISSION) R.string.link_no_permission else R.string.link_pick),
            color = TextSecondary, fontSize = 14.sp
        )

        if (state == CompanionConnectionState.NO_PERMISSION && Build.VERSION.SDK_INT >= 31) {
            Button(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                Text(stringResource(R.string.link_allow_bluetooth), color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
            }
        }

        if (demo || selected != null || thisDevice) {
            LinkSectionTitle(stringResource(R.string.link_music_apps))
            if (installed.isEmpty()) Text(stringResource(R.string.link_music_apps_none), color = TextMuted, fontSize = 13.sp)
            else {
                Text(stringResource(R.string.link_music_apps_hint), color = TextMuted, fontSize = 13.sp)
                installed.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { app ->
                            val running = sessions.firstOrNull { it.packageName == app.packageName && it.running }
                            MusicAppRow(app, running?.playing, app.packageName in favorites, Modifier.weight(1f)) {
                                manager.setFavoriteApp(app.packageName, it)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (!demo && !thisDevice) PhoneRow(stringResource(R.string.link_this_device), stringResource(R.string.link_this_device_hint)) {
            manager.useThisDevice(); refresh++
        }
        val others = phones.filter { it.address != selected }
        if (!demo && others.isNotEmpty()) {
            LinkSectionTitle(stringResource(if (selected == null && !thisDevice) R.string.link_paired_phones else R.string.link_other_phones))
            others.forEach { p -> PhoneRow(p.name, p.address) { manager.selectPhone(p); refresh++ } }
        }
        if (!demo) TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text(stringResource(R.string.link_bluetooth_settings), color = ElectricCyan) }

        if (com.curveon.BuildConfig.DEV || demo) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.link_demo_data), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.link_demo_data_hint), color = TextMuted, fontSize = 12.sp)
            }
            CockpitSwitch(checked = demo, onCheckedChange = { manager.setDemo(it); refresh++ }, accent = NeonAmber)
        }
    }

    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { HideSystemBars(); Text(stringResource(R.string.link_forget_title, name)) },
        text = { Text(stringResource(R.string.link_forget_text)) },
        confirmButton = {
            TextButton(onClick = { manager.forgetPhone(); confirmForget = false; refresh++ }) {
                Text(stringResource(R.string.link_forget), color = WarningRed)
            }
        },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

/** A phone to switch to, with "Use". */
@Composable
private fun PhoneRow(name: String, detail: String, onUse: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant)
            .clickable(onClick = onUse).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Smartphone, null, tint = TextSecondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = TextMuted, fontSize = 11.sp)
        }
        Text(stringResource(R.string.link_use), color = ElectricCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** The phone Curveon links to: connection, model, battery, what it shares, and Forget. */
@Composable
private fun LinkedPhoneCard(manager: PhoneCompanionManager, onForget: () -> Unit) {
    val state by manager.connectionState.collectAsStateWithLifecycle()
    val name by manager.connectedDeviceName.collectAsStateWithLifecycle()
    val status by manager.phoneStatus.collectAsStateWithLifecycle()
    val since by manager.connectedSince.collectAsStateWithLifecycle()
    val contactsAllowed by manager.contactsAllowed.collectAsStateWithLifecycle()
    val callLogAllowed by manager.callLogAllowed.collectAsStateWithLifecycle()
    val thisDevice by manager.thisDevice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { manager.refreshThisDevice() }
    val connected = state == CompanionConnectionState.CONNECTED || state == CompanionConnectionState.DEMO
    val (label, color) = when (state) {
        CompanionConnectionState.CONNECTED -> stringResource(R.string.link_status_connected) to EcoGreen
        CompanionConnectionState.DEMO -> stringResource(R.string.link_demo_data) to NeonAmber
        CompanionConnectionState.CONNECTING -> stringResource(R.string.link_status_connecting) to NeonAmber
        else -> stringResource(R.string.link_status_offline) to WarningRed
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Smartphone, null, tint = ElectricCyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(if (thisDevice) stringResource(R.string.link_this_device) else name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state == CompanionConnectionState.DISCONNECTED || state == CompanionConnectionState.UNREACHABLE) {
                OutlinedButton(onClick = manager::retryNow) { Text(stringResource(R.string.link_connect_now), color = ElectricCyan) }
                Spacer(Modifier.width(8.dp))
            }
            if (state != CompanionConnectionState.DEMO) TextButton(onClick = onForget) {
                Text(stringResource(if (thisDevice) R.string.link_stop_this_device else R.string.link_forget), color = TextMuted)
            }
        }

        val s = status
        if (connected && s != null) {
            Text(
                listOfNotNull(
                    s.model.takeIf { it.isNotEmpty() && it != name },
                    s.batteryPercent.takeIf { it >= 0 }?.let {
                        stringResource(if (s.charging) R.string.link_battery_charging else R.string.link_battery, it)
                    },
                    since?.let { stringResource(R.string.link_since, DateFormat.getTimeFormat(context).format(java.util.Date(it))) }
                ).joinToString(" · "),
                color = TextSecondary, fontSize = 14.sp
            )
            if (!s.notificationAccess) {
                if (thisDevice) MissingAccess(stringResource(R.string.link_this_no_notification_access)) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } else Text(stringResource(R.string.link_no_notification_access), color = NeonAmber, fontSize = 13.sp)
            }
        }
        val voiceAllowed = !thisDevice || THIS_DEVICE_PERMISSIONS.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (connected && (!contactsAllowed || !callLogAllowed || !voiceAllowed)) {
            if (thisDevice) MissingAccess(stringResource(R.string.link_this_no_contacts)) { permissions.launch(THIS_DEVICE_PERMISSIONS) }
            else Text(stringResource(R.string.link_no_contacts), color = NeonAmber, fontSize = 13.sp)
        }
        when (state) {
            CompanionConnectionState.DISCONNECTED -> stringResource(R.string.link_disconnected, name)
            CompanionConnectionState.UNREACHABLE -> stringResource(R.string.link_unreachable, name)
            CompanionConnectionState.BLUETOOTH_OFF -> stringResource(R.string.bluetooth_off)
            CompanionConnectionState.NO_PERMISSION -> stringResource(R.string.link_no_permission)
            else -> null
        }?.let { Text(it, color = TextSecondary, fontSize = 13.sp) }
    }
}

/** What "This device" needs to read contacts and calls, dial, send spoken text messages, and hear spoken replies. */
private val THIS_DEVICE_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
    Manifest.permission.RECORD_AUDIO
)

/** Something this device isn't allowed yet, with a button to allow it. */
@Composable
private fun MissingAccess(text: String, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = NeonAmber, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.link_allow), color = ElectricCyan) }
    }
}

/** A music app on the phone; tap to make it a favorite (always in the music card). */
@Composable
private fun MusicAppRow(app: MediaApp, playing: Boolean?, favorite: Boolean, modifier: Modifier, onFavorite: (Boolean) -> Unit) {
    Row(
        modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant)
            .clickable { onFavorite(!favorite) }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xCC1A1F26)), contentAlignment = Alignment.Center) {
            if (app.icon != null) Image(app.icon.asImageBitmap(), null, modifier = Modifier.size(28.dp))
            else Text(app.name.take(1), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (playing != null) Text(stringResource(if (playing) R.string.link_app_playing else R.string.link_app_running), color = TextMuted, fontSize = 12.sp)
        }
        Icon(
            if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
            stringResource(R.string.link_favorite), tint = if (favorite) NeonAmber else TextMuted
        )
    }
}

@Composable
private fun LinkSectionTitle(text: String) {
    Text(text.uppercase(), color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}
