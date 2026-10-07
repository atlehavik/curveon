package com.curveon.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.data.gpx.Mix
import com.curveon.data.model.GpxGroup
import com.curveon.ui.theme.*

/** Eye for shown / hidden, split down the middle when a group is partly shown. */
@Composable
fun VisibilityIcon(mix: Mix, color: Color, size: Dp = 26.dp) {
    when (mix) {
        Mix.ALL -> Icon(Icons.Default.Visibility, "Shown on map", tint = color, modifier = Modifier.size(size))
        Mix.NONE -> Icon(Icons.Default.VisibilityOff, "Hidden", tint = TextMuted, modifier = Modifier.size(size))
        Mix.MIXED -> Box(Modifier.size(size)) {
            Icon(
                Icons.Default.Visibility, "Partly shown", tint = color,
                modifier = Modifier.fillMaxSize().drawWithContent { clipRect(right = size.toPx() / 2) { this@drawWithContent.drawContent() } }
            )
            Icon(
                Icons.Default.VisibilityOff, null, tint = TextMuted,
                modifier = Modifier.fillMaxSize().drawWithContent { clipRect(left = size.toPx() / 2) { this@drawWithContent.drawContent() } }
            )
        }
    }
}

/** One colour as a dot, or several as a ring of segments (a group whose tracks keep their own colours). */
@Composable
fun ColorSwatch(colors: List<Int>, size: Dp = 22.dp) {
    val distinct = colors.distinct().take(8)
    if (distinct.size <= 1) {
        Box(Modifier.size(size).clip(CircleShape).background(Color(distinct.firstOrNull() ?: 0xFFFFFFFF.toInt())).border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape))
    } else Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.28f
        val sweep = 360f / distinct.size
        distinct.forEachIndexed { i, c ->
            drawArc(
                Color(c), startAngle = -90f + i * sweep + 4f, sweepAngle = sweep - 8f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(stroke)
            )
        }
    }
}

/** Direction a track is ridden: as recorded (→), inverted (← in amber), or mixed in a group (⇄). */
@Composable
fun DirectionIcon(mix: Mix) {
    when (mix) {
        Mix.NONE -> Icon(Icons.Default.TrendingFlat, "As recorded", tint = TextMuted)
        Mix.ALL -> Icon(Icons.Default.TrendingFlat, "Inverted", tint = NeonAmber, modifier = Modifier.rotate(180f))
        Mix.MIXED -> Icon(Icons.Default.SwapHoriz, "Mixed directions", tint = NeonAmber)
    }
}

/** Asks for a new name. */
@Composable
fun RenameDialog(title: String, initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = { onRename(text.text); onDismiss() }, enabled = text.text.isNotBlank()) { Text("Save", color = ElectricCyan) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Pick a group for a track (moving it, or saving a ride): an existing group, no group, or a new
 * one. [onPick] gets (groupId, null) or (null, newName).
 */
@Composable
fun GroupPickerDialog(
    title: String,
    groups: List<GpxGroup>,
    current: Long?,
    onPick: (Long?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { PickRow(Icons.Default.Remove, "No group", current == null) { onPick(null, null); onDismiss() } }
                    items(groups, key = { it.id }) { g ->
                        PickRow(Icons.Default.FolderOpen, g.name, current == g.id) { onPick(g.id, null); onDismiss() }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        placeholder = { Text("New group") }, colors = fieldColors(), modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onPick(null, newName.trim()); onDismiss() }, enabled = newName.isNotBlank()) {
                        Text("Create", color = if (newName.isNotBlank()) ElectricCyan else TextMuted)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) ElectricCyan else TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    focusedBorderColor = ElectricCyan, unfocusedBorderColor = CockpitBorder, cursorColor = ElectricCyan
)
