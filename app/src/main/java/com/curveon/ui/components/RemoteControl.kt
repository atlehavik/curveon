package com.curveon.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ActionMode
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.BaseInputConnection
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.edit
import com.curveon.data.remote.KEYS_NOT_FOR_REMOTE
import com.curveon.data.remote.RemoteAction
import com.curveon.data.remote.RemoteBindings
import com.curveon.data.remote.RemoteCommand
import com.curveon.data.remote.RemoteLevel
import com.curveon.data.remote.RemoteSituation
import com.curveon.data.remote.remoteCommand
import kotlinx.coroutines.delay

val LocalRemote = staticCompositionLocalOf<RemoteControl?> { null }

/** What the remote does to the map. */
interface RemoteMap {
    /** Moves the map by this share of its size (screen directions: -1 left or up, 1 right or down). */
    fun pan(dx: Int, dy: Int)
    fun zoomIn()
    fun zoomOut()
    fun recenter()
    /** As a long press on the middle of the map, where the crosshair is. */
    fun whatsHere()
    fun nextOrientation()
    val panned: Boolean
    /** False while drawing an area, where only a finger can point. */
    val canPoint: Boolean
}

/** What the remote does on the main screen. */
interface RemoteDashboard {
    fun whereTo(speak: Boolean)
    fun mapOrDashboard()
    fun nextTrack()
    fun previousTrack()
    fun playPause()
    /** A rail screen covers the map, so zooming it would do nothing the rider can see. */
    val mapCovered: Boolean
}

/** The call that is ringing or going on, for the remote's buttons. */
class RemoteCall(val ringing: Boolean, val answer: () -> Unit, val decline: () -> Unit)

/** The last button seen, for the rider trying the remote in Settings. */
data class RemotePress(val keyCode: Int, val device: String?, val action: RemoteAction?)

/** Something open over the main screen, see [rememberRemoteSheet]. */
class RemoteSheet(val start: FocusRequester, val onBack: androidx.compose.runtime.State<(() -> Unit)?>)

/** One short message about the remote; see RemoteHints. */
enum class RemoteHint { FIRST_PRESS, UNKNOWN_BUTTON }

/**
 * A handlebar remote: every key press goes through here, for the main window and for each dialog's own
 * window ([RemoteWindow]). Nothing runs until a key arrives, so a rider with no remote pays nothing.
 *
 * What each button does is decided in [remoteCommand]; here is how it is carried out: telling a press
 * from a hold, moving the focus among the Compose buttons, and the ring round the chosen one.
 */
