package com.curveon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.groups.WebPlanLink
import com.curveon.qrscan.QrScanDialog
import com.curveon.qrscan.hasCamera
import com.curveon.ui.theme.*
import com.curveon.ui.viewmodel.DashboardViewModel.WebPlanState

/**
 * Planned → From the web: what the web planner is, the camera for its code, and once connected, how the last fetch
 * went. Trips come in by themselves after that (app start, opening Planned); Fetch now is for the impatient.
 */
@Composable
fun WebPlanDialog(
    state: WebPlanState,
    /** A scanned code; false when it was not a planner code after all. */
    onLink: (String) -> Boolean,
    onFetch: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    var scanning by remember { mutableStateOf(false) }
    val camera = hasCamera()
    val connected = state.browsers > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("web_plan_dialog"),
        title = { HideSystemBars(); Text(stringResource(R.string.web_plan_title)) },
        text = {
            // Scrolls on a low screen (landscape, large text): the buttons stay below in the dialog.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(if (connected) R.string.web_plan_connected else R.string.web_plan_intro), color = TextSecondary, fontSize = 15.sp)
                if (connected) Text(status(state), color = if (state.offline) NeonAmber else TextPrimary, fontSize = 15.sp)
                if (!camera) Text(stringResource(R.string.web_plan_no_camera), color = NeonAmber, fontSize = 14.sp)
                if (connected) {
                    if (camera) TextButton(onClick = { scanning = true }, modifier = Modifier.testTag("web_plan_scan_another")) {
                        Text(stringResource(R.string.web_plan_scan_another))
                    }
                    TextButton(onClick = onForget, modifier = Modifier.testTag("web_plan_forget")) {
                        Text(stringResource(R.string.web_plan_forget), color = WarningRed)
                    }
                }
            }
        },
        confirmButton = {
            if (connected) TextButton(onClick = onFetch, enabled = !state.fetching, modifier = Modifier.testTag("web_plan_fetch")) {
                Text(stringResource(R.string.web_plan_fetch))
            } else if (camera) TextButton(onClick = { scanning = true }, modifier = Modifier.testTag("web_plan_scan")) {
                Text(stringResource(R.string.web_plan_scan))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )

    if (scanning) QrScanDialog(
        onInvite = { scanning = false; onLink(it) },
        onDismiss = { scanning = false },
        accept = WebPlanLink::isLink,
        hint = stringResource(R.string.web_plan_scan_hint),
        wrong = stringResource(R.string.web_plan_scan_wrong),
    )
}

@Composable
private fun status(s: WebPlanState): String = when {
    s.fetching -> stringResource(R.string.web_plan_fetching)
    s.offline -> stringResource(R.string.web_plan_offline)
    s.added == null -> ""
    s.added == 0 && s.changed == 0 -> stringResource(R.string.web_plan_nothing_new)
    else -> listOfNotNull(
        s.added.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.web_plan_new, it, it) },
        s.changed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.web_plan_changed, it, it) },
    ).joinToString(" · ")
}
