package com.curveon.ui.components

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.remote.RemoteAction
import com.curveon.data.remote.remoteKeyName
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay

private val RemoteAction.title get() = when (this) {
    RemoteAction.UP -> R.string.remote_up
    RemoteAction.DOWN -> R.string.remote_down
    RemoteAction.LEFT -> R.string.remote_left
    RemoteAction.RIGHT -> R.string.remote_right
    RemoteAction.OK -> R.string.remote_ok
    RemoteAction.BACK -> R.string.remote_back
    RemoteAction.ZOOM_IN -> R.string.map_zoom_in
    RemoteAction.ZOOM_OUT -> R.string.map_zoom_out
    RemoteAction.MENU -> R.string.remote_menu
    RemoteAction.RECENTER -> R.string.map_follow_me
    RemoteAction.WHERE_TO -> R.string.where_to
    RemoteAction.SPEAK_WHERE_TO -> R.string.remote_speak
    RemoteAction.ORIENTATION -> R.string.remote_orientation
    RemoteAction.MAP_OR_DASHBOARD -> R.string.remote_map_or_dashboard
    RemoteAction.NEXT_TRACK -> R.string.remote_next_track
    RemoteAction.PREVIOUS_TRACK -> R.string.remote_previous_track
    RemoteAction.PLAY_PAUSE -> R.string.remote_play_pause
    RemoteAction.CALL -> R.string.remote_call
}

/** A word on what a button does, where its name doesn't say it all. */
private val RemoteAction.explanation get() = when (this) {
    RemoteAction.OK -> R.string.remote_ok_hint
    RemoteAction.BACK -> R.string.remote_back_hint
    else -> null
}

/** Settings → Remote: whether the remote is there, trying its buttons, and giving them other jobs. */
@Composable
internal fun ColumnScope.RemoteSettingsPage(remote: RemoteControl) {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(remote.connectedRemotes()) }
    DisposableEffect(Unit) {
        val input = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { connected = remote.connectedRemotes() }
            override fun onInputDeviceRemoved(id: Int) { connected = remote.connectedRemotes() }
            override fun onInputDeviceChanged(id: Int) { connected = remote.connectedRemotes() }
        }
        input.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { input.unregisterInputDeviceListener(listener) }
    }

    SettingRow(
        if (connected.isEmpty()) stringResource(R.string.remote_none) else stringResource(R.string.remote_connected, connected.joinToString(", ")),
        if (connected.isEmpty()) stringResource(R.string.remote_none_hint) else null
    ) {
        if (connected.isEmpty()) PillButton(Icons.Default.Bluetooth, stringResource(R.string.remote_bluetooth), CockpitSurfaceVariant, TextPrimary) {
            openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
        }
    }
    ScreenKeyboardNote()

    val press = remote.lastPress
    SettingRow(
        stringResource(R.string.remote_try),
        when {
            press == null -> stringResource(R.string.remote_try_hint)
            press.action == null -> stringResource(R.string.remote_try_no_job, remoteKeyName(press.keyCode))
            else -> stringResource(R.string.remote_try_job, remoteKeyName(press.keyCode), stringResource(press.action.title))
        }
    ) {}

    SettingRow(stringResource(R.string.remote_joystick), stringResource(R.string.remote_joystick_hint)) {
        SettingChoice(listOf(false, true), remote.joystickZooms, {
            stringResource(if (it) R.string.remote_joystick_zoom else R.string.remote_joystick_move)
        }, remote::chooseJoystickZooms)
    }

    SettingTitle(stringResource(R.string.remote_buttons))
    Text(stringResource(R.string.remote_buttons_hint), color = TextMuted, fontSize = 13.sp)
    RemoteAction.entries.forEach { action ->
        val keys = remote.bindings.keysFor(action)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant)
                .clickable { remote.capturing = action }
                .padding(horizontal = 14.dp, vertical = 10.dp).testTag("remote_button_${action.name}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(action.title), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                action.explanation?.let { Text(stringResource(it), color = TextMuted, fontSize = 12.sp) }
            }
            Text(if (keys.isEmpty()) "–" else keys.joinToString("  ") { remoteKeyName(it) }, color = ElectricCyan,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.widthIn(max = 170.dp))
        }
    }
    if (remote.bindings.changes.isNotEmpty()) TextButton(onClick = remote::resetButtons) { Text(stringResource(R.string.remote_reset), color = NeonAmber) }

    remote.capturing?.let { action ->
        LaunchedEffect(action) {
            delay(CAPTURE_MS)
            remote.capturing = null
        }
        val keys = remote.bindings.keysFor(action)
        AlertDialog(
            onDismissRequest = { remote.capturing = null },
            containerColor = CockpitSurface,
            icon = { Icon(Icons.Default.SportsEsports, null, tint = ElectricCyan); HideSystemBars() },
            title = { Text(stringResource(R.string.remote_capture, stringResource(action.title)), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.remote_capture_hint), color = TextSecondary) },
            confirmButton = { TextButton(onClick = { remote.capturing = null }) { Text(stringResource(R.string.cancel), color = ElectricCyan) } },
            dismissButton = {
                if (keys.isNotEmpty()) TextButton(onClick = { remote.forget(action); remote.capturing = null }) {
                    Text(stringResource(R.string.remote_capture_remove), color = NeonAmber)
                }
            }
        )
    }
}