class RemoteControl(context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    var bindings by mutableStateOf(RemoteBindings.load(prefs.getString(KEY_BINDINGS, null)))
        private set
    /** Up and down zoom, left and right open the menu; null until chosen, then the remote decides (see [joystickZooms]). */
    private var joystickChoice by mutableStateOf(if (prefs.contains(KEY_JOYSTICK)) prefs.getBoolean(KEY_JOYSTICK, false) else null)
    /** A wheel like BMW's (through WunderLINQ) has no zoom buttons, so it needs the wheel for zooming. */
    private var wheelSeen by mutableStateOf(prefs.getBoolean(KEY_WHEEL, false))
    private var hasOkButton = prefs.getBoolean(KEY_HAS_OK, false)

    val joystickZooms: Boolean get() = joystickChoice ?: wheelSeen
    fun chooseJoystickZooms(zooms: Boolean) { joystickChoice = zooms; prefs.edit { putBoolean(KEY_JOYSTICK, zooms) } }

    /** The button the rider is giving a job in Settings; its next press is taken for that. */
    var capturing by mutableStateOf<RemoteAction?>(null)
    var lastPress by mutableStateOf<RemotePress?>(null)
        private set
    var hint by mutableStateOf<RemoteHint?>(null)
    var dashboard: RemoteDashboard? = null
    var map: RemoteMap? = null
    var call: RemoteCall? = null

    fun assign(keyCode: Int, action: RemoteAction) = save(bindings.with(keyCode, action))
    fun forget(action: RemoteAction) = save(bindings.without(action))
    fun resetButtons() = save(RemoteBindings())
    private fun save(b: RemoteBindings) { bindings = b; prefs.edit { putString(KEY_BINDINGS, b.save()) } }

    /** Pressed lately, so a new screen should choose its first button for the rider. */
    val recentlyUsed: Boolean get() = lastUsed != 0L && SystemClock.elapsedRealtime() - lastUsed < RECENT_MS
    private var lastUsed = 0L

    // What wants the rider's answer, newest last: while there is one, the arrows go there and not to the map.
    private val sheets = ArrayList<RemoteSheet>()
    /** The button the menu opens on: the rail's last one, or what the screen has put first (the pick bar's). */
    var lastRail: FocusRequester? = null
    var menuTarget: FocusRequester? = null
    private var backToRail = false

    private var main: RemoteWindow? = null

    // ---------------------------------------------------------------- sheets

    fun openSheet(sheet: RemoteSheet) {
        sheets += sheet
        if (hint == RemoteHint.FIRST_PRESS) hint = null // the rider has got the idea, and it would cover what opened
    }
    fun closeSheet(sheet: RemoteSheet) {
        sheets -= sheet
        // Back out of a rail screen: on to the button that opened it, as when coming out of a menu anywhere.
        val w = main
        // Otherwise (a place chosen, the trip started): the map, with nothing chosen. Something else may open first.
        val toRail = backToRail
        if (sheets.isEmpty() && w != null) handler.postDelayed({ if (sheets.isEmpty()) { if (toRail) openMenu(w) else w.letGo() } }, FOCUS_SETTLE_MS)
        backToRail = false
    }

    /** Chooses [start] if the remote is in use. */
    fun offerFocus(start: FocusRequester) { if (recentlyUsed) main?.let { w -> w.leaveTouchMode { request(start); w.choose() } } }

    private fun request(r: FocusRequester?) = r != null && runCatching { r.requestFocus() }.isSuccess

    private fun openMenu(w: RemoteWindow) = w.leaveTouchMode {
        // Not all of these are on the screen at any time; asking one that isn't throws.
        request(menuTarget) || request(lastRail)
        w.choose()
        leaveMenuLater(w)
    }

    private val leaveMenu = Runnable { main?.let { w -> if (sheets.isEmpty()) w.letGo() } }
    private fun leaveMenuLater(w: RemoteWindow) {
        handler.removeCallbacks(leaveMenu)
        if (w.main) handler.postDelayed(leaveMenu, MENU_TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- keys

    private var holding: RemoteAction? = null
    private var holdFired = false
    private var choosing = false
    private var swallowUp: Int? = null
    private var lastRepeat = 0L
    private var downAction: RemoteAction? = null
    private var lastUpCode = KeyEvent.KEYCODE_UNKNOWN
    private var lastUpTime = 0L

    fun attachMain(w: RemoteWindow) { main = w }

    /** A finger on the screen: the chosen button stops being shown until the remote is used again. */
    fun onTouch(w: RemoteWindow) {
        if (w.main) handler.removeCallbacks(leaveMenu)
        // A tap on a text field is about to put the focus there: leave it be.
        if (w.chosen) w.letGo()
    }

    fun onKey(event: KeyEvent, w: RemoteWindow): Boolean {
        val code = event.keyCode
        // The keyboard on the screen sends keys too, and they are the field's.
        if (code in KEYS_NOT_FOR_REMOTE || event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return w.passOn(event)
        val device = event.device
        val fromRemote = device?.isExternal == true
        val up = event.action == KeyEvent.ACTION_UP
        if (up && swallowUp == code) { swallowUp = null; return true }

        val capture = capturing
        if (capture != null && (fromRemote || bindings.actionFor(code, false) != null || event.device?.isVirtual != false)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                assign(code, capture)
                capturing = null
                swallowUp = code
            }
            return true
        }

        val action = bindings.actionFor(code, fromRemote)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && (action != null || fromRemote)) {
            lastPress = RemotePress(code, device?.name, action)
            if (action == null) noticeUnknown(code)
        }
        if (action == null) return w.passOn(event)

        // Outside touch mode Android gives some button the focus by itself. That isn't the rider's
        // choice: what counts is what the remote has chosen, which is what the ring is round.
        val focused = w.focused()?.takeIf { it.isTextField || w.chosen }
        // Someone typing on a real keyboard: the letters are letters.
        if (focused?.isTextField == true && event.isPrintingKey) return w.passOn(event)

        // A bump on a rough road can make a button bounce: the same key again at once isn't another press.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (code == lastUpCode && event.eventTime - lastUpTime < BOUNCE_MS) { swallowUp = code; return true }
            noticeUse(action, device)
        } else if (up) { lastUpCode = code; lastUpTime = event.eventTime }
        val level = when {
            !w.main -> RemoteLevel.DIALOG
            sheets.isNotEmpty() -> RemoteLevel.SHEET
            focused != null -> RemoteLevel.BUTTONS
            else -> RemoteLevel.MAP
        }
        val situation = RemoteSituation(
            level, panned = map?.panned == true, ringing = call?.ringing == true, inCall = call != null,
            joystickZooms = joystickZooms, hasOkButton = hasOkButton
        )
        // OK and Back on the map tell a press from a hold, as does anything held there.
        val holdable = level == RemoteLevel.MAP && !situation.ringing && (action == RemoteAction.OK || action == RemoteAction.BACK)

        when {
            up -> {
                if (holding == action) {
                    handler.removeCallbacks(holdTimer)
                    holding = null
                    if (!holdFired) run(remoteCommand(action, false, situation), action, event, w, focused)
                } else if (choosing && downAction == action) {
                    choosing = false
                    w.passOn(asChoose(event))
                }
                downAction = null
                w.ringSoon()
                return true
            }
            event.repeatCount > 0 -> {
                // Android says when a held key has become a long press; the timer is for remotes that send no repeats.
                if (holding == action && event.isLongPress && !holdFired) { handler.removeCallbacks(holdTimer); holdTimer.run() }
                if (holding == action || !(action.isArrow || action.isZoom)) return true
                val gap = if (remoteCommand(action, false, situation).isZoom) ZOOM_REPEAT_MS else MOVE_REPEAT_MS
                val now = SystemClock.uptimeMillis()
                if (now - lastRepeat < gap) return true
                lastRepeat = now
            }
            holdable -> {
                holding = action
                holdFired = false
                holdTimer = Runnable {
                    if (holding == action) {
                        holdFired = true
                        run(remoteCommand(action, true, situation), action, event, w, focused)
                    }
                }
                handler.postDelayed(holdTimer, HOLD_MS)
                return true
            }
            else -> lastRepeat = SystemClock.uptimeMillis()
        }
        downAction = action
        run(remoteCommand(action, false, situation), action, event, w, focused)
        w.ringSoon()
        if (level == RemoteLevel.BUTTONS) leaveMenuLater(w)
        return true
    }

    /**
     * A key in a dropdown menu, which is a window Android gives no way into: the arrows and Enter it knows
     * itself. Other keys become those, and Esc (which it doesn't know) closes it as Back would.
     */
    fun onPopupKey(event: KeyEvent, popup: View, focus: FocusManager): Boolean {
        val code = event.keyCode
        if (code in KEYS_A_POPUP_KNOWS) return false
        val action = bindings.actionFor(code, event.device?.isExternal == true) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) lastUsed = SystemClock.elapsedRealtime()
        fun back() { if (down && event.repeatCount == 0) {
            popup.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            popup.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        } }
        fun move(direction: FocusDirection) { if (down) focus.moveFocus(direction) }
        when (action) {
            RemoteAction.UP -> move(FocusDirection.Up)
            RemoteAction.DOWN -> move(FocusDirection.Down)
            RemoteAction.LEFT -> move(FocusDirection.Left)
            RemoteAction.RIGHT -> move(FocusDirection.Right)
            RemoteAction.OK, RemoteAction.RECENTER -> popup.rootView.dispatchKeyEvent(asChoose(event))
            RemoteAction.BACK, RemoteAction.MENU -> back()
            RemoteAction.ZOOM_IN -> if (!hasOkButton) popup.rootView.dispatchKeyEvent(asChoose(event))
            RemoteAction.ZOOM_OUT -> if (!hasOkButton) back()
            else -> return false
        }
        return true
    }

    private var holdTimer = Runnable { }
    private val RemoteCommand.isZoom get() = this == RemoteCommand.ZOOM_IN || this == RemoteCommand.ZOOM_OUT

    private fun run(command: RemoteCommand, action: RemoteAction, event: KeyEvent, w: RemoteWindow, focused: Focused?) {
        val mapHere = map
        when (command) {
            RemoteCommand.NOTHING -> Unit
            RemoteCommand.PAN_UP -> mapHere?.pan(0, -1)
            RemoteCommand.PAN_DOWN -> mapHere?.pan(0, 1)
            RemoteCommand.PAN_LEFT -> mapHere?.pan(-1, 0)
            RemoteCommand.PAN_RIGHT -> mapHere?.pan(1, 0)
            RemoteCommand.ZOOM_IN -> if (dashboard?.mapCovered != true) mapHere?.zoomIn()
            RemoteCommand.ZOOM_OUT -> if (dashboard?.mapCovered != true) mapHere?.zoomOut()
            RemoteCommand.OPEN_MENU -> openMenu(w)
            RemoteCommand.LEAVE_MENU -> { handler.removeCallbacks(leaveMenu); w.letGo() }
            RemoteCommand.WHATS_HERE -> if (mapHere?.canPoint == true) mapHere.whatsHere() else openMenu(w)
            RemoteCommand.RECENTER -> mapHere?.recenter()
            RemoteCommand.ORIENTATION -> mapHere?.nextOrientation()
            RemoteCommand.WHERE_TO -> dashboard?.whereTo(speak = false)
            RemoteCommand.SPEAK_WHERE_TO -> dashboard?.whereTo(speak = true)
            RemoteCommand.MAP_OR_DASHBOARD -> dashboard?.mapOrDashboard()
            RemoteCommand.NEXT_TRACK -> dashboard?.nextTrack()
            RemoteCommand.PREVIOUS_TRACK -> dashboard?.previousTrack()
            RemoteCommand.PLAY_PAUSE -> dashboard?.playPause()
            RemoteCommand.ANSWER -> call?.answer?.invoke()
            RemoteCommand.DECLINE, RemoteCommand.END_CALL -> call?.decline?.invoke()
            RemoteCommand.MOVE_UP -> w.move(FocusDirection.Up, focused, sheets.lastOrNull()?.start)
            RemoteCommand.MOVE_DOWN -> w.move(FocusDirection.Down, focused, sheets.lastOrNull()?.start)
            RemoteCommand.MOVE_LEFT -> w.move(FocusDirection.Left, focused, sheets.lastOrNull()?.start)
            RemoteCommand.MOVE_RIGHT -> w.move(FocusDirection.Right, focused, sheets.lastOrNull()?.start)
            // Nothing chosen yet: this press chooses the first button, it doesn't press one unseen.
            RemoteCommand.CHOOSE ->
                if (focused == null) w.move(FocusDirection.Next, null, sheets.lastOrNull()?.start)
                else { choosing = true; downAction = action; w.passOn(asChoose(event)) }
            RemoteCommand.GO_BACK -> {
                val answer = if (w.main) sheets.lastOrNull()?.onBack?.value else null
                if (answer != null) answer()
                else {
                    backToRail = w.main && sheets.isNotEmpty()
                    w.goBack()
                }
            }
        }
    }

    /** The key Compose's buttons take as a press, in the form it expects from a D-pad. */
    private fun asChoose(e: KeyEvent) = KeyEvent(
        e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_DPAD_CENTER, e.repeatCount, 0, e.deviceId, e.scanCode, e.flags, InputDevice.SOURCE_DPAD
    )

    private fun noticeUse(action: RemoteAction, device: InputDevice?) {
        lastUsed = SystemClock.elapsedRealtime()
        if (hint != null && ++pressesSinceHint > PRESSES_TO_HIDE_HINT) { hint = null; pressesSinceHint = 0 }
        if (action == RemoteAction.OK && !hasOkButton) { hasOkButton = true; prefs.edit { putBoolean(KEY_HAS_OK, true) } }
        if (!wheelSeen && device?.name?.contains("wunderlinq", ignoreCase = true) == true) { wheelSeen = true; prefs.edit { putBoolean(KEY_WHEEL, true) } }
        if (!prefs.getBoolean(KEY_INTRODUCED, false)) {
            prefs.edit { putBoolean(KEY_INTRODUCED, true) }
            hint = RemoteHint.FIRST_PRESS
        }
    }

    /** Once per start: a button that does nothing, and where to give it a job. Headsets' volume and music keys aren't asked about. */
    private fun noticeUnknown(code: Int) {
        if (unknownSaid || code in NOT_WORTH_ASKING) return
        unknownSaid = true
        hint = RemoteHint.UNKNOWN_BUTTON
    }
    private var unknownSaid = false
    private var pressesSinceHint = 0

    /** Remotes connected now, by name. Headsets are input devices too, but offer no arrows or OK. */
    fun connectedRemotes(): List<String> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { d ->
        d.isExternal && !d.isVirtual && d.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            d.hasKeys(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_F6).any { it }
    }.map { it.name }.distinct()

    companion object {
        private const val KEY_BINDINGS = "bindings"
        private const val KEY_JOYSTICK = "joystickZooms"
        private const val KEY_WHEEL = "wheelSeen"
        private const val KEY_HAS_OK = "hasOk"
        private const val KEY_INTRODUCED = "introduced"
        private const val HOLD_MS = 600L
        private const val BOUNCE_MS = 60L
        private const val PRESSES_TO_HIDE_HINT = 4
        private const val MOVE_REPEAT_MS = 140L
        private const val ZOOM_REPEAT_MS = 350L
        private const val RECENT_MS = 60_000L
        private const val MENU_TIMEOUT_MS = 15_000L
        private const val FOCUS_SETTLE_MS = 150L
        private val KEYS_A_POPUP_KNOWS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK
        )
        private val NOT_WORTH_ASKING = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT
        )

        /**
         * A remote that says it is a full keyboard makes Android put away the keyboard on the screen,
         * unless the rider has turned that off in Android's settings. An app can't change that itself.
         */
        fun hidesScreenKeyboard(context: Context): Boolean {
            val c = context.resources.configuration
            if (c.keyboard != Configuration.KEYBOARD_QWERTY || c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_NO) return false
            return runCatching { Settings.Secure.getInt(context.contentResolver, "show_ime_with_hard_keyboard", 0) }.getOrDefault(0) == 0
        }
    }
}

