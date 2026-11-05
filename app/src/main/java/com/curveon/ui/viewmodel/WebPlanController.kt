package com.curveon.ui.viewmodel

import android.app.Application
import com.curveon.data.model.RouteItem
import com.curveon.data.repository.CurveonRepository
import com.curveon.data.webplan.WebPlans
import com.curveon.ui.viewmodel.DashboardViewModel.WebPlanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Planning session with the web planner: trips planned in a browser come to this device, and the page sees this
 * device's planned trips. Connected while the planned trips are on screen, else fetched now and then.
 */
class WebPlanController(
    application: Application,
    private val scope: CoroutineScope,
    private val repository: CurveonRepository,
    /** This device's planned trips (and those in Where to?), as the Planned page shows them. */
    private val savedRoutes: StateFlow<List<RouteItem>>,
) {

    private val webPlans = WebPlans(application, repository)

    private val _webPlan = MutableStateFlow(WebPlanState(browsers = webPlans.links().size))
    val webPlan: StateFlow<WebPlanState> = _webPlan.asStateFlow()
    private var webPlanFetched = 0L
    private var webPlanSession: com.curveon.data.webplan.WebPlanSession? = null
    private var webPlanWanted = 0

    init {
        if (webPlans.links().isNotEmpty()) fetchWebPlans()
        showPlannedOnWeb()
    }

    /**
     * A planned trip saved, renamed, moved or deleted here: the page sees it (at once while connected, else on a
     * short connection, and only when something it shows did change).
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun showPlannedOnWeb() = scope.launch {
        combine(savedRoutes, repository.allRouteGroups) { r, g -> r to g }.drop(1).debounce(2000).collect {
            if (webPlans.links().isEmpty()) return@collect
            val session = webPlanSession
            if (session != null) runCatching { session.publish() }
            else if (webPlans.needsPublish()) fetchWebPlans()
        }
    }

    /** Fetches what the web planner sent and shows it this device's trips; [auto] (app shown, Planned opened) at most every half minute. */
    fun fetchWebPlans(auto: Boolean = false) {
        if (_webPlan.value.fetching || webPlans.links().isEmpty() || webPlanSession != null) return
        if (auto && System.currentTimeMillis() - webPlanFetched < 30_000) return
        webPlanFetched = System.currentTimeMillis()
        _webPlan.update { it.copy(fetching = true) }
        scope.launch {
            val r = runCatching { webPlans.sync() }.getOrNull()
            val news = r != null && r.any
            _webPlan.update { it.copy(fetching = false, added = r?.added, changed = r?.changed ?: 0, deleted = r?.deleted ?: 0, tracks = r?.tracks ?: 0, offline = r == null,
                report = if (news) System.currentTimeMillis() else it.report) }
        }
    }

    /** While the planned trips are on screen: connected, so a trip from the page arrives at once. Pair with [webPlanHidden]. */
    fun webPlanShown() {
        webPlanWanted++
        connectWebPlans()
    }

    fun webPlanHidden() {
        webPlanWanted = (webPlanWanted - 1).coerceAtLeast(0)
        if (webPlanWanted == 0) { webPlanSession?.close(); webPlanSession = null; _webPlan.update { it.copy(live = false) } }
    }

    private fun connectWebPlans() {
        if (webPlanSession != null || webPlanWanted == 0 || webPlans.links().isEmpty()) return
        var session: com.curveon.data.webplan.WebPlanSession? = null
        session = webPlans.live(
            onResult = { r ->
                if (r.any) _webPlan.update { it.copy(added = r.added, changed = r.changed, deleted = r.deleted, tracks = r.tracks, offline = false, report = System.currentTimeMillis()) }
            },
            onDropped = {
                // Coverage gone or the relay restarted: try again in a while, as long as the trips are still open.
                scope.launch {
                    // One browser's link dropping ends the whole session, its other links too: left open, they stayed
                    // connected for good beside the new session's (each one a relay channel). A session already replaced
                    // (a new browser linked meanwhile) is old news.
                    if (webPlanSession !== session) return@launch
                    session?.close()
                    webPlanSession = null
                    _webPlan.update { it.copy(live = false) }
                    delay(10_000)
                    connectWebPlans()
                }
            }
        )
        webPlanSession = session
        _webPlan.update { it.copy(live = true, offline = false) }
    }

    /** A scanned (or opened) web planner code: the session starts, and the page sees this device's trips. False when it is not one. */
    fun addWebPlanLink(uri: String): Boolean {
        if (!webPlans.add(uri)) return false
        _webPlan.update { it.copy(browsers = webPlans.links().size) }
        // Connected anew, so the new browser is in it.
        webPlanSession?.close(); webPlanSession = null
        if (webPlanWanted > 0) connectWebPlans() else fetchWebPlans()
        return true
    }

    /** Ends the planning sessions; the trips here stay. */
    fun forgetWebPlans() {
        webPlanSession?.close(); webPlanSession = null
        webPlans.forgetAll()
        _webPlan.value = WebPlanState()
    }
}
