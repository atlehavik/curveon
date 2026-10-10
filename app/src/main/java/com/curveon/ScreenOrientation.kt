package com.curveon

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import android.view.WindowManager

/** How the screen turns: with the device (Android's auto-rotate), or locked one way for riding. */
enum class ScreenLock(val label: Int) {
    AUTO(R.string.screen_lock_auto), LANDSCAPE(R.string.screen_lock_landscape), PORTRAIT(R.string.screen_lock_portrait);

    companion object {
        /**
         * The exact orientation to lock to. Held that way already: exactly as it is now, so a bump
         * can't flip it upside down either (as Android's own "landscape" may); otherwise the usual
         * way round.
         */
        fun orientationFor(context: Context, lock: ScreenLock): Int {
            if (lock == AUTO) return ActivityInfo.SCREEN_ORIENTATION_USER
            val landscape = lock == LANDSCAPE
            val now = current(context)
            val nowLandscape = now == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || now == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            return when {
                now != null && nowLandscape == landscape -> now
                landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }

        /** The orientation the screen has right now, as an ActivityInfo constant. */
        @Suppress("DEPRECATION")
        private fun current(context: Context): Int? {
            val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: return null
            val landscapeNow = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            // Devices whose natural way round is landscape (some tablets) report 0 while landscape.
            val naturalPortrait = (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) != landscapeNow
            return when (rotation) {
                Surface.ROTATION_0 -> if (naturalPortrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Surface.ROTATION_90 -> if (naturalPortrait) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                Surface.ROTATION_180 -> if (naturalPortrait) ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> if (naturalPortrait) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
}
