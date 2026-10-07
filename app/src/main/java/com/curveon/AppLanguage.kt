package com.curveon

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

/**
 * The language the app is shown in, chosen in Settings. Android keeps the choice per app (so it
 * also shows under the system's App languages) and restarts the screen in the new language.
 */
enum class AppLanguage(val tag: String, /** Always in its own language, so the rider can find it. */ val label: String) {
    ENGLISH("en", "English"),
    NORWEGIAN("nb", "Norsk");

    companion object {
        /** Per-app languages need Android 13; older devices follow the system language. */
        val canChoose = Build.VERSION.SDK_INT >= 33

        fun current(context: Context): AppLanguage {
            val chosen = if (canChoose) context.getSystemService(LocaleManager::class.java).applicationLocales else LocaleList.getEmptyLocaleList()
            val locale = if (chosen.isEmpty) Resources.getSystem().configuration.locales[0] else chosen[0]
            return if (locale.language in setOf("nb", "no", "nn")) NORWEGIAN else ENGLISH
        }

        fun set(context: Context, language: AppLanguage) {
            if (!canChoose || current(context) == language) return
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        }
    }
}
