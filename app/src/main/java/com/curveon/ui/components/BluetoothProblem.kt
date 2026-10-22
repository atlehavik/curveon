package com.curveon.ui.components

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.curveon.R
import com.curveon.ui.theme.ElectricCyan
import com.curveon.ui.theme.NeonAmber

/** Why Bluetooth devices (the OBD adapter) can't be reached: not allowed for Curveon, or switched off. */
enum class BluetoothProblem(val text: Int, val action: Int) {
    NOT_ALLOWED(R.string.bt_not_allowed, R.string.location_allow),
    OFF(R.string.bt_off, R.string.location_turn_on)
}

fun bluetoothProblem(context: Context): BluetoothProblem? {
    if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        return BluetoothProblem.NOT_ALLOWED
    val on = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled ?: return BluetoothProblem.OFF
    return if (on) null else BluetoothProblem.OFF
}

/** The problem now, looked at again whenever Curveon comes back (from Android's settings, say). */
@Composable
fun rememberBluetoothProblem(): BluetoothProblem? {
    val context = LocalContext.current
    var problem by remember { mutableStateOf(bluetoothProblem(context)) }
    LifecycleResumeEffect(Unit) {
        problem = bluetoothProblem(context)
        onPauseOrDispose { }
    }
    return problem
}

/** What's wrong with Bluetooth, and a button that sorts it: ask for the permission (or open the app's settings), or turn it on. */
@Composable
fun BluetoothFix(problem: BluetoothProblem, onFixed: () -> Unit = {}) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) onFixed() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onFixed() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("bluetooth_fix")) {
        Text(stringResource(problem.text), color = NeonAmber, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            when (problem) {
                BluetoothProblem.NOT_ALLOWED -> {
                    val activity = context as? Activity
                    if (Build.VERSION.SDK_INT >= 31 && activity?.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) == true)
                        ask.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }
                BluetoothProblem.OFF -> runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
        }) { Text(stringResource(problem.action), color = ElectricCyan) }
    }
}