/** The button chosen in a window. */
class Focused(val bounds: ComposeRect, val isTextField: Boolean)

/** One window the remote works in: the activity's, or a dialog's. */
class RemoteWindow(
    private val remote: RemoteControl,
    private val window: Window,
    val focusManager: FocusManager,
    val main: Boolean,
    private val dispatch: (KeyEvent) -> Boolean,
    private val backDispatcher: OnBackPressedDispatcher? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = window.decorView.resources.displayMetrics.density
    private val ring = FocusRing(density)
    private var ringAdded = false
    private var keepUntil = 0L

    fun passOn(event: KeyEvent) = dispatch(event)

    /** The chosen button, from what Compose tells the accessibility services: the one way that covers all of them. */
    fun focused(): Focused? {
        val root = composeRoot(window.decorView) ?: return null
        val node = runCatching { findFocused(root.semanticsOwner.unmergedRootSemanticsNode) }.getOrNull() ?: return null
        return Focused(node.boundsInWindow, SemanticsActions.SetText in node.config)
    }

    private fun composeRoot(v: View): RootForTest? = when (v) {
        is RootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { composeRoot(v.getChildAt(it)) }
        else -> null
    }

    private fun findFocused(n: SemanticsNode): SemanticsNode? {
        for (c in n.children) findFocused(c)?.let { return it }
        return n.takeIf { it.config.getOrNull(SemanticsProperties.Focused) == true }
    }

    /** The remote has chosen a button, or is about to: from now on the ring is round it and the arrows move from it. */
    var chosen = false
        private set

    fun choose() {
        chosen = true
        choseAt = SystemClock.uptimeMillis()
        ringSoon()
    }
    private var choseAt = 0L

    /** Nothing is chosen any more: the ring goes, and the anchor (below) takes the focus again. */
    fun letGo() {
        chosen = false
        handler.removeCallbacks(refresh)
        ring.hide()
        if (focused()?.isTextField != true) focusManager.clearFocus(true)
        holdViewFocus()
    }

    fun move(direction: FocusDirection, focused: Focused?, sheet: FocusRequester?) = leaveTouchMode {
        when {
            focused != null -> focusManager.moveFocus(direction)
            // The first press on a screen: its first button, and the arrow's direction doesn't count.
            sheet != null -> {
                if (runCatching { sheet.requestFocus() }.isFailure) chooseFirst()
                // A sheet whose first part has nothing to choose yet: anything at all rather than nothing.
                else handler.postDelayed({ if (focused() == null) { chooseFirst(); ringSoon() } }, FOCUS_FALLBACK_MS)
            }
            // Android may have put the focus on the first button already, unseen; then that is the one.
            focused() == null -> chooseFirst()
        }
        choose()
    }

    /** The first button of the window. While the anchor holds the focus Compose must first be given it, and then picks that itself. */
    private fun chooseFirst() {
        val compose = composeRoot(window.decorView) as? View ?: return
        if (compose.hasFocus()) focusManager.moveFocus(FocusDirection.Next) else compose.requestFocus()
    }

    /**
     * Android leaves touch mode on the first key, and if no view holds the focus by then it gives it to
     * one and eats the key. Outside touch mode it gives it to one anyway, and Compose passes it to the first
     * button, which would light up unasked. So while nothing is chosen this invisible speck holds the
     * focus: that way the rider's first press always counts. Choosing a button takes the focus from it.
     */
    private val anchor = View(window.decorView.context).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun holdViewFocus() {
        if (anchor.parent == null) window.addContentView(anchor, ViewGroup.LayoutParams(1, 1))
        if (chosen || anchor.isFocused || focused()?.isTextField == true) return
        // Only once it has been laid out; a view with no size can't hold the focus.
        if (anchor.width > 0) anchor.requestFocus()
        else handler.post { if (!chosen && anchor.width > 0 && focused()?.isTextField != true) anchor.requestFocus() }
    }

    /**
     * Compose's buttons only take the focus outside touch mode, and Android only leaves that for certain
     * keys. A remote's other keys (F6, say) would find them unwilling, so a key that does nothing is sent first.
     */
    fun leaveTouchMode(then: () -> Unit) {
        val decor = window.decorView
        if (!decor.isInTouchMode) return then()
        val connection = BaseInputConnection(decor, false)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME))
        handler.post(then)
    }

    /** Back as on the phone, except that it never leaves the app: there is nothing behind the map to go to on the bike. */
    fun goBack() {
        if (backDispatcher != null) { if (backDispatcher.hasEnabledCallbacks()) backDispatcher.onBackPressed(); return }
        dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dispatch(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
    }

    // ---------------------------------------------------------------- the ring

    private val refresh = object : Runnable {
        override fun run() {
            val f = if (chosen) focused() else null
            val now = SystemClock.uptimeMillis()
            // The focus takes a moment to arrive; if it still isn't there the button is gone (its screen closed),
            // and nothing is chosen any more.
            val waiting = chosen && f == null && now - choseAt < FOCUS_ARRIVES_MS
            if (chosen && f == null && !waiting) chosen = false
            if (waiting) { handler.postDelayed(this, RING_FIRST_MS); return }
            holdViewFocus()
            if (f == null) ring.hide() else {
                if (!ringAdded) { window.decorView.overlay.add(ring); ringAdded = true }
                ring.show(f.bounds)
            }
            // Lists scroll and cards come and go under the ring: it follows for as long as the rider is at it.
            // Left alone it goes, so it can't stay behind where a button used to be; the next press brings it back.
            if (f != null) {
                if (SystemClock.uptimeMillis() < keepUntil) handler.postDelayed(this, RING_FOLLOW_MS) else ring.hide()
            }
        }
    }

    fun ringSoon() {
        keepUntil = SystemClock.uptimeMillis() + RING_KEEP_MS
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, RING_FIRST_MS)
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        if (ringAdded) window.decorView.overlay.remove(ring)
        ringAdded = false
    }

    private companion object {
        const val RING_FIRST_MS = 30L
        const val RING_FOLLOW_MS = 250L
        const val RING_KEEP_MS = 60_000L
        const val FOCUS_FALLBACK_MS = 250L
        const val FOCUS_ARRIVES_MS = 400L
    }
}

