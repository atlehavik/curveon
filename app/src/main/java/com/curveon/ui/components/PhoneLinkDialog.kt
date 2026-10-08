package com.curveon.ui.components

import com.curveon.R
import androidx.compose.ui.res.stringResource
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
                    CompanionConnectionState.CONNECTED -> stringResource(R.string.link_connected, name)
                    CompanionConnectionState.CONNECTING -> stringResource(R.string.phone_connecting_to, name)
                    CompanionConnectionState.DISCONNECTED -> stringResource(R.string.link_disconnected, name)
                    CompanionConnectionState.UNREACHABLE -> stringResource(R.string.link_unreachable, name)
                    CompanionConnectionState.NO_PHONE_SELECTED -> stringResource(R.string.link_pick)
                    CompanionConnectionState.NO_PERMISSION -> stringResource(R.string.link_no_permission)
                    CompanionConnectionState.BLUETOOTH_OFF -> stringResource(R.string.bluetooth_off)
                    CompanionConnectionState.DEMO -> stringResource(R.string.link_demo)
                },
                color = TextSecondary, fontSize = 14.sp
            )

            if (state == CompanionConnectionState.NO_PERMISSION && Build.VERSION.SDK_INT >= 31) {
                Button(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)) {
                    Text(stringResource(R.string.link_allow_bluetooth), color = androidx.compose.ui.graphics.Color(0xFF00363D), fontWeight = FontWeight.Bold)
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
                        if (p.address == selected) Icon(Icons.Default.CheckCircle, stringResource(R.string.selected), tint = EcoGreen)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.link_demo_data), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.link_demo_data_hint), color = TextMuted, fontSize = 12.sp)
                }
                CockpitSwitch(
                    checked = state == CompanionConnectionState.DEMO,
                    onCheckedChange = { manager.setDemo(it); refresh++ }, accent = NeonAmber)
            }
        }
    }
}
