package com.curveon.data.remote

import android.view.KeyEvent

/**
 * What a button on a handlebar remote means. Remotes are Bluetooth keyboards, and each sends its own
 * keys (arrows and Enter, F6/F7, or letters made for another app): [DEFAULT_KEYS] knows them all, and
 * the rider can give any button another job in Settings → Remote.
 */
enum class RemoteAction {
    UP, DOWN, LEFT, RIGHT, OK, BACK, ZOOM_IN, ZOOM_OUT, MENU, RECENTER, WHERE_TO, SPEAK_WHERE_TO,
    ORIENTATION, MAP_OR_DASHBOARD, NEXT_TRACK, PREVIOUS_TRACK, PLAY_PAUSE, CALL;

    val isArrow get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
    val isZoom get() = this == ZOOM_IN || this == ZOOM_OUT
}

/**
 * The keys the common remotes send, so a remote works before anything is set up. The letters are the
 * ones riders' remotes are set up with for other apps (MyRoute-app, Kurviger, OsmAnd): N, A or M for the
 * menu, C or L to come back to the rider, B, K or D for the map's direction, S to search.
 */
val DEFAULT_KEYS: Map<Int, RemoteAction> = buildMap {
    fun put(action: RemoteAction, vararg keys: Int) = keys.forEach { put(it, action) }
    put(RemoteAction.UP, KeyEvent.KEYCODE_DPAD_UP)
    put(RemoteAction.DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
    put(RemoteAction.LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
    put(RemoteAction.RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
    put(RemoteAction.OK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A)
    put(RemoteAction.BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B)
    put(RemoteAction.ZOOM_IN, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_ZOOM_IN)
    put(RemoteAction.ZOOM_OUT, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_ZOOM_OUT)
    put(RemoteAction.MENU, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_MEDIA_EJECT)
    put(RemoteAction.RECENTER, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_L)
    put(RemoteAction.ORIENTATION, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_D)
    put(RemoteAction.WHERE_TO, KeyEvent.KEYCODE_S)
    put(RemoteAction.NEXT_TRACK, KeyEvent.KEYCODE_MEDIA_NEXT)
    put(RemoteAction.PREVIOUS_TRACK, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    put(RemoteAction.PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
    put(RemoteAction.CALL, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL)
}

/** The device's own buttons send these as well: they keep their meaning unless the key comes from a remote. */
private val DEVICE_KEYS = setOf(
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL
)

/** Keys Android keeps for itself or that would lock the rider out: never given another job. */
val KEYS_NOT_FOR_REMOTE = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_UNKNOWN)

/**
 * Which key does what: the defaults, plus what the rider has changed ([changes]: a key's new job, or
 * null for a key that should do nothing).
 */
class RemoteBindings(val changes: Map<Int, RemoteAction?> = emptyMap()) {
    /** The job of [keyCode]; the device's own keys only have one when they come from a remote. */
    fun actionFor(keyCode: Int, fromRemote: Boolean): RemoteAction? {
        if (keyCode in KEYS_NOT_FOR_REMOTE || (keyCode in DEVICE_KEYS && !fromRemote)) return null
        return if (keyCode in changes) changes[keyCode] else DEFAULT_KEYS[keyCode]
    }

    /** Every key that does [action]. */
    fun keysFor(action: RemoteAction): List<Int> =
        (DEFAULT_KEYS.keys + changes.keys).filter { (if (it in changes) changes[it] else DEFAULT_KEYS[it]) == action }

    /** [keyCode] now does [action], and only that. */
    fun with(keyCode: Int, action: RemoteAction) = RemoteBindings(changes + (keyCode to action))

    /** No key does [action] any longer. */
    fun without(action: RemoteAction) = RemoteBindings(changes + keysFor(action).associateWith { null })

    /** For the settings file: "code=ACTION" (or "code=" for a key that does nothing) with commas between. */
    fun save(): String = changes.entries.joinToString(",") { (k, v) -> "$k=${v?.name.orEmpty()}" }

    companion object {
        fun load(saved: String?): RemoteBindings = RemoteBindings(buildMap {
            saved.orEmpty().split(',').forEach { part ->
                val code = part.substringBefore('=').toIntOrNull() ?: return@forEach
                val name = part.substringAfter('=', "")
                if (name.isEmpty()) put(code, null)
                else RemoteAction.entries.firstOrNull { it.name == name }?.let { put(code, it) }
            }
        })
    }
}

/** A key's name as the rider knows it from the remote. */
fun remoteKeyName(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> "↑"
    KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
    KeyEvent.KEYCODE_DPAD_LEFT -> "←"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
    KeyEvent.KEYCODE_DPAD_CENTER -> "Center"
    KeyEvent.KEYCODE_ENTER -> "Enter"
    KeyEvent.KEYCODE_NUMPAD_ENTER -> "Num Enter"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_PLUS -> "+"
    KeyEvent.KEYCODE_EQUALS -> "="
    KeyEvent.KEYCODE_MINUS -> "−"
    KeyEvent.KEYCODE_NUMPAD_ADD -> "Num +"
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "Num −"
    else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
}

/** Where the rider is when a button is pressed. */
enum class RemoteLevel {
    /** The map, with nothing chosen. */
    MAP,
    /** A button on the main screen is chosen (the menu rail, the cards, the map's buttons). */
    BUTTONS,
    /** Something that wants an answer is open over the main screen (a rail screen, the trip card). */
    SHEET,
    /** A window of its own on top. */
    DIALOG
}

enum class RemoteCommand {
    NOTHING, PAN_UP, PAN_DOWN, PAN_LEFT, PAN_RIGHT, ZOOM_IN, ZOOM_OUT, OPEN_MENU, LEAVE_MENU, WHATS_HERE, RECENTER,
    MOVE_UP, MOVE_DOWN, MOVE_LEFT, MOVE_RIGHT, CHOOSE, GO_BACK, WHERE_TO, SPEAK_WHERE_TO, ORIENTATION,
    MAP_OR_DASHBOARD, NEXT_TRACK, PREVIOUS_TRACK, PLAY_PAUSE, ANSWER, DECLINE, END_CALL
}

/** What is going on when a button is pressed; see [remoteCommand]. */
data class RemoteSituation(
    val level: RemoteLevel,
    /** The map has been moved away from the rider. */
    val panned: Boolean = false,
    /** A call is ringing, or one is going on. */
    val ringing: Boolean = false,
    val inCall: Boolean = false,
    /** Up and down zoom on the map and left and right open the menu, for remotes with no zoom buttons. */
    val joystickZooms: Boolean = false,
    /** This remote has an OK button; without one, + chooses and − goes back while a button is chosen. */
    val hasOkButton: Boolean = true
)

/**
 * The whole behaviour of the remote in one place. [held] is true for a press held long enough to count
 * as a hold; the arrows and zoom never get one, because holding them just goes on moving or zooming.
 *
 * On the map the arrows move the map and +/− zoom (what other riding apps do), OK opens the menu and
 * Back comes back. With a button chosen the arrows move between buttons, OK presses it and Back leaves.
 */
fun remoteCommand(action: RemoteAction, held: Boolean, s: RemoteSituation): RemoteCommand {
    // A call beats everything except a window that is on top of the card.
    if (s.ringing && s.level != RemoteLevel.DIALOG) when (action) {
        RemoteAction.OK, RemoteAction.CALL -> return RemoteCommand.ANSWER
        RemoteAction.BACK -> return RemoteCommand.DECLINE
        else -> Unit
    }
    if (s.inCall && action == RemoteAction.CALL) return RemoteCommand.END_CALL

    when (action) {
        RemoteAction.WHERE_TO -> return if (s.level == RemoteLevel.DIALOG) RemoteCommand.NOTHING else RemoteCommand.WHERE_TO
        RemoteAction.SPEAK_WHERE_TO -> return if (s.level == RemoteLevel.DIALOG) RemoteCommand.NOTHING else RemoteCommand.SPEAK_WHERE_TO
        RemoteAction.ORIENTATION -> return if (s.level == RemoteLevel.MAP || s.level == RemoteLevel.BUTTONS) RemoteCommand.ORIENTATION else RemoteCommand.NOTHING
        RemoteAction.MAP_OR_DASHBOARD -> return if (s.level == RemoteLevel.DIALOG) RemoteCommand.NOTHING else RemoteCommand.MAP_OR_DASHBOARD
        RemoteAction.NEXT_TRACK -> return RemoteCommand.NEXT_TRACK
        RemoteAction.PREVIOUS_TRACK -> return RemoteCommand.PREVIOUS_TRACK
        RemoteAction.PLAY_PAUSE -> return RemoteCommand.PLAY_PAUSE
        RemoteAction.CALL -> return RemoteCommand.NOTHING
        else -> Unit
    }

    if (s.level == RemoteLevel.MAP) return when (action) {
        RemoteAction.UP -> if (s.joystickZooms) RemoteCommand.ZOOM_IN else RemoteCommand.PAN_UP
        RemoteAction.DOWN -> if (s.joystickZooms) RemoteCommand.ZOOM_OUT else RemoteCommand.PAN_DOWN
        RemoteAction.LEFT -> if (s.joystickZooms) RemoteCommand.OPEN_MENU else RemoteCommand.PAN_LEFT
        RemoteAction.RIGHT -> if (s.joystickZooms) RemoteCommand.OPEN_MENU else RemoteCommand.PAN_RIGHT
        RemoteAction.ZOOM_IN -> RemoteCommand.ZOOM_IN
        RemoteAction.ZOOM_OUT -> RemoteCommand.ZOOM_OUT
        RemoteAction.MENU -> RemoteCommand.OPEN_MENU
        RemoteAction.RECENTER -> if (s.panned) RemoteCommand.RECENTER else RemoteCommand.NOTHING
        RemoteAction.OK -> when {
            held -> RemoteCommand.SPEAK_WHERE_TO
            s.panned -> RemoteCommand.WHATS_HERE
            else -> RemoteCommand.OPEN_MENU
        }
        RemoteAction.BACK -> when {
            held -> RemoteCommand.MAP_OR_DASHBOARD
            s.panned -> RemoteCommand.RECENTER
            else -> RemoteCommand.GO_BACK
        }
        else -> RemoteCommand.NOTHING
    }

    // A button is chosen, or will be at this press.
    return when (action) {
        RemoteAction.UP -> RemoteCommand.MOVE_UP
        RemoteAction.DOWN -> RemoteCommand.MOVE_DOWN
        RemoteAction.LEFT -> RemoteCommand.MOVE_LEFT
        RemoteAction.RIGHT -> RemoteCommand.MOVE_RIGHT
        RemoteAction.OK, RemoteAction.RECENTER -> RemoteCommand.CHOOSE
        RemoteAction.BACK -> if (s.level == RemoteLevel.BUTTONS) RemoteCommand.LEAVE_MENU else RemoteCommand.GO_BACK
        RemoteAction.MENU -> if (s.level == RemoteLevel.BUTTONS) RemoteCommand.LEAVE_MENU else RemoteCommand.GO_BACK
        RemoteAction.ZOOM_IN -> when {
            !s.hasOkButton -> RemoteCommand.CHOOSE
            s.level == RemoteLevel.DIALOG -> RemoteCommand.NOTHING
            else -> RemoteCommand.ZOOM_IN
        }
        RemoteAction.ZOOM_OUT -> when {
            !s.hasOkButton -> if (s.level == RemoteLevel.BUTTONS) RemoteCommand.LEAVE_MENU else RemoteCommand.GO_BACK
            s.level == RemoteLevel.DIALOG -> RemoteCommand.NOTHING
            else -> RemoteCommand.ZOOM_OUT
        }
        else -> RemoteCommand.NOTHING
    }
}
