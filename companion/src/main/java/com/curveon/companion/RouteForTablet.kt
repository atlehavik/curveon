package com.curveon.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.curveon.phone.PhoneHub
import com.curveon.protocol.Message
import com.curveon.protocol.RouteHandoff
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * A trip from Curveon on this phone for the tablet: sent at once when the tablet is connected, otherwise kept
 * (the newest one only) until it connects, so a route planned over lunch is waiting on the bike.
 */
object RouteForTablet {

    private fun file(context: Context) = File(context.filesDir, "route_for_tablet.json")

    fun keep(context: Context, route: Message.RouteFromPhone) =
        file(context).writeText(JSONObject().put("name", route.name).put("route", route.routeGzBase64).toString())

    /** The route waiting for the tablet, taken off the waiting list. */
    fun take(context: Context): Message.RouteFromPhone? {
        val f = file(context)
        if (!f.exists()) return null
        val route = runCatching { JSONObject(f.readText()).let { Message.RouteFromPhone(it.optString("name"), it.getString("route")) } }.getOrNull()
        f.delete()
        return route
    }

    /** Curveon's ordered broadcast; the result code tells Curveon what to say to the rider. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val payload = intent.getStringExtra(RouteHandoff.EXTRA_ROUTE) ?: return
            val route = Message.RouteFromPhone(intent.getStringExtra(RouteHandoff.EXTRA_NAME).orEmpty(), payload)
            val pending = goAsync()
            thread(name = "route-for-tablet") {
                // Kept first: should the link drop while it is written, it goes over on the next connect.
                keep(context, route)
                if (PhoneHub.hasTablet) PhoneHub.broadcast(route)
                pending.resultCode = when {
                    PhoneHub.hasTablet -> { file(context).delete(); RouteHandoff.RESULT_SENT }
                    PhoneHub.status.value.serviceRunning -> RouteHandoff.RESULT_WAITING
                    else -> RouteHandoff.RESULT_SHARING_OFF
                }
                pending.finish()
            }
        }
    }
}
