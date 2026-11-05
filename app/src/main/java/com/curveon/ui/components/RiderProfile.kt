@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.curveon.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.groups.GroupManager
import com.curveon.data.groups.GroupRider
import com.curveon.protocol.Message
import com.curveon.ui.theme.*
import kotlinx.coroutines.delay

/** The marker's picture: the rider's photo, their icon or their initials, on their colour. */
@Composable
fun RiderAvatar(r: GroupRider, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(r.colour)).border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val photo = r.photo
        val icon = r.icon?.let { GroupIcons[it] }
        when {
            photo != null -> Image(photo.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            icon != null -> Icon(icon, null, tint = Color(0xFF0B0E14), modifier = Modifier.size(size * 0.6f))
            else -> Text(r.nickname.take(2).uppercase(), color = Color(0xFF0B0E14), fontWeight = FontWeight.Black, fontSize = (size.value * 0.4f).sp)
        }
    }
}

@Composable
internal fun RiderRow(
    r: GroupRider, me: Boolean, pos: Message.GroupPositionInfo?, onRemove: (() -> Unit)?, nudge: Pair<GroupManager, String>? = null,
    befriend: GroupManager? = null
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RiderAvatar(r, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (me) stringResource(R.string.group_rider_you, r.nickname) else r.nickname, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (!me) Text(riderStatus(pos), color = TextSecondary, fontSize = 13.sp)
                nudge?.let { (manager, groupId) -> NudgeRowStatus(manager, groupId, r.id, r.nickname) }
            }
            befriend?.let { FriendRequestIconButton(it, r.id) }
            nudge?.let { (manager, groupId) -> NudgeIconButton(manager, groupId, r.id) }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.PersonRemove, stringResource(R.string.group_remove), tint = TextSecondary) }
        }
        // On a line of its own, the full width of the row: beside the name it is a narrow column that breaks words at large text.
        befriend?.let { FriendRequestRowStatus(it, r.id, r.nickname) }
    }
}

/** "now", "3 min ago" or "stopped 12 min", the way the sender reports it. */
@Composable
private fun riderStatus(p: Message.GroupPositionInfo?): String {
    p ?: return stringResource(R.string.group_no_position)
    if (p.paused) return stringResource(R.string.group_rider_paused)
    val now = System.currentTimeMillis()
    if (p.stopped && p.stoppedSince != null) return stringResource(R.string.group_stopped_for, ((now - p.stoppedSince!!) / 60_000).coerceAtLeast(1).toInt())
    val min = ((now - p.timeMs) / 60_000).toInt()
    return if (min < 1) stringResource(R.string.group_seen_now) else stringResource(R.string.group_seen_min, min)
}

/**
 * Nickname, colour, icon and the rider's own picture. The picture is taken with the camera or chosen from the
 * pictures, cut in a round window, and shown as the others will see it. Every change is saved by itself (the name once
 * the typing stops) and told to the phone, which tells the group; "Saved" says it went.
 */
