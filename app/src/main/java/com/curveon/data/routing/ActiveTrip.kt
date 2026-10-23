package com.curveon.data.routing

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The trip being navigated, kept on disk while it is. Android may end Curveon on the way (out of sight, short of
 * memory) or the device restart: at the next start the rider is offered to go on with it. Gone once the trip ends or
 * is stopped, or Curveon is closed from its notification.
 */
class ActiveTrip(context: Context) {

    private val file = File(context.filesDir, FILE)
    private val draft = PlanDraft(context)

    /** The trip now being navigated, or null when none is. */
    fun save(goal: NavigationGoal?) {
        if (goal == null) { file.delete(); return }
        val json = JSONObject().put("goal", draft.goalJson(goal))
            .putOpt("shared", (goal as? NavigationGoal.Destination)?.shared).toString()
        val tmp = File(file.path + ".tmp")
        runCatching { tmp.writeText(json); tmp.renameTo(file) }
    }

    /** Still being navigated: an offer to go on is only made for a trip ridden a short while ago. */
    fun touch() {
        if (file.exists()) file.setLastModified(System.currentTimeMillis())
    }

    /** The trip that was being navigated when Curveon was ended, if that was not long ago. */
    fun load(): NavigationGoal? {
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) { file.delete(); return null }
        return runCatching {
            val json = JSONObject(file.readText())
            val goal = draft.parseGoal(json.getJSONObject("goal"))
            val shared = json.optString("shared").takeIf { it.isNotEmpty() }
            if (goal is NavigationGoal.Destination && shared != null) goal.copy(shared = shared) else goal
        }.getOrNull()
    }

    companion object {
        private const val FILE = "active-trip.json"
        /** Refreshed by [touch] every few minutes while navigating, so this is the time since the trip was left. */
        private const val MAX_AGE_MS = 12 * 60 * 60_000L
        const val TOUCH_EVERY_MS = 5 * 60_000L

        /** The rider closed Curveon on purpose: nothing to offer at the next start. */
        fun forget(context: Context) {
            File(context.filesDir, FILE).delete()
        }
    }
}
