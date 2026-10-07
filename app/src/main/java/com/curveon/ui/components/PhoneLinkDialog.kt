package com.curveon.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curveon.data.bluetooth.CompanionConnectionState
import com.curveon.data.bluetooth.PhoneCompanionManager
import com.curveon.ui.theme.*

/** Choose which paired phone to mirror, or turn on demo data. Shown in Settings → Phone. */
@Composable
fun PhoneLinkContent(manager: PhoneCompanionManager, modifier: Modifier = Modifier) {
    val state by manager.connectionState.collectAsStateWithLifecycle()
    val name by manager.connectedDeviceName.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val phones = remember(refresh, state) { manager.pairedPhones() }
    val selected = remember(refresh, state) { manager.selectedPhoneAddress() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        manager.restart(); refresh++
    }

    run {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when (state) {
                    CompanionConnectionState.CONNECTED -> "Connected to $name"
                    CompanionConnectionState.CONNECTING -> "Connecting to $name…"
                    CompanionConnectionState.DISCONNECTED -> "$name is not answering. Open Curveon Companion on the phone and tap Start sharing."
                    CompanionConnectionState.UNREACHABLE -> "$name's Bluetooth doesn't answer at all. If Curveon Companion is sharing, turn Bluetooth off and on on this tablet (or the phone)."
                    CompanionConnectionState.NO_PHONE_SELECTED -> "Pick your phone below. It must be paired in Android's Bluetooth settings first."
                    CompanionConnectionState.NO_PERMISSION -> "Curveon needs permission to use Bluetooth."
                    CompanionConnectionState.BLUETOOTH_OFF -> "Bluetooth is off."
                    CompanionConnectionState.DEMO -> "Showing demo music and messages."
                },
                color = TextSecondary, fontSize = 14.sp
            )

            if (state == CompanionConnectionState.NO_PERMISSION && Build.VERSION.SDK_INT >= 31) {
                Button(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                    Text("Allow Bluetooth", color = androidx.compose.ui.graphics.Color(0xFF00363D), fontWeight = FontWeight.Bold)
                }
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(phones) { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CockpitSurfaceVariant)
                            .clickable { manager.selectPhone(p); refresh++ }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Smartphone, null, tint = ElectricCyan)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(p.address, color = TextMuted, fontSize = 11.sp)
                        }
                        if (p.address == selected) Icon(Icons.Default.CheckCircle, "Selected", tint = EcoGreen)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo data", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Sample music and messages, for trying the dashboard without a phone", color = TextMuted, fontSize = 12.sp)
                }
                Switch(
                    checked = state == CompanionConnectionState.DEMO,
                    onCheckedChange = { manager.setDemo(it); refresh++ },
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonAmber)
                )
            }
        }
    }
}