@Composable
internal fun ProfileEditor(me: GroupRider, alwaysOpen: Boolean = false, onSave: (String, Int, String?, String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // The rider's own picture (base64 JPEG): kept as it is unless a new one is cut or it is removed.
    var photo by rememberSaveable { mutableStateOf(me.photoBase64) }
    val preview = remember(photo) { photo?.let { runCatching { val b = android.util.Base64.decode(it, android.util.Base64.DEFAULT); android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() } }
    // The photo just taken or chosen, waiting to be cut.
    var toCut by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val captureFile = remember { java.io.File(context.cacheDir, "photos/profile.jpg") }
    fun toast(res: Int) = android.widget.Toast.makeText(context, context.getString(res), android.widget.Toast.LENGTH_LONG).show()
    fun cutFrom(uri: android.net.Uri) {
        val bitmap = com.curveon.phone.ProfilePhoto.load(context, uri)
        runCatching { captureFile.delete() } // a picture of the rider's face is not left in the cache
        if (bitmap != null) toCut = bitmap else toast(R.string.group_photo_failed)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) cutFrom(uri) }
    // The picture is taken in Curveon's own camera (front lens, mirror view, saved as shown): the phone's camera app saves a
    // selfie mirrored or not depending on its own setting, so what came back could be the other way round from what the rider saw.
    var cameraOpen by remember { mutableStateOf(false) }
    fun takePhoto() { cameraOpen = true }
    if (cameraOpen) com.curveon.qrscan.SelfieCameraDialog(
        file = captureFile.also { it.parentFile?.mkdirs() },
        onPicture = { cameraOpen = false; cutFrom(android.net.Uri.fromFile(captureFile)) },
        onDismiss = { cameraOpen = false }
    )

    // Taken from the profile once: what comes back after a save must not undo letters typed meanwhile.
    var nick by rememberSaveable { mutableStateOf(me.nickname) }
    var colour by rememberSaveable { mutableStateOf(me.colour) }
    var icon by rememberSaveable { mutableStateOf(me.icon) }
    var saved by remember { mutableStateOf(listOf<Any?>(me.nickname, me.colour, me.icon, me.photoBase64)) }
    var savedCount by remember { mutableIntStateOf(0) }
    fun saveNow() {
        val name = nick.trim().ifEmpty { return }
        val now = listOf<Any?>(name, colour, icon, photo)
        if (now == saved) return
        onSave(name, colour, icon, photo)
        saved = now
        savedCount++
    }
    LaunchedEffect(nick, colour, icon, photo) {
        if (nick.trim() != saved[0]) delay(900) // the name: once the typing stops
        saveNow()
    }
    DisposableEffect(Unit) { onDispose { saveNow() } } // closed while still typing
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(savedCount) { if (savedCount > 0) { showSaved = true; delay(2_500); showSaved = false } }
    if (!alwaysOpen) TextButton(onClick = { open = !open }) {
        Icon(Icons.Default.Edit, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.group_profile), color = ElectricCyan, fontWeight = FontWeight.Bold)
    }
    if (open || alwaysOpen) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(nick, { nick = it.take(20) }, singleLine = true, label = { Text(stringResource(R.string.group_nickname)) }, modifier = Modifier.fillMaxWidth())
        // How the others see you: this very marker, as it will be on their map and in their list.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RiderAvatar(me.copy(nickname = nick.ifEmpty { me.nickname }, colour = colour, icon = icon, photo = preview, photoBase64 = photo), 96.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(nick.ifEmpty { me.nickname }, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.group_photo_preview), color = TextSecondary, fontSize = 13.sp)
                if (showSaved) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("profile_saved")) {
                    Icon(Icons.Default.Check, null, tint = EcoGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.profile_saved), color = EcoGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { takePhoto() }, modifier = Modifier.testTag("group_take_photo")) {
                Icon(Icons.Default.PhotoCamera, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.group_photo_take), color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.testTag("group_pick_photo")) {
                Icon(Icons.Default.PhotoLibrary, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.group_photo_gallery), color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
            if (photo != null) OutlinedButton(onClick = { photo = null }, modifier = Modifier.testTag("group_remove_photo")) {
                Icon(Icons.Default.Delete, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.group_photo_remove), color = TextSecondary, fontWeight = FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            GroupColours.forEach { c ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).border(if (c == colour) 3.dp else 1.dp, if (c == colour) Color.White else CockpitBorder, CircleShape).clickable { colour = c })
            }
        }
        // The picture is shown instead of an icon: the icons are for when there is no picture.
        val iconsOn = photo == null
        if (!iconsOn) Text(stringResource(R.string.group_icons_off), color = TextMuted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).graphicsLayer { alpha = if (iconsOn) 1f else 0.35f }) {
            GroupIcons.forEach { (id, vector) ->
                val picked = iconsOn && id == icon
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (picked) Color(colour) else CockpitSurface)
                        .border(1.dp, CockpitBorder, CircleShape).clickable(enabled = iconsOn) { icon = if (icon == id) null else id },
                    contentAlignment = Alignment.Center
                ) { Icon(vector, null, tint = if (picked) Color(0xFF0B0E14) else TextSecondary, modifier = Modifier.size(22.dp)) }
            }
        }
    }
    toCut?.let { bitmap ->
        PhotoCropDialog(
            bitmap,
            colour,
            onDone = { square ->
                val b64 = com.curveon.phone.ProfilePhoto.encode(square)
                if (b64 != null) photo = b64 else toast(R.string.group_photo_failed)
                toCut = null
            },
            onCancel = { toCut = null }
        )
    }
}

/** A friend's round picture, icon or initials, as the riders have. */
@Composable
internal fun FriendAvatar(f: Message.FriendInfo, size: androidx.compose.ui.unit.Dp) {
    val photo = remember(f.photoJpegBase64) {
        f.photoJpegBase64?.let { runCatching { val b = android.util.Base64.decode(it, android.util.Base64.DEFAULT); android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
    }
    RiderAvatar(GroupRider(f.id, f.nickname, f.colour, f.icon, photo, f.photoJpegBase64), size)
}
