package com.curveon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curveon.R
import com.curveon.data.obd.FuelAnalysis
import com.curveon.data.obd.FuelCalibration
import com.curveon.data.obd.FuelSignal
import com.curveon.data.obd.ObdState
import com.curveon.ui.theme.*
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Engine → Fuel gauge setup: a checklist that teaches Curveon which value of the bike is
 * the fuel level and builds the tank table (litres for each reading). The levels registered are
 * kept, so it can be done over several refuellings; the signal is found by comparing what the bike
 * said at each level, nothing needs to be known about the bike beforehand.
 */
@Composable
fun FuelSetupDialog(
    calibration: FuelCalibration,
    obd: ObdState,
    capturing: Boolean,
    onTank: (Float?) -> Unit,
    onRegister: (liters: Float, onDone: (Boolean) -> Unit) -> Unit,
    onRemove: (at: Long) -> Unit,
    onUseSource: (String?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var ready by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val candidates = remember(calibration.points) { FuelAnalysis.candidates(calibration.points) }
    val levels = calibration.distinctLevels
    val tank = calibration.tankLiters
    val register: (Float) -> Unit = { l -> failed = false; onRegister(l) { ok -> failed = !ok } }

    CockpitDialog(
        title = stringResource(R.string.fuel_setup_title), icon = Icons.Default.LocalGasStation, accent = NeonAmber,
        onDismiss = onDismiss, tag = "fuel_setup"
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.fuel_setup_intro), color = TextSecondary, fontSize = 14.sp)

            Step(1, stringResource(R.string.fuel_step_ready), ready, onToggle = { ready = !ready }) {
                Text(stringResource(R.string.fuel_step_ready_body), color = TextSecondary, fontSize = 13.sp)
                Text(
                    stringResource(when (obd.status) {
                        ObdState.Status.CONNECTED -> R.string.settings_obd_status_connected
                        ObdState.Status.CONNECTING -> R.string.obd_connecting
                        ObdState.Status.NO_ADAPTER -> R.string.obd_bluetooth_off
                        ObdState.Status.OFF -> R.string.obd_choose_adapter
                        else -> R.string.obd_not_answering
                    }),
                    color = if (obd.status == ObdState.Status.CONNECTED) EcoGreen else NeonAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }

            Step(2, stringResource(R.string.fuel_step_tank), tank != null) {
                Text(stringResource(R.string.fuel_step_tank_body), color = TextSecondary, fontSize = 13.sp)
                LitresField(tank, stringResource(R.string.fuel_litres), onTank, Modifier.testTag("fuel_tank"))
            }

            Step(3, stringResource(R.string.fuel_step_levels), levels >= FuelCalibration.LEVELS_NEEDED) {
                Text(stringResource(R.string.fuel_step_levels_body), color = TextSecondary, fontSize = 13.sp)
                calibration.points.sortedBy { it.liters }.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CockpitSurfaceVariant).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("%.1f L".format(p.liters), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.at)), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(p.at) }) { Icon(Icons.Default.Close, stringResource(R.string.delete), tint = TextSecondary) }
                    }
                }
                if (capturing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = NeonAmber, strokeWidth = 2.dp)
                    Text(stringResource(R.string.fuel_reading), color = NeonAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    val can = obd.status == ObdState.Status.CONNECTED
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FuelButton(stringResource(R.string.fuel_register_empty), can, Modifier.testTag("fuel_empty")) { register(0f) }
                        FuelButton(stringResource(R.string.fuel_register_full, tank?.let { "%.1f".format(it) } ?: "–"), can && tank != null, Modifier.testTag("fuel_full")) { register(tank!!) }
                    }
                    var other by remember { mutableStateOf<Float?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LitresField(other, stringResource(R.string.fuel_litres), { other = it }, Modifier.weight(1f).testTag("fuel_other"))
                        FuelButton(stringResource(R.string.fuel_register_other), can && other != null) { register(other!!) }
                    }
                    Text(stringResource(R.string.fuel_not_sure), color = TextMuted, fontSize = 12.sp)
                    if (!can) Text(stringResource(R.string.fuel_connect_first), color = NeonAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (failed) Text(stringResource(R.string.fuel_read_failed), color = WarningRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Step(4, stringResource(R.string.fuel_step_find), calibration.source != null) {
                Text(stringResource(R.string.fuel_step_find_body), color = TextSecondary, fontSize = 13.sp)
                when {
                    levels < FuelCalibration.LEVELS_NEEDED ->
                        Text(stringResource(R.string.fuel_need_levels, levels), color = NeonAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    candidates.isEmpty() ->
                        Text(stringResource(R.string.fuel_none_found), color = WarningRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    else -> candidates.take(3).forEach { c ->
                        val chosen = c.signal == calibration.source
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (chosen) ElectricCyan.copy(alpha = 0.15f) else CockpitSurfaceVariant)
                                .clickable { onUseSource(if (chosen) null else c.signal) }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (chosen) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null,
                                    tint = if (chosen) ElectricCyan else TextMuted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(signalName(c.signal), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (chosen) Text(stringResource(R.string.fuel_in_use), color = EcoGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(c.readings.joinToString("  ·  ") { (l, raw) -> "%.1f L → %d".format(l, raw) }, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            Step(5, stringResource(R.string.fuel_step_check), calibration.source != null && obd.fuelLiters != null) {
                Text(stringResource(R.string.fuel_step_check_body), color = TextSecondary, fontSize = 13.sp)
                if (calibration.source != null) Text(
                    obd.fuelLiters?.let { stringResource(R.string.fuel_now, "%.1f".format(it)) } ?: stringResource(R.string.fuel_waiting),
                    color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.testTag("fuel_now")
                )
            }

            if (calibration.points.isNotEmpty() || tank != null || calibration.source != null)
                TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.fuel_start_over), color = WarningRed) }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.fuel_start_over)) },
        text = { Text(stringResource(R.string.fuel_start_over_body)) },
        confirmButton = { TextButton(onClick = { onReset(); ready = false; confirmReset = false }) { Text(stringResource(R.string.delete), color = WarningRed) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

/** A step of the checklist: a number turning into a tick when done, a title and what to do. [onToggle] lets the rider tick it by hand. */
@Composable
private fun Step(number: Int, title: String, done: Boolean, onToggle: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CockpitSurface)
            .border(1.dp, if (done) EcoGreen.copy(alpha = 0.6f) else CockpitBorder, RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (done) EcoGreen else CockpitSurfaceVariant)
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle).testTag("fuel_step_$number") else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(20.dp))
            else Text("$number", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun FuelButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = NeonAmber, contentColor = Color.Black)) {
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A number of litres typed in; null while empty or not a number. */
@Composable
private fun LitresField(value: Float?, label: String, onValue: (Float?) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(value?.let { "%.1f".format(it).trimEnd('0').trimEnd('.', ',') } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() || it == '.' || it == ',' }.take(5)
            onValue(text.replace(',', '.').toFloatOrNull()?.takeIf { it in 0f..99f })
        },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

/** A signal's key in words the rider can read out, e.g. "Bus message 3F0, byte 3". */
@Composable
private fun signalName(key: String): String {
    val s = FuelSignal.parse(key) ?: return key
    val bytes = if (s.from == s.to) "${s.from + 1}" else "${s.from + 1}–${s.to + 1}"
    return stringResource(if (s.isPid) R.string.fuel_signal_pid else R.string.fuel_signal_can, s.id, bytes)
}
