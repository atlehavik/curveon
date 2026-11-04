package com.curveon.data.remote

import android.view.KeyEvent
import com.curveon.data.remote.RemoteAction.*
import com.curveon.data.remote.RemoteCommand as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {
    private val none = RemoteBindings()
    private fun map(panned: Boolean = false, zooms: Boolean = false) = RemoteSituation(RemoteLevel.MAP, panned = panned, joystickZooms = zooms)
    private val buttons = RemoteSituation(RemoteLevel.BUTTONS)
    private val sheet = RemoteSituation(RemoteLevel.SHEET)
    private val dialog = RemoteSituation(RemoteLevel.DIALOG)

    @Test fun `the keys of the common remotes work with no setup`() {
        // DMD Remote 2 and Thork: arrows, Enter, Esc, F6/F7. Carpe Iter: +, −, A. BarButtons: C, N. SilverFox: =, −, C.
        assertEquals(UP, none.actionFor(KeyEvent.KEYCODE_DPAD_UP, true))
        assertEquals(OK, none.actionFor(KeyEvent.KEYCODE_ENTER, true))
        assertEquals(OK, none.actionFor(KeyEvent.KEYCODE_DPAD_CENTER, true))
        assertEquals(BACK, none.actionFor(KeyEvent.KEYCODE_ESCAPE, true))
        assertEquals(ZOOM_IN, none.actionFor(KeyEvent.KEYCODE_F6, true))
        assertEquals(ZOOM_OUT, none.actionFor(KeyEvent.KEYCODE_F7, true))
        assertEquals(ZOOM_IN, none.actionFor(KeyEvent.KEYCODE_PLUS, true))
        assertEquals(ZOOM_IN, none.actionFor(KeyEvent.KEYCODE_EQUALS, true))
        assertEquals(ZOOM_OUT, none.actionFor(KeyEvent.KEYCODE_MINUS, true))
        assertEquals(MENU, none.actionFor(KeyEvent.KEYCODE_N, true))
        assertEquals(RECENTER, none.actionFor(KeyEvent.KEYCODE_C, true))
        assertEquals(NEXT_TRACK, none.actionFor(KeyEvent.KEYCODE_MEDIA_NEXT, true))
    }

    @Test fun `the device's own keys keep their meaning`() {
        assertNull(none.actionFor(KeyEvent.KEYCODE_BACK, fromRemote = false))
        assertNull(none.actionFor(KeyEvent.KEYCODE_MEDIA_NEXT, fromRemote = false))
        assertNull(none.actionFor(KeyEvent.KEYCODE_VOLUME_UP, fromRemote = false))
        assertEquals(BACK, none.actionFor(KeyEvent.KEYCODE_BACK, fromRemote = true))
        // Volume stays Android's until the rider gives a remote's volume key another job.
        assertNull(none.actionFor(KeyEvent.KEYCODE_VOLUME_UP, fromRemote = true))
        val changed = none.with(KeyEvent.KEYCODE_VOLUME_UP, ZOOM_IN)
        assertEquals(ZOOM_IN, changed.actionFor(KeyEvent.KEYCODE_VOLUME_UP, fromRemote = true))
        assertNull(changed.actionFor(KeyEvent.KEYCODE_VOLUME_UP, fromRemote = false))
    }

    @Test fun `Home and Power are never taken`() {
        val b = none.with(KeyEvent.KEYCODE_HOME, OK).with(KeyEvent.KEYCODE_POWER, OK)
        assertNull(b.actionFor(KeyEvent.KEYCODE_HOME, true))
        assertNull(b.actionFor(KeyEvent.KEYCODE_POWER, true))
    }

    @Test fun `a key gets a new job, and a job can lose its keys`() {
        val b = none.with(KeyEvent.KEYCODE_F9, UP).with(KeyEvent.KEYCODE_ENTER, ZOOM_IN)
        assertEquals(UP, b.actionFor(KeyEvent.KEYCODE_F9, true))
        assertEquals(ZOOM_IN, b.actionFor(KeyEvent.KEYCODE_ENTER, true))
        assertTrue(KeyEvent.KEYCODE_F9 in b.keysFor(UP))
        assertTrue(KeyEvent.KEYCODE_ENTER !in b.keysFor(OK))
        val without = b.without(UP)
        assertTrue(without.keysFor(UP).isEmpty())
        assertNull(without.actionFor(KeyEvent.KEYCODE_DPAD_UP, true))
    }

    @Test fun `changes survive being saved`() {
        val b = none.with(KeyEvent.KEYCODE_F9, UP).without(MENU)
        val back = RemoteBindings.load(b.save())
        assertEquals(b.changes, back.changes)
        assertEquals(UP, back.actionFor(KeyEvent.KEYCODE_F9, true))
        assertNull(back.actionFor(KeyEvent.KEYCODE_N, true))
        assertEquals(emptyMap<Int, RemoteAction?>(), RemoteBindings.load(null).changes)
        assertEquals(emptyMap<Int, RemoteAction?>(), RemoteBindings.load("nonsense,=,12=NO_SUCH").changes)
    }

    @Test fun `on the map the arrows move it and zoom zooms, as in other riding apps`() {
        assertEquals(C.PAN_UP, remoteCommand(UP, false, map()))
        assertEquals(C.PAN_RIGHT, remoteCommand(RIGHT, false, map()))
        assertEquals(C.ZOOM_IN, remoteCommand(ZOOM_IN, false, map()))
        assertEquals(C.ZOOM_OUT, remoteCommand(ZOOM_OUT, false, map()))
    }

    @Test fun `a remote with no zoom buttons zooms with up and down`() {
        assertEquals(C.ZOOM_IN, remoteCommand(UP, false, map(zooms = true)))
        assertEquals(C.ZOOM_OUT, remoteCommand(DOWN, false, map(zooms = true)))
        assertEquals(C.OPEN_MENU, remoteCommand(LEFT, false, map(zooms = true)))
        assertEquals(C.OPEN_MENU, remoteCommand(RIGHT, false, map(zooms = true)))
    }

    @Test fun `OK and Back on the map depend on where it is, and on a hold`() {
        assertEquals(C.OPEN_MENU, remoteCommand(OK, false, map()))
        assertEquals(C.WHATS_HERE, remoteCommand(OK, false, map(panned = true)))
        assertEquals(C.SPEAK_WHERE_TO, remoteCommand(OK, true, map()))
        assertEquals(C.GO_BACK, remoteCommand(BACK, false, map()))
        assertEquals(C.RECENTER, remoteCommand(BACK, false, map(panned = true)))
        assertEquals(C.MAP_OR_DASHBOARD, remoteCommand(BACK, true, map()))
    }

    @Test fun `with a button chosen the arrows go between buttons, and there is always a way out`() {
        assertEquals(C.MOVE_DOWN, remoteCommand(DOWN, false, buttons))
        assertEquals(C.CHOOSE, remoteCommand(OK, false, buttons))
        assertEquals(C.LEAVE_MENU, remoteCommand(BACK, false, buttons))
        assertEquals(C.LEAVE_MENU, remoteCommand(MENU, false, buttons))
        assertEquals(C.GO_BACK, remoteCommand(BACK, false, sheet))
        assertEquals(C.GO_BACK, remoteCommand(BACK, false, dialog))
        assertEquals(C.GO_BACK, remoteCommand(MENU, false, dialog))
    }

    @Test fun `a remote with no OK button chooses with plus and goes back with minus`() {
        val noOk = buttons.copy(hasOkButton = false)
        assertEquals(C.CHOOSE, remoteCommand(ZOOM_IN, false, noOk))
        assertEquals(C.LEAVE_MENU, remoteCommand(ZOOM_OUT, false, noOk))
        assertEquals(C.GO_BACK, remoteCommand(ZOOM_OUT, false, dialog.copy(hasOkButton = false)))
        // With an OK button they go on zooming, and in a window of its own there is nothing to zoom.
        assertEquals(C.ZOOM_IN, remoteCommand(ZOOM_IN, false, buttons))
        assertEquals(C.NOTHING, remoteCommand(ZOOM_IN, false, dialog))
    }

    @Test fun `a ringing call beats everything but a window on top`() {
        val ringing = map().copy(ringing = true, inCall = true)
        assertEquals(C.ANSWER, remoteCommand(OK, false, ringing))
        assertEquals(C.DECLINE, remoteCommand(BACK, false, ringing))
        assertEquals(C.ANSWER, remoteCommand(OK, false, sheet.copy(ringing = true)))
        assertEquals(C.CHOOSE, remoteCommand(OK, false, dialog.copy(ringing = true)))
        // Answered: the arrows are the map's again, and only the call button ends it.
        val talking = map().copy(inCall = true)
        assertEquals(C.OPEN_MENU, remoteCommand(OK, false, talking))
        assertEquals(C.GO_BACK, remoteCommand(BACK, false, talking))
        assertEquals(C.END_CALL, remoteCommand(CALL, false, talking))
    }

    @Test fun `music keys work anywhere`() {
        listOf(map(), buttons, sheet, dialog).forEach {
            assertEquals(C.NEXT_TRACK, remoteCommand(NEXT_TRACK, false, it))
            assertEquals(C.PLAY_PAUSE, remoteCommand(PLAY_PAUSE, false, it))
        }
    }
}
