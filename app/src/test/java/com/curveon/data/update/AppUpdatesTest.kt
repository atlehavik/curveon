package com.curveon.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.curveon.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdatesTest {

    @Test
    fun `versions compare by number, not as text`() {
        assertTrue(AppUpdates.isNewer("1.1", "1.0"))
        assertTrue(AppUpdates.isNewer("1.10", "1.9"))
        assertTrue(AppUpdates.isNewer("1.1", "1.0.3"))
        assertTrue(AppUpdates.isNewer("2.0", "1.12"))
        assertFalse(AppUpdates.isNewer("1.0", "1.0"))
        assertFalse(AppUpdates.isNewer("1.0.0", "1.0"))
        assertFalse(AppUpdates.isNewer("1.0", "1.1"))
    }

    @Test
    fun `changelog has this version in both languages, newest first`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val en = Changelog.read(context, norwegian = false)
        val nb = Changelog.read(context, norwegian = true)
        assertEquals(BuildConfig.VERSION_NAME, en.first().version)
        assertEquals(en.map { it.version }, nb.map { it.version })
        en.zip(nb).forEach { (e, n) -> assertEquals("lines in ${e.version}", e.changes.size, n.changes.size) }
        en.zipWithNext().forEach { (newer, older) -> assertTrue(AppUpdates.isNewer(newer.version, older.version)) }
        assertEquals(listOf("1.1"), Changelog.since(en, "1.0").map { it.version })
    }

    @Test
    fun `release notes lose their Markdown`() {
        assertEquals("Intro\n\n• Curveon-1.0.apk: the app (com.curveon.tablet)\nNotes",
            AppUpdates.plainText("## Intro\r\n\n\n- **Curveon-1.0.apk**: the app (`com.curveon.tablet`)\nNotes\n"))
    }
}
