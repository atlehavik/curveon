@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.curveon.ui.components

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.R
import com.curveon.data.groups.GroupManager
import com.curveon.protocol.Message
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val SOS_HOLD_MS = 3_000
internal const val SOS_COUNTDOWN_S = 10

/** Held for three seconds, so a gloved thumb does not set it off by accident. */
@Composable
internal fun SosButton(onTrigger: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(holding) {
        if (!holding) { progress = 0f; return@LaunchedEffect }
        val start = System.currentTimeMillis()
        while (holding) {
            progress = ((System.currentTimeMillis() - start) / SOS_HOLD_MS.toFloat()).coerceAtMost(1f)
            delay(30)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(WarningRed.copy(alpha = 0.18f))
                .border(2.dp, WarningRed, RoundedCornerShape(16.dp)).testTag("group_sos_hold")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        holding = true
                        // Only a thumb that stays put for the whole hold sets it off: lifting, sliding off the
                        // button or the list taking over the gesture (scrolling) all cancel.
                        var completed = true
                        withTimeoutOrNull(SOS_HOLD_MS.toLong()) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: run { completed = false; return@withTimeoutOrNull }
                                if (!change.pressed || change.isConsumed || change.position.x !in 0f..size.width.toFloat() ||
                                    change.position.y !in 0f..size.height.toFloat()
                                ) { completed = false; return@withTimeoutOrNull }
                            }
                        }
                        holding = false
                        if (completed) onTrigger()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress).background(WarningRed.copy(alpha = 0.55f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sos, null, tint = WarningRed, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.sos_hold), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
        Text(stringResource(R.string.sos_hold_hint), color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
internal fun SosCountdown(left: Int, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        HideSystemBars()
        Column(
            Modifier.fillMaxSize().background(Color(0xFF7A0B00)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.sos_countdown_title), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text("$left", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black, modifier = Modifier.testTag("sos_countdown"))
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.White), modifier = Modifier.height(64.dp).widthIn(min = 240.dp).testTag("sos_countdown_cancel")) {
                Text(stringResource(R.string.cancel), color = Color(0xFF7A0B00), fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/**
 * On top of everything: the rider's own SOS with how far it has got (a bar at the top), and a
 * full-screen alert when someone else sends one. [navigateTo] starts a route to the rider in trouble.
 */
@Composable
fun GroupSosOverlay(manager: GroupManager, myLat: Double?, myLon: Double?, navigateTo: (Double, Double, String) -> Unit) {
    val sos by manager.sos.collectAsStateWithLifecycle()
    val members by manager.members.collectAsStateWithLifecycle()
    val myId by manager.myId.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(setOf<Long>()) }

    Box(Modifier.fillMaxSize()) {
    sos.values.firstOrNull { it.mine }?.let { mine -> MySosCard(mine, manager, Modifier.align(Alignment.TopCenter).padding(12.dp)) }

    val incoming = sos.values.firstOrNull { !it.mine && it.timeMs !in dismissed }
    incoming?.let { s ->
        val who = members[s.from]?.nickname ?: stringResource(R.string.group_unnamed)
        val onTheWay = s.acks.count { it.coming }
        // It may be nothing, and not everyone has to go: ignoring it is as easy as answering.
        val ignore = { dismissed = dismissed + s.timeMs }
        SosAlertSound()
        // Beats like the siren: the red swells and fades, the edge flashes and the icon pounds, until the rider answers.
        val beat by rememberInfiniteTransition(label = "sosBeat").animateFloat(0f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "sosBeatValue")
        Dialog(onDismissRequest = ignore, properties = DialogProperties(dismissOnClickOutside = false)) {
            HideSystemBars()
            Column(
                Modifier.widthIn(max = 380.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .drawBehind { drawRect(lerp(Color(0xFF8E0000), Color(0xFFE53935), beat)) }
                    .border(4.dp, Color.White.copy(alpha = 0.2f + 0.8f * beat), RoundedCornerShape(20.dp))
                    .padding(18.dp).testTag("sos_alert"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Sos, null, tint = Color.White,
                        modifier = Modifier.size(40.dp).graphicsLayer { scaleX = 1f + 0.3f * beat; scaleY = 1f + 0.3f * beat })
                    Column {
                        Text(stringResource(R.string.sos_alert_title, who), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        if (myLat != null && myLon != null) {
                            val m = com.curveon.routing.Geo.distance(myLat, myLon, s.lat, s.lon).toInt()
                            Text(
                                if (m < 1000) stringResource(R.string.sos_away_m, m) else stringResource(R.string.sos_away_km, m / 1000.0),
                                color = Color.White, fontSize = 15.sp
                            )
                        }
                    }
                }
                Text(
                    if (onTheWay > 0) stringResource(R.string.sos_on_the_way, onTheWay) else stringResource(R.string.sos_nobody_yet),
                    color = if (onTheWay > 0) Color(0xFFB9F6CA) else Color(0xFFFFCDD2), fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { manager.sosAnswer(s.from, true); navigateTo(s.lat, s.lon, who); ignore() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White), modifier = Modifier.weight(1f).height(52.dp).testTag("sos_drive")
                    ) { Text(stringResource(R.string.sos_drive_there), color = Color(0xFFB71C1C), fontSize = 16.sp, fontWeight = FontWeight.Black) }
                    TextButton(onClick = ignore, modifier = Modifier.height(52.dp).testTag("sos_ignore")) {
                        Text(stringResource(R.string.sos_ignore), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    }
}

/** The rider's own SOS: how far it has got, and a way to end it. Shown on the dashboard and, as the dialog covers that, in the dialog too. */
@Composable
internal fun MySosCard(sos: Message.GroupSos, manager: GroupManager, modifier: Modifier = Modifier) {
    val seen = sos.acks.size
    val coming = sos.acks.count { it.coming }
    val others = (sos.memberCount - 1).coerceAtLeast(0)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF7A0B00))
            .border(2.dp, WarningRed, RoundedCornerShape(16.dp)).padding(14.dp).testTag("sos_sent"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.sos_sent), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(stringResource(R.string.sos_delivery, seen, others), color = Color.White, fontSize = 15.sp)
        if (coming > 0) Text(stringResource(R.string.sos_on_the_way, coming), color = Color(0xFFB9F6CA), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        else if (seen == 0) Text(stringResource(R.string.sos_no_delivery), color = Color(0xFFFFD180), fontSize = 14.sp)
        Button(onClick = { manager.sosCancel() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
            Text(stringResource(R.string.sos_cancel), color = Color(0xFF7A0B00), fontWeight = FontWeight.Black)
        }
    }
}

/** The SOS siren and vibration for as long as the alert is up. */
@Composable
private fun SosAlertSound() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val siren = SosSiren().also { it.start() }
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)) }
        onDispose {
            siren.stop()
            runCatching { vibrator?.cancel() }
        }
    }
}

/** Where Android last knew this device was (any source), for an SOS sent before Curveon's own GPS has a fix. */
@android.annotation.SuppressLint("MissingPermission") // no permission: no position, and the rider is told
internal fun lastKnownPosition(context: Context): Pair<Double, Double>? = runCatching {
    val lm = context.getSystemService(android.location.LocationManager::class.java)
    lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
}.getOrNull()

internal fun hasInternet(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(false)
