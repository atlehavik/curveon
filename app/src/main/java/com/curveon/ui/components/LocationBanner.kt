package com.curveon.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.curveon.R
import com.curveon.ui.theme.*

/** Why Curveon can't see where the rider is, if it can't. */
private enum class LocationProblem { NOT_ALLOWED, OFF }

private fun locationProblem(context: Context): LocationProblem? {
    val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    if (!allowed) return LocationProblem.NOT_ALLOWED
    val on = runCatching { context.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(true)
    return if (on) null else LocationProblem.OFF
}

/**
 * Over the map when Curveon can't see where the rider is: location not allowed, or switched off on the device.
 * Says so and fixes it in one tap; looked at again each time Curveon comes back, so it goes once it is sorted.
 */
@Composable
fun LocationBanner(onAllowed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf(locationProblem(context)) }
    LifecycleResumeEffect(Unit) {
        problem = locationProblem(context)
        if (problem != LocationProblem.NOT_ALLOWED) onAllowed()
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        problem = locationProblem(context)
        if (problem != LocationProblem.NOT_ALLOWED) onAllowed()
    }
    val p = problem ?: return
    Row(
        modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(14.dp)).background(CockpitSurface)
            .border(1.dp, NeonAmber, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("location_banner"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.LocationOff, null, tint = NeonAmber, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(if (p == LocationProblem.NOT_ALLOWED) R.string.location_not_allowed else R.string.location_off),
            color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            if (p == LocationProblem.OFF) context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else {
                val activity = context as? Activity
                // Asked and refused for good: Android won't ask again, so the app's own settings page it is.
                val canAsk = activity != null && activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                if (canAsk) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        }, modifier = Modifier.testTag("location_fix")) {
            Text(stringResource(if (p == LocationProblem.NOT_ALLOWED) R.string.location_allow else R.string.location_turn_on),
                color = ElectricCyan, fontWeight = FontWeight.Bold)
        }
    }
}