/**
 * The ring round the chosen button: white with a dark edge, because it must stand out on the day map,
 * the night map and a cyan button alike. It glides to the next button so the eye can follow it.
 */
private class FocusRing(private val density: Float) : Drawable() {
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE; strokeWidth = 3 * density }
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCC000000.toInt(); strokeWidth = 5.5f * density }
    private val shown = RectF()
    private val from = RectF()
    private val to = RectF()
    private var visible = false
    private var animator: android.animation.ValueAnimator? = null

    init { setBounds(0, 0, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2) }

    fun show(b: ComposeRect) {
        val pad = 4 * density
        val target = RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
        if (visible && target == to) return
        to.set(target)
        if (!visible) {
            visible = true
            shown.set(target)
            invalidateSelf()
            return
        }
        from.set(shown)
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(GLIDE_MS).apply {
            addUpdateListener {
                val t = it.animatedValue as Float
                shown.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t)
                invalidateSelf()
            }
            start()
        }
    }

    fun hide() {
        if (!visible) return
        visible = false
        animator?.cancel()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!visible) return
        val radius = minOf(shown.height(), shown.width()) / 2
        val r = minOf(radius, MAX_RADIUS_DP * density)
        canvas.drawRoundRect(shown, r, r, outer)
        canvas.drawRoundRect(shown, r, r, inner)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val GLIDE_MS = 110L
        const val MAX_RADIUS_DP = 30
    }
}

