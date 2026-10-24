package com.curveon.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.curveon.R
import com.curveon.data.feedback.Draft
import com.curveon.data.feedback.Feedback
import com.curveon.data.feedback.ReportStatus
import com.curveon.data.feedback.SendResult
import com.curveon.data.feedback.SentReport
import com.curveon.ui.theme.*
import kotlinx.coroutines.launch

private val OnCyan = androidx.compose.ui.graphics.Color(0xFF00363D)

/** Settings → Help: report a bug or suggest something, and follow what was sent before. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackCard() {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var writing by remember { mutableStateOf<Boolean?>(null) } // true: a bug, false: an idea
    var sent by remember { mutableStateOf(Feedback.sent(context)) }
    val status = remember { mutableStateMapOf<Int, ReportStatus>() }
    // Only the latest few, once each time Help opens: GitHub's public API allows 60 an hour.
    LaunchedEffect(sent) { sent.take(5).forEach { r -> Feedback.status(r)?.let { status[r.number] = it } } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CockpitSurfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Feedback, null, tint = ElectricCyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.feedback_title), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.feedback_hint), color = TextSecondary, fontSize = 13.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { writing = true }) {
                Icon(Icons.Default.BugReport, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.feedback_bug), color = ElectricCyan)
            }
            OutlinedButton(onClick = { writing = false }) {
                Icon(Icons.Default.Lightbulb, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.feedback_idea), color = ElectricCyan)
            }
        }
        if (sent.isNotEmpty()) {
            Text(stringResource(R.string.feedback_yours), color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            sent.take(5).forEach { r -> SentRow(r, status[r.number]) { runCatching { uri.openUri(r.url) } } }
        }
    }
    writing?.let { bug ->
        FeedbackDialog(bug, onSent = { sent = Feedback.sent(context) }, onDismiss = { writing = null })
    }
}

@Composable
private fun SentRow(r: SentReport, status: ReportStatus?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (r.bug) Icons.Default.BugReport else Icons.Default.Lightbulb, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("#${r.number} ${r.title}", color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (status != null) {
            val state = when {
                status.open -> stringResource(R.string.feedback_status_open)
                status.done -> stringResource(R.string.feedback_status_done)
                else -> stringResource(R.string.feedback_status_closed)
            }
            val replies = if (status.replies > 0) " · " + pluralStringResource(R.plurals.feedback_replies, status.replies, status.replies) else ""
            Text(state + replies, color = if (status.done) ElectricCyan else TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        Icon(Icons.Default.OpenInNew, null, tint = TextMuted, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

/** Title, description, up to [Feedback.MAX_IMAGES] pictures and (for a bug) the latest crash report, then Send. */
@Composable
private fun FeedbackDialog(bug: Boolean, onSent: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val draft = remember { Feedback.drafts[bug] ?: Draft() }
    var title by rememberSaveable { mutableStateOf(draft.title) }
    var text by rememberSaveable { mutableStateOf(draft.text) }
    var images by rememberSaveable { mutableStateOf(draft.images) }
    val crash = remember { if (bug) Feedback.recentCrash(context) else null }
    var withCrash by rememberSaveable { mutableStateOf(draft.withCrash) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SendResult?>(null) }
    val crashNote = stringResource(R.string.feedback_crash_note)
    // Kept until it is sent, also when the dialog closes (by X or because the screen turned).
    LaunchedEffect(title, text, images, withCrash, result) {
        if (result is SendResult.Sent) Feedback.drafts.remove(bug) else Feedback.drafts[bug] = Draft(title, text, images, withCrash)
    }
    val send = {
        sending = true
        scope.launch {
            result = Feedback.send(context, bug, title, text, images.map(Uri::parse), withCrash)
            sending = false
            if (result is SendResult.Sent) onSent()
        }
        Unit
    }
    // The system photo picker: no storage permission, and only the pictures the rider picks.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Feedback.MAX_IMAGES)) { picked ->
        images = (images + picked.map { it.toString() }).distinct().take(Feedback.MAX_IMAGES)
    }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        focusedBorderColor = ElectricCyan, unfocusedBorderColor = CockpitBorder, cursorColor = ElectricCyan
    )
    // Its own window above Settings, also when Settings sits in the dashboard's panel.
    CompositionLocalProvider(LocalInPanel provides false, LocalInHostDialog provides false) {
        CockpitDialog(
            title = stringResource(if (bug) R.string.feedback_bug_heading else R.string.feedback_idea_heading),
            icon = if (bug) Icons.Default.BugReport else Icons.Default.Lightbulb,
            accent = ElectricCyan, onDismiss = onDismiss, tag = "feedback_dialog",
            // Send in the header, next to close: always in sight, also above the keyboard.
            actions = {
                if (result !is SendResult.Sent) Button(
                    onClick = send, enabled = title.isNotBlank() && !sending,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan), modifier = Modifier.testTag("feedback_send")
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), color = OnCyan, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Send, null, tint = OnCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (sending) R.string.feedback_sending else R.string.feedback_send), color = OnCyan, fontWeight = FontWeight.Bold)
                }
            }
        ) {
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val done = result as? SendResult.Sent
                if (done != null) {
                    Text(stringResource(R.string.feedback_sent, done.report.number), color = TextPrimary, fontSize = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { runCatching { uri.openUri(done.report.url) } }, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                            Text(stringResource(R.string.feedback_follow), color = OnCyan, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = ElectricCyan) }
                    }
                    return@Column
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(120) }, singleLine = true, colors = colors,
                    label = { Text(stringResource(R.string.feedback_title_field)) },
                    placeholder = { Text(stringResource(if (bug) R.string.feedback_bug_title_hint else R.string.feedback_idea_title_hint), color = TextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(10_000) }, minLines = 4, colors = colors,
                    label = { Text(stringResource(R.string.feedback_text_field)) },
                    placeholder = { Text(stringResource(if (bug) R.string.feedback_bug_body else R.string.feedback_idea_body), color = TextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    images.forEach { u ->
                        Box(Modifier.size(72.dp)) {
                            AsyncImage(Uri.parse(u), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(3.dp).size(24.dp).clip(CircleShape).background(CockpitSurface)
                                    .clickable { images = images - u },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.Close, stringResource(R.string.feedback_remove_image), tint = TextPrimary, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = images.size < Feedback.MAX_IMAGES
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.feedback_add_images), color = ElectricCyan)
                    }
                    if (images.isNotEmpty()) Text(
                        stringResource(R.string.feedback_images_count, images.size, Feedback.MAX_IMAGES),
                        color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp)
                    )
                }
                if (crash != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withCrash, { withCrash = it }, colors = CheckboxDefaults.colors(checkedColor = ElectricCyan))
                    Text(stringResource(R.string.feedback_include_crash), color = TextPrimary, fontSize = 15.sp)
                }
                Text(stringResource(R.string.feedback_public), color = TextMuted, fontSize = 13.sp)
                when (result) {
                    SendResult.TooMany -> Text(stringResource(R.string.feedback_too_many), color = WarningRed, fontSize = 14.sp)
                    SendResult.Failed -> Text(stringResource(R.string.feedback_failed), color = WarningRed, fontSize = 14.sp)
                    else -> Unit
                }
                // The relay couldn't take it: GitHub's own form, filled in (needs an account there).
                if (result == SendResult.Failed) OutlinedButton(onClick = {
                    runCatching { uri.openUri(Feedback.githubUrl(bug, title, text, crash?.takeIf { withCrash }, crashNote)) }
                }) { Text(stringResource(R.string.feedback_open_github), color = ElectricCyan) }
            }
        }
    }
}
