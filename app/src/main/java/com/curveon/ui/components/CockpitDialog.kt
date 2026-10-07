package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.curveon.ui.theme.*

/**
 * A short message for the rider (e.g. "Calling Kari…"). Dialogs show it on top of themselves,
 * since the dashboard's own toast is hidden behind an open dialog.
 */
val LocalToast = compositionLocalOf<String?> { null }

/**
 * The one dialog frame every rail screen uses (Where to?, Roundtrip, GPX, Phone, Settings, Rides):
 * same size, same header, and the same round close button in the top-right corner.
 *
 * [header] replaces the title (e.g. the search field); [actions] sit just left of the close button.
 */
@Composable
fun CockpitDialog(
    title: String,
    icon: ImageVector,
    accent: Color,
    onDismiss: () -> Unit,
    tag: String,
    subtitle: String? = null,
    header: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val toast = LocalToast.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f).clip(DialogShape).background(CockpitSurface)
                .border(1.dp, CockpitBorder, DialogShape).testTag(tag)
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (header != null) header() else {
                        Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                            if (subtitle != null) Text(subtitle, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    actions()
                    Box(Modifier.testTag("${tag}_close")) {
                        RoundButton(Icons.Default.Close, stringResource(R.string.close), 48.dp, CockpitSurfaceVariant, TextPrimary, onDismiss)
                    }
                }
                content()
            }
            toast?.let {
                Text(it, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(RoundedCornerShape(12.dp))
                        .background(CockpitSurfaceVariant).border(1.dp, ElectricCyan, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

private val DialogShape = RoundedCornerShape(24.dp)