/** A dialog's window passes its keys and touches through the remote before anything else. */
private class RemoteWindowCallback(
    private val base: Window.Callback,
    private val remote: RemoteControl,
    private val remoteWindow: () -> RemoteWindow?
) : Window.Callback by base {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = remoteWindow()?.let { remote.onKey(event, it) } ?: base.dispatchKeyEvent(event)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) remoteWindow()?.let { remote.onTouch(it) }
        return base.dispatchTouchEvent(event)
    }
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = base.onWindowStartingActionMode(callback, type)
}

/** Connects a dialog's [window] to the remote; see [HideSystemBars], which every dialog calls. */
@Composable
internal fun RemoteInDialog(window: Window) {
    val remote = LocalRemote.current ?: return
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val current = remember { arrayOfNulls<RemoteWindow>(1) }
    DisposableEffect(window) {
        val base = window.callback
        val w = RemoteWindow(remote, window, focus, main = false, dispatch = { base.dispatchKeyEvent(it) })
        current[0] = w
        w.holdViewFocus()
        window.callback = RemoteWindowCallback(base, remote) { current[0] }
        onDispose {
            w.detach()
            current[0] = null
            window.callback = base
        }
    }
    // Opened from the remote: the first button is chosen already, unless the dialog has chosen one itself.
    LaunchedEffect(window) {
        if (!remote.recentlyUsed || remote.capturing != null) return@LaunchedEffect
        delay(DIALOG_FOCUS_DELAY_MS)
        current[0]?.move(FocusDirection.Next, null, null)
    }
}

