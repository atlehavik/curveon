package com.curveon.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.update.ChangelogEntry
import com.curveon.ui.theme.*

/** What changed: after an update the versions since the last one, from Settings → Help all of them. */
@Composable
fun WhatsNewDialog(entries: List<ChangelogEntry>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            HideSystemBars()
            Text(entries.firstOrNull()?.let { stringResource(R.string.whats_new_title, it.version) } ?: stringResource(R.string.whats_new))
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                entries.forEachIndexed { i, entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (i > 0 || entries.size > 1) Text(entry.version, color = ElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        entry.changes.forEach { line ->
                            Row {
                                Text("•", color = ElectricCyan, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
                                Text(line, color = TextPrimary, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
