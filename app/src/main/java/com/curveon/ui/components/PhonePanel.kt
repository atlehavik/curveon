package com.curveon.ui.components

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.protocol.Message
import com.curveon.ui.theme.*
import kotlinx.coroutines.launch
import java.text.Normalizer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.curveon.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PhoneTab(val label: Int, val icon: ImageVector) {
    FAVORITES(R.string.phone_favorites, Icons.Default.Star),
    RECENTS(R.string.phone_recents, Icons.Default.History),
    CONTACTS(R.string.phone_contacts, Icons.Default.Contacts),
    KEYPAD(R.string.phone_keypad, Icons.Default.Dialpad)
}

/** What the phone panel shows; all of it comes from the phone over the companion link. */
data class PhonePanelState(
    val contacts: List<Message.Contact>?,
    val recents: List<Message.RecentCall>?,
    val photos: Map<String, Bitmap>,
    val contactsAllowed: Boolean,
    val callLogAllowed: Boolean,
    val connected: Boolean,
    /** Shown when nothing has come from the phone yet, e.g. "Phone not connected". */
    val offlineText: String
)

/**
 * Android Auto-style phone app: Favorites (starred contacts), Recents, Contacts and a Keypad.
 * Tapping a person calls them on the phone.
 */
@Composable
fun PhoneDialog(
    state: PhonePanelState,
    tab: PhoneTab,
    onTab: (PhoneTab) -> Unit,
    onCall: (number: String, name: String) -> Unit,
    onNeedPhoto: (String) -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit
) {
    CockpitDialog(
        title = stringResource(R.string.phone), icon = Icons.Default.Phone, accent = EcoGreen, onDismiss = onClose, tag = "phone_panel",
        header = {
            Icon(Icons.Default.Phone, null, tint = EcoGreen, modifier = Modifier.size(28.dp))
            Text(stringResource(R.string.phone), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhoneTab.entries.forEach { t ->
                    TabButton(t, selected = t == tab, Modifier.weight(1f)) { onTab(t) }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(CockpitBackground)) {
            val photo: @Composable (String?) -> Bitmap? = { key ->
                if (key != null) LaunchedEffect(key) { onNeedPhoto(key) }
                key?.let { state.photos[it] }
            }
            when (tab) {
                PhoneTab.FAVORITES -> ContactsGate(state, state.contacts, state.contactsAllowed, stringResource(R.string.phone_no_share_contacts), onReconnect) { all ->
                    val favorites = all.filter { it.starred }
                    if (favorites.isEmpty()) Empty(Icons.Default.StarBorder, stringResource(R.string.phone_no_favorites), stringResource(R.string.phone_no_favorites_hint))
                    else Favorites(favorites, photo, onCall)
                }
                PhoneTab.RECENTS -> ContactsGate(state, state.recents, state.callLogAllowed, stringResource(R.string.phone_no_share_calls), onReconnect) { calls ->
                    if (calls.isEmpty()) Empty(Icons.Default.History, stringResource(R.string.phone_no_recents), "")
                    else Recents(calls, photo, onCall)
                }
                PhoneTab.CONTACTS -> ContactsGate(state, state.contacts, state.contactsAllowed, stringResource(R.string.phone_no_share_contacts), onReconnect) { all ->
                    if (all.isEmpty()) Empty(Icons.Default.Contacts, stringResource(R.string.phone_no_contacts), "")
                    else ContactList(all, photo, onCall)
                }
                PhoneTab.KEYPAD -> Keypad(onCall)
            }
        }
    }
}

@Composable
private fun TabButton(tab: PhoneTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (selected) ElectricCyan else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp).testTag("phone_tab_${tab.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val color = if (selected) Color(0xFF00363D) else TextSecondary
        Icon(tab.icon, stringResource(tab.label), tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(tab.label), color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** Loading / no permission / not connected, or the content once the phone has sent it. */
@Composable
private fun <T> ContactsGate(
    state: PhonePanelState,
    data: List<T>?,
    allowed: Boolean,
    /** Shown when the phone doesn't share it, e.g. "The phone doesn't share contacts". */
    notShared: String,
    onReconnect: () -> Unit,
    content: @Composable (List<T>) -> Unit
) {
    when {
        !allowed -> Empty(Icons.Default.Lock, notShared, stringResource(R.string.phone_allow_hint))
        data != null -> content(data)
        !state.connected -> Box(Modifier.fillMaxSize().clickable(onClick = onReconnect)) {
            Empty(Icons.Default.PhonelinkOff, state.offlineText, stringResource(R.string.tap_to_connect))
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ElectricCyan) }
    }
}

@Composable
private fun Empty(icon: ImageVector, title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(text, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------------------------- favorites

/** Big photo tiles, like Android Auto's favourites: one tap calls. */
@Composable
private fun Favorites(
    favorites: List<Message.Contact>,
    photo: @Composable (String?) -> Bitmap?,
    onCall: (String, String) -> Unit
) {
    var choosing by remember { mutableStateOf<Message.Contact?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(favorites, key = { it.id }) { c ->
            FavoriteTile(c, photo(c.photoKey)) {
                if (c.numbers.size == 1) onCall(c.numbers[0].number, c.name) else choosing = c
            }
        }
    }
    choosing?.let { c -> NumberChooser(c, photo(c.photoKey), onCall = { n -> choosing = null; onCall(n, c.name) }, onDismiss = { choosing = null }) }
}

@Composable
private fun FavoriteTile(c: Message.Contact, photo: Bitmap?, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(tileColor(c.name))
            .clickable(onClick = onClick).testTag("favorite_tile")
    ) {
        if (photo != null) {
            Image(photo.asImageBitmap(), c.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Text(initials(c.name), color = Color.White.copy(alpha = 0.9f), fontSize = 52.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 24.dp))
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xD9000000))))
        Text(
            c.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 20.sp,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
        )
    }
}

// ---------------------------------------------------------------------------------- recents

@Composable
private fun Recents(calls: List<Message.RecentCall>, photo: @Composable (String?) -> Bitmap?, onCall: (String, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
        items(calls) { call ->
            val name = call.name ?: call.number
            PersonRow(name, photo(call.photoKey), onClick = { onCall(call.number, name) }) {
                val (icon, color) = when (call.kind) {
                    Message.CallKind.MISSED -> Icons.AutoMirrored.Filled.CallMissed to WarningRed
                    Message.CallKind.REJECTED -> Icons.Default.PhoneDisabled to TextMuted
                    Message.CallKind.OUTGOING -> Icons.AutoMirrored.Filled.CallMade to EcoGreen
                    Message.CallKind.INCOMING -> Icons.AutoMirrored.Filled.CallReceived to ElectricCyan
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, call.kind.name, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    val label = listOfNotNull(
                        if (call.count > 1) "(${call.count})" else null,
                        call.label.ifEmpty { null } ?: if (call.name == null) null else call.number,
                        whenText(call.timeMs)
                    ).joinToString(" · ")
                    Text(label, color = if (call.kind == Message.CallKind.MISSED) WarningRed else TextSecondary, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun whenText(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        now - ms < 60_000 -> stringResource(R.string.ago_now)
        DateUtils.isToday(ms) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.yesterday)
        now - ms < 6 * DateUtils.DAY_IN_MILLIS -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
    }
}

// ---------------------------------------------------------------------------------- contacts

/**
 * Alphabetical list with letter headers. A letter strip on the right jumps (drag along it with a
 * glove); a contact with several numbers opens them inline.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactList(contacts: List<Message.Contact>, photo: @Composable (String?) -> Bitmap?, onCall: (String, String) -> Unit) {
    val groups = remember(contacts) { contacts.groupBy { letterOf(it.name) } }
    // Index of each letter's header in the list (header + its contacts per group).
    val letterIndex = remember(groups) {
        var i = 0
        groups.mapValues { (_, list) -> i.also { i += 1 + list.size } }
    }
    var expanded by remember(contacts) { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
            groups.forEach { (letter, list) ->
                stickyHeader(key = "h$letter") {
                    Text(letter, color = ElectricCyan, fontSize = 15.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.fillMaxWidth().background(CockpitBackground).padding(horizontal = 20.dp, vertical = 4.dp))
                }
                items(list, key = { it.id }) { c ->
                    Column {
                        PersonRow(c.name, photo(c.photoKey), onClick = {
                            if (c.numbers.size == 1) onCall(c.numbers[0].number, c.name)
                            else expanded = if (expanded == c.id) null else c.id
                        }) {
                            Text(
                                if (c.numbers.size == 1) c.numbers[0].label.ifEmpty { c.numbers[0].number } else pluralStringResource(R.plurals.phone_numbers, c.numbers.size, c.numbers.size),
                                color = TextSecondary, fontSize = 14.sp, maxLines = 1
                            )
                        }
                        if (expanded == c.id) c.numbers.forEach { n ->
                            NumberRow(n, Modifier.padding(start = 84.dp, end = 16.dp, bottom = 6.dp)) { onCall(n.number, c.name) }
                        }
                    }
                }
            }
        }
        LetterStrip(groups.keys.toList()) { letter ->
            letterIndex[letter]?.let { scope.launch { listState.scrollToItem(it) } }
        }
    }
}

@Composable
private fun LetterStrip(letters: List<String>, onLetter: (String) -> Unit) {
    var height by remember { mutableIntStateOf(1) }
    fun pick(y: Float) = letters.getOrNull((y / height * letters.size).toInt().coerceIn(0, letters.lastIndex))?.let(onLetter)
    Column(
        Modifier.width(40.dp).fillMaxHeight().padding(vertical = 8.dp).onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) { detectTapGestures { pick(it.y) } }
            .pointerInput(letters) { detectVerticalDragGestures { change, _ -> pick(change.position.y) } },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Every letter gets an equal share, so a big phone book (A–Å plus #) still fits.
        letters.forEach {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(it, color = TextSecondary, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Groups Æ, Ø, Å as their own letters (Norwegian order), other accents under their base letter. */
private fun letterOf(name: String): String {
    val first = name.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    if (first in "ÆØÅ") return first.toString()
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
    return if (base.isLetter()) base.toString() else "#"
}

@Composable
private fun NumberChooser(c: Message.Contact, photo: Bitmap?, onCall: (String) -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(20.dp))
                .background(CockpitSurface).clickable(enabled = false) {}.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactAvatar(c.name, photo, 52.dp)
                Spacer(Modifier.width(12.dp))
                Text(c.name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            c.numbers.forEach { n -> NumberRow(n) { onCall(n.number) } }
        }
    }
}

@Composable
private fun NumberRow(n: Message.PhoneNumber, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(CockpitSurfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Call, null, tint = EcoGreen, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(n.label, color = TextSecondary, fontSize = 15.sp, modifier = Modifier.width(90.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(n.number, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------- keypad

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(onCall: (String, String) -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
        "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                number, color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("keypad_number")
            )
            if (number.isNotEmpty()) Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .combinedClickable(onClick = { number = number.dropLast(1) }, onLongClick = { number = "" }),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.delete), tint = TextSecondary, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (digit, letters) ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(CockpitSurfaceVariant)
                                .combinedClickable(
                                    onClick = { if (number.length < 20) number += digit },
                                    // Long-press 0 for "+", as on a phone.
                                    onLongClick = if (digit == "0") ({ if (number.length < 20) number += "+" }) else null
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(digit, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            if (letters.isNotEmpty()) Text(letters, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        PillButton(Icons.Default.Call, stringResource(R.string.call), if (number.isEmpty()) CockpitSurfaceVariant else EcoGreen,
            if (number.isEmpty()) TextMuted else Color.Black, Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("keypad_call")) {
            if (number.isNotEmpty()) onCall(number, number)
        }
    }
}

// ---------------------------------------------------------------------------------- shared

@Composable
private fun PersonRow(name: String, photo: Bitmap?, onClick: () -> Unit, subtitle: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(name, photo, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle()
        }
    }
}

/** Contact photo, or initials on a colour picked from the name (like the phone's own dialer). */
@Composable
fun ContactAvatar(name: String, photo: Bitmap?, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tileColor(name)), contentAlignment = Alignment.Center) {
        if (photo != null) Image(photo.asImageBitmap(), name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Text(initials(name), color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Black)
    }
}

private val TileColors = listOf(
    Color(0xFF1E5A7A), Color(0xFF5B3F8C), Color(0xFF2E6B4F), Color(0xFF8C4A2F),
    Color(0xFF7A2E55), Color(0xFF3F5B8C), Color(0xFF6B6B2E), Color(0xFF2E6B6B)
)

private fun tileColor(name: String) = TileColors[Math.floorMod(name.hashCode(), TileColors.size)]

private fun initials(name: String): String {
    val words = name.split(' ').filter { it.isNotBlank() }
    if (words.isEmpty() || !words[0][0].isLetter()) return "#"
    return (words.first().take(1) + if (words.size > 1) words.last().take(1) else "").uppercase()
}
