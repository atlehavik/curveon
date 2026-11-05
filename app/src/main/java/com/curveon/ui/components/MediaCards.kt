package com.curveon.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.bluetooth.MediaApp
import androidx.compose.ui.res.stringResource
import com.curveon.R
import com.curveon.data.model.MusicTrack
import com.curveon.ui.theme.*

private val OnCyan = Color(0xFF00363D)

/**
 * Cover art fills the card, like Android Auto's media card when nothing else needs the space.
 * With more than one media app on the phone, flip the card sideways to switch between them;
 * small dots at the top show there are more.
 */
@Composable
fun FullMediaCard(
    track: MusicTrack?,
    art: Bitmap?,
    phoneState: CompanionConnectionState,
    phoneName: String,
    apps: List<MediaApp>,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPlayApp: (String) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Why no music can come (notification access off on the phone), and what tapping does about it (null: nothing to do here). */
    blocked: String? = null,
    onFixBlocked: (() -> Unit)? = null
) {
    val current = apps.indexOfFirst { it.name == track?.sourceApp }
    val switchable = track != null && apps.size > 1
    val switchTo: (Int) -> Unit = { step -> onPlayApp(apps[Math.floorMod(current + step, apps.size)].packageName) }
    DashCard(
        modifier.fillMaxWidth()
            .then(if (switchable) Modifier.flipToSwitch(onNext = { switchTo(1) }, onPrevious = { switchTo(-1) }) else Modifier)
            .testTag("media_card_full")
    ) {
        if (track == null) {
            val connected = phoneState == CompanionConnectionState.CONNECTED
            val canRetry = phoneState == CompanionConnectionState.DISCONNECTED || phoneState == CompanionConnectionState.UNREACHABLE ||
                phoneState == CompanionConnectionState.CONNECTING
            // Connected but silent: pick an app, or tap to make the phone resume whatever played last.
            // Not connected: tap to try right now (e.g. sharing was just turned on on the phone).
            val stuck = connected && blocked != null
            Box(Modifier.matchParentSize().clickable(enabled = if (stuck) onFixBlocked != null else (connected && apps.isEmpty()) || canRetry,
                onClick = if (stuck) ({ onFixBlocked?.invoke(); Unit }) else if (connected) onPlayPause else onReconnect)) {
                NothingPlaying(phoneState, phoneName, choosing = connected && apps.isNotEmpty() && !stuck, blocked = if (connected) blocked else null)
                if (connected && apps.isNotEmpty()) {
                    Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        apps.take(4).forEach { app ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                                MediaAppIcon(app, 56.dp, current = false) { onPlayApp(app.packageName) }
                                Text(app.name, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            return@DashCard
        }
        if (art != null) {
            Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(CockpitSurfaceVariant, CockpitBackground))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(96.dp))
            }
        }
        // Darken the lower half so text and buttons stay readable on any cover.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color(0xF2000000))))
        if (switchable) {
            Row(Modifier.align(Alignment.TopCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                apps.indices.forEach { i ->
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == current) ElectricCyan else Color(0x88FFFFFF)))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)) {
            Text(track.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artist, track.sourceApp).filter { it.isNotEmpty() }.joinToString(" · "),
                color = Color(0xFFD0D7E0), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (track.durationSeconds > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { track.progressFraction },
                    color = ElectricCyan, trackColor = Color(0x55FFFFFF),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Default.SkipPrevious, stringResource(R.string.media_previous), 56.dp, Color(0x33FFFFFF), Color.White, onPrevious)
                RoundButton(if (track.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.media_play_pause), 72.dp, ElectricCyan, OnCyan, onPlayPause)
                RoundButton(Icons.Default.SkipNext, stringResource(R.string.media_next), 56.dp, Color(0x33FFFFFF), Color.White, onNext)
            }
        }
    }
}