private const val DIALOG_FOCUS_DELAY_MS = 150L

/** Goes on every dropdown menu: its first choice is chosen for a rider using the remote, whose buttons all work in it; see [RemoteControl.onPopupKey]. */
fun Modifier.remotePopup(): Modifier = composed {
    val remote = LocalRemote.current ?: return@composed Modifier
    val view = androidx.compose.ui.platform.LocalView.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (remote.recentlyUsed) {
            delay(POPUP_FOCUS_DELAY_MS)
            runCatching { first.requestFocus() }
        }
    }
    Modifier.focusRequester(first).focusGroup().onPreviewKeyEvent { remote.onPopupKey(it.nativeKeyEvent, view, focus) }
}

private const val POPUP_FOCUS_DELAY_MS = 60L

/**
 * For what wants the rider's answer over the main screen: while it is shown, the arrows go here and not to
 * the map, and its first button is chosen for a rider using the remote. Put the requester on that button.
 * [onBack] is what Back means here, when that isn't what the phone's own Back does.
 */
@Composable
fun rememberRemoteSheet(onBack: (() -> Unit)? = null): FocusRequester {
    val remote = LocalRemote.current
    val start = remember { FocusRequester() }
    val back = androidx.compose.runtime.rememberUpdatedState(onBack)
    if (remote != null) {
        DisposableEffect(remote) {
            val sheet = RemoteSheet(start, back)
            remote.openSheet(sheet)
            onDispose { remote.closeSheet(sheet) }
        }
        LaunchedEffect(remote) {
            delay(SHEET_FOCUS_DELAY_MS)
            remote.offerFocus(start)
        }
    }
    return start
}

/** A whole card that wants an answer, as [rememberRemoteSheet]; its first button is chosen. */
@Composable
fun Modifier.remoteSheet(onBack: (() -> Unit)? = null): Modifier {
    val start = rememberRemoteSheet(onBack)
    return focusRequester(start).focusGroup()
}

/** The button the remote's OK goes to first on the map instead of the rail, while it is on screen (the bar for picking on the map). */
@Composable
fun rememberRemoteMenuTarget(): FocusRequester {
    val remote = LocalRemote.current
    val target = remember { FocusRequester() }
    if (remote != null) DisposableEffect(remote) {
        remote.menuTarget = target
        onDispose { if (remote.menuTarget === target) remote.menuTarget = null }
    }
    return target
}

private const val SHEET_FOCUS_DELAY_MS = 80L
