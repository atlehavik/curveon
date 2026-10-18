package com.curveon.phone

import android.app.Notification
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which navigation app is guiding right now, seen from the notifications they keep up while they
 * navigate (for the companion's battery test). Google Maps shows an ongoing navigation
 * notification; Curveon marks its own with [EXTRA_NAVIGATING].
 */
object NavWatch {

    enum class App { NONE, GOOGLE_MAPS, CURVEON }

    data class State(val app: App = App.NONE)

    const val EXTRA_NAVIGATING = "com.curveon.NAVIGATING"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val CURVEON = "com.curveon.app"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What Google Maps' notifications looked like, for checking the guess afterwards (the battery test logs it). */
    @Volatile var onEvent: ((String) -> Unit)? = null

    private val mapsKeys = HashSet<String>()
    private var curveonKey: String? = null

    @Synchronized
    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        when (sbn.packageName) {
            GOOGLE_MAPS -> {
                val nav = sbn.isOngoing && (n.category == Notification.CATEGORY_NAVIGATION ||
                    n.channelId?.contains("nav", ignoreCase = true) == true)
                // Maps re-posts its notification every few seconds: log only when that changes what we think.
                val changed = if (nav) mapsKeys.add(sbn.key) else mapsKeys.remove(sbn.key)
                if (changed) onEvent?.invoke("maps notification ongoing=${sbn.isOngoing} category=${n.category} channel=${n.channelId} nav=$nav")
            }
            CURVEON -> if (n.extras.getBoolean(EXTRA_NAVIGATING)) curveonKey = sbn.key
                else if (curveonKey == sbn.key) curveonKey = null
            else -> return
        }
        publish()
    }

    @Synchronized
    fun onRemoved(sbn: StatusBarNotification) {
        when (sbn.packageName) {
            GOOGLE_MAPS -> if (mapsKeys.remove(sbn.key)) onEvent?.invoke("maps navigation notification removed")
            CURVEON -> if (curveonKey == sbn.key) curveonKey = null
            else -> return
        }
        publish()
    }

    private fun publish() {
        // Both at once shouldn't happen on a test ride; Curveon is then the one that counts.
        _state.value = when {
            curveonKey != null -> State(App.CURVEON)
            mapsKeys.isNotEmpty() -> State(App.GOOGLE_MAPS)
            else -> State()
        }
    }
}
