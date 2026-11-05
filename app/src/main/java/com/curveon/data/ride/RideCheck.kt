package com.curveon.data.ride

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Looks at the ride under way every so often, also with the screen off and no GPS fix to go by (a phone indoors), when
 * ordinary timers stand still: an alarm from Android. Inexact, so no special permission, and Android may be late with it
 * while the device lies still for long. When Curveon has been ended it wakes nothing: there is no ride to look at.
 */
class RideCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        _ticks.tryEmit(Unit)
    }

    companion object {
        private const val EVERY_MS = 60_000L
        private val _ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val ticks: SharedFlow<Unit> = _ticks

        private fun intent(context: Context) = PendingIntent.getBroadcast(
            context, 0, Intent(context, RideCheck::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun next(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY_MS, intent(context))
        }

        fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(intent(context))
    }
}
