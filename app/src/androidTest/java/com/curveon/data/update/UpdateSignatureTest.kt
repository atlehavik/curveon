package com.curveon.data.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On a real device: a new version signed like the installed app passes the check before Android's installer, so
 * updating from GitHub keeps working. The installed APK itself stands in for it.
 */
@RunWith(AndroidJUnit4::class)
class UpdateSignatureTest {
    @Test
    fun anApkSignedLikeThisAppPasses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val copy = File(context.cacheDir, "self-copy.apk")
        File(context.applicationInfo.sourceDir).copyTo(copy, overwrite = true)
        try {
            assertTrue(AppUpdates(context).signedLikeThisApp(copy))
        } finally {
            copy.delete()
        }
    }
}