/** One-row player used when a notification or reply needs the space. */
@Composable
fun MiniMediaCard(
    track: MusicTrack, art: Bitmap?, onPlayPause: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
    /** Only the cover and the two buttons, for half a row (beside the call). */
    compact: Boolean = false
) {
    val size = if (compact) 44.dp else 52.dp
    val button = if (compact) 44.dp else 48.dp
    DashCard(modifier.fillMaxWidth().testTag("media_card_mini")) {
        // Half a row too narrow for the cover and both buttons (a low phone on its side): the buttons stay, centred.
        BoxWithConstraints(Modifier.fillMaxWidth().align(Alignment.Center)) {
            val cover = !compact || maxWidth >= 154.dp
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (cover) Arrangement.Start else Arrangement.Center) {
                if (cover) Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(CockpitSurfaceVariant)
                    .then(if (onCollapse != null) Modifier.clickable(onClick = onCollapse) else Modifier), contentAlignment = Alignment.Center) {
                    if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    else Icon(Icons.Default.MusicNote, null, tint = TextMuted)
                }
                if (compact) { if (cover) Spacer(Modifier.weight(1f)) } else {
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                RoundButton(if (track.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.media_play_pause), button, ElectricCyan, OnCyan, onPlayPause)
                Spacer(Modifier.width(6.dp))
                RoundButton(Icons.Default.SkipNext, stringResource(R.string.media_next), button, CockpitSurfaceVariant, TextPrimary, onNext)
            }
        }
    }
}

/** The music on the map, folded in: just the cover (a note without one). A tap opens [MiniMediaCard]. */
@Composable
fun MediaPillIcon(track: MusicTrack, art: Bitmap?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.size(MEDIA_ICON_SIZE).clip(shape).background(CockpitSurface.copy(alpha = 0.95f))
            .border(1.dp, if (track.isPlaying) ElectricCyan else CockpitBorder, shape).clickable(onClick = onClick)
            .testTag("media_pill_icon"),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) Image(art.asImageBitmap(), track.title, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Icon(Icons.Default.MusicNote, track.title, tint = TextMuted, modifier = Modifier.size(28.dp))
        // What it is doing now, in the corner.
        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Icon(if (track.isPlaying) Icons.Default.GraphicEq else Icons.Default.Pause, null, tint = if (track.isPlaying) ElectricCyan else Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/** The folded music icon's size; the turn card beside it and the map buttons above it make room for this. */
val MEDIA_ICON_SIZE = 56.dp

/** A media app on the phone; the one being shown gets a cyan ring. */
@Composable
private fun MediaAppIcon(app: MediaApp, size: Dp, current: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xCC1A1F26))
            .border(if (current) 3.dp else 1.dp, if (current) ElectricCyan else Color(0x55FFFFFF), CircleShape)
            .clickable(onClick = onClick).padding(size * 0.14f).testTag("media_app_${app.packageName}"),
        contentAlignment = Alignment.Center
    ) {
        if (app.icon != null) Image(app.icon.asImageBitmap(), app.name, modifier = Modifier.fillMaxSize())
        else Text(app.name.take(1), color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun BoxScope.NothingPlaying(state: CompanionConnectionState, phoneName: String, choosing: Boolean, blocked: String? = null) {
    Column(
        Modifier.align(if (choosing) Alignment.TopCenter else Alignment.Center).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            if (state == CompanionConnectionState.CONNECTED) Icons.Default.MusicOff else Icons.Default.PhonelinkOff,
            null, tint = TextMuted, modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (blocked != null) blocked else when (state) {
                CompanionConnectionState.CONNECTED -> stringResource(if (choosing) R.string.media_nothing_pick else R.string.media_nothing_tap, phoneName)
                CompanionConnectionState.CONNECTING -> stringResource(R.string.phone_connecting_to, phoneName)
                // Curveon on a phone: that phone itself is the obvious choice.
                CompanionConnectionState.NO_PHONE_SELECTED -> stringResource(
                    if (androidx.compose.ui.platform.LocalContext.current.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY))
                        R.string.media_no_phone_this else R.string.media_no_phone
                )
                CompanionConnectionState.NO_PERMISSION -> stringResource(R.string.media_no_permission)
                CompanionConnectionState.BLUETOOTH_OFF -> stringResource(R.string.bluetooth_off)
                CompanionConnectionState.DISCONNECTED -> stringResource(R.string.media_disconnected)
                CompanionConnectionState.UNREACHABLE -> stringResource(R.string.media_unreachable, phoneName)
                CompanionConnectionState.DEMO -> stringResource(R.string.media_nothing)
            },
            color = TextSecondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
