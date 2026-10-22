package com.curveon.data.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.curveon.data.model.RouteItem
import com.curveon.protocol.RouteHandoff
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Planning over lunch on the phone, riding on the tablet: Curveon on the phone hands a trip (planned, ridden or GPX,
 * as a saved route) to Curveon Companion on the same phone, which sends it to the tablet over Bluetooth.
 */
object TabletRoutes {

    /** What became of a trip sent to the tablet; the screen tells the rider. */
    enum class Outcome { SENT, WAITING, SHARING_OFF, NO_ANSWER, TOO_LONG, EMPTY }

    /** Only offered with Curveon Companion on this phone: it is what talks to the tablet. */
    fun companionInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(RouteHandoff.COMPANION_PACKAGE, 0) }.isSuccess

    /** Opens Curveon Companion, so the rider can start sharing (or wake it after an install). */
    fun openCompanion(context: Context) {
        context.packageManager.getLaunchIntentForPackage(RouteHandoff.COMPANION_PACKAGE)
            ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun send(context: Context, item: RouteItem, onOutcome: (Outcome) -> Unit) {
        val payload = encode(item)
        // A broadcast carries well under a megabyte; a line thinned to every 25 m fits days of riding.
        if (payload.length > MAX_PAYLOAD) return onOutcome(Outcome.TOO_LONG)
        val intent = Intent(RouteHandoff.ACTION).setPackage(RouteHandoff.COMPANION_PACKAGE)
            .putExtra(RouteHandoff.EXTRA_NAME, item.title).putExtra(RouteHandoff.EXTRA_ROUTE, payload)
        context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onOutcome(when (resultCode) {
                RouteHandoff.RESULT_SENT -> Outcome.SENT
                RouteHandoff.RESULT_WAITING -> Outcome.WAITING
                RouteHandoff.RESULT_SHARING_OFF -> Outcome.SHARING_OFF
                else -> Outcome.NO_ANSWER
            })
        }, null, RouteHandoff.RESULT_NO_ANSWER, null, null)
    }

    fun encode(item: RouteItem): String {
        val json = JSONObject()
            .put("title", item.title).put("subtitle", item.subtitle)
            .put("km", item.totalDistanceKm.toDouble()).put("minutes", item.estimatedMinutes)
            .put("road", item.roadType).put("ground", item.groundType)
            .put("curves", item.curvinessScore).put("climb", item.elevationGainMeters).put("loop", item.isRoundtrip)
            .put("line", item.polylineJson).put("waypoints", item.waypointsJson)
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toString().toByteArray()) } }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** The trip as a planned trip on the tablet (not yet saved: id 0), or null if it can't be read. */
    fun decode(payload: String): RouteItem? = runCatching {
        val text = GZIPInputStream(Base64.decode(payload, Base64.NO_WRAP).inputStream()).use { it.readBytes().decodeToString() }
        val j = JSONObject(text)
        RouteItem(
            title = j.getString("title"), subtitle = j.optString("subtitle"),
            totalDistanceKm = j.optDouble("km").toFloat(), estimatedMinutes = j.optInt("minutes"),
            roadType = j.optString("road", "CURVY"), groundType = j.optString("ground", "PAVED"),
            curvinessScore = j.optInt("curves"), elevationGainMeters = j.optInt("climb"), isRoundtrip = j.optBoolean("loop"),
            polylineJson = j.getString("line"), waypointsJson = j.optString("waypoints"),
            inWhereTo = false, inPlanned = true
        )
    }.getOrNull()

    private const val MAX_PAYLOAD = 600_000
}
