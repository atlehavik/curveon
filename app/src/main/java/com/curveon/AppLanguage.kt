package com.curveon

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language the app is shown in, chosen in Settings. Android keeps the choice per app (so it
 * also shows under the system's App languages) and restarts the screen in the new language.
 */
enum class AppLanguage(
    val tag: String,
    /** Always in its own language, so the rider can find it. */ val label: String,
    /** The voice for spoken directions; also how numbers are said (decimal comma or point). */ val speech: String,
) {
    ENGLISH("en", "English", "en-GB"),
    NORWEGIAN("nb", "Norsk", "nb-NO"),
    SWEDISH("sv", "Svenska", "sv-SE"),
    DANISH("da", "Dansk", "da-DK"),
    FINNISH("fi", "Suomi", "fi-FI"),
    ICELANDIC("is", "Íslenska", "is-IS"),
    GERMAN("de", "Deutsch", "de-DE"),
    SPANISH("es", "Español", "es-ES"),
    FRENCH("fr", "Français", "fr-FR"),
    DUTCH("nl", "Nederlands", "nl-NL"),
    POLISH("pl", "Polski", "pl-PL");

    val speechLocale: Locale get() = Locale.forLanguageTag(speech)

    companion object {
        /** Per-app languages need Android 13; older devices follow the system language. */
        val canChoose = Build.VERSION.SDK_INT >= 33

        fun current(context: Context): AppLanguage {
            val chosen = if (canChoose) context.getSystemService(LocaleManager::class.java).applicationLocales else LocaleList.getEmptyLocaleList()
            val locale = if (chosen.isEmpty) Resources.getSystem().configuration.locales[0] else chosen[0]
            return of(locale.language)
        }

        /** The app language for an ISO language code; English when Curveon doesn't speak it. */
        fun of(language: String) = when (language) {
            "no", "nn" -> NORWEGIAN
            else -> entries.firstOrNull { it.tag == language } ?: ENGLISH
        }

        fun set(context: Context, language: AppLanguage) {
            if (!canChoose || current(context) == language) return
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        }
    }
}