private const val CAPTURE_MS = 12_000L

/**
 * A remote that passes for a whole keyboard makes Android hide the keyboard on the screen, and then there is no
 * typing in the search. The rider can't guess why, and only Android's settings can turn that off.
 */
@Composable
fun ScreenKeyboardNote(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!RemoteControl.hidesScreenKeyboard(context)) return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant).border(1.dp, NeonAmber, RoundedCornerShape(12.dp))
            .clickable { openSettings(context, Settings.ACTION_HARD_KEYBOARD_SETTINGS) }
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("remote_keyboard_note"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Keyboard, null, tint = NeonAmber)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.remote_keyboard_note), color = TextPrimary, fontSize = 13.sp)
    }
}

private fun openSettings(context: Context, action: String) {
    val intents = listOf(Intent(action), Intent(Settings.ACTION_SETTINGS))
    intents.firstOrNull { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
}

/** The short messages about the remote, over whatever is showing: the first press, and a button with no job. */
@Composable
fun RemoteHints(remote: RemoteControl) {
    val hint = remote.hint ?: return
    LaunchedEffect(hint) {
        delay(if (hint == RemoteHint.FIRST_PRESS) INTRODUCTION_MS else UNKNOWN_BUTTON_MS)
        remote.hint = null
    }
    // Under the speed and road cards, as the question about resuming a ride.
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 92.dp, start = 12.dp, end = 12.dp)) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 420.dp).clip(RoundedCornerShape(16.dp)).background(CockpitSurface)
                .border(1.dp, ElectricCyan, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 14.dp).testTag("remote_hint"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (hint) {
                RemoteHint.FIRST_PRESS -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SportsEsports, null, tint = ElectricCyan, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.remote_intro_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    IntroLine(stringResource(if (remote.joystickZooms) R.string.remote_intro_arrows_zoom else R.string.remote_intro_arrows))
                    IntroLine(stringResource(R.string.remote_intro_ok))
                    IntroLine(stringResource(R.string.remote_intro_back))
                    IntroLine(stringResource(R.string.remote_intro_hold))
                    Text(stringResource(R.string.remote_intro_more), color = TextMuted, fontSize = 12.sp)
                }
                RemoteHint.UNKNOWN_BUTTON -> Text(stringResource(R.string.remote_unknown_button), color = TextPrimary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun IntroLine(text: String) = Text(text, color = TextSecondary, fontSize = 14.sp)

private const val INTRODUCTION_MS = 12_000L
private const val UNKNOWN_BUTTON_MS = 6_000L
