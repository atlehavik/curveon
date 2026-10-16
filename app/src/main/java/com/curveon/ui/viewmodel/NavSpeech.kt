package com.curveon.ui.viewmodel

import android.content.Context
import com.curveon.AppLanguage
import com.curveon.R
import com.curveon.data.routing.Cue
import com.curveon.protocol.Message.NavCue
import com.curveon.routing.Maneuver
import com.curveon.routing.ManeuverType
import com.curveon.routing.NavigationProgress
import com.curveon.ui.viewmodel.DashboardViewModel.Companion.describe
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What spoken navigation says for a [Cue], in the language Curveon is shown in, using the same
 * turn texts as the screen: "In 300 metres, turn right onto Stavlandsvegen, then keep left".
 */
class NavSpeech(private val context: Context) {

    /** Null for a turn without [p] (nothing to say it from). */
    fun text(cue: Cue, p: NavigationProgress?, road: String?, useMph: Boolean): String? {
        val onto = road?.let { " " + str(R.string.speak_onto, it) } ?: ""
        return when (cue.sound) {
            NavCue.PREPARE -> {
                p ?: return null
                val m = p.nextManeuver
                val then = p.followingManeuver?.takeIf { it.type != ManeuverType.ARRIVE && it.distanceFromStart - m.distanceFromStart < THEN_M }
                    ?.let { ", " + str(R.string.turn_then, it.describe(context).lowercase()) } ?: ""
                distance(p.distanceToNextManeuver, useMph) + ", " + lowerFirst(m.describe(context)) + onto + then
            }
            NavCue.TURN -> (p ?: return null).nextManeuver.describe(context) + onto
            NavCue.ARRIVE -> str(R.string.speak_arrived)
            NavCue.OFF_ROUTE -> str(R.string.speak_off_route)
            NavCue.BACK_ON_ROUTE -> str(if (cue.newRoute) R.string.speak_new_route else R.string.speak_back_on_route)
            NavCue.CHOOSE -> str(R.string.speak_choose)
        }
    }

    /** Heard when spoken navigation is chosen in Settings. */
    fun sample(useMph: Boolean) =
        distance(300.0, useMph) + ", " + lowerFirst(Maneuver(ManeuverType.RIGHT, 0, 0.0).describe(context))

    /** The voice to ask the text-to-speech engine for. */
    fun language(useMph: Boolean) = when (AppLanguage.current(context)) {
        AppLanguage.NORWEGIAN -> "nb-NO"
        AppLanguage.ENGLISH -> if (useMph) "en-US" else "en-GB"
    }

    /** "In 300 metres": rounded to what is worth saying (10 m close by, 50 m, then 100 m). */
    private fun distance(meters: Double, useMph: Boolean): String {
        if (useMph) {
            val feet = meters * 3.28084
            if (feet < 1000) return str(R.string.speak_in_ft, round(feet, if (feet < 300) 50 else 100))
            return str(R.string.speak_in_mi, "%.1f".format(numbers(), meters / 1609.344))
        }
        val m = round(meters, if (meters < 100) 10 else if (meters < 300) 50 else 100)
        if (m < 1000) return str(R.string.speak_in_m, m)
        return str(R.string.speak_in_km, "%.1f".format(numbers(), meters / 1000))
    }

    /** Decimal comma in Norwegian, so the voice says "komma". */
    private fun numbers() = if (AppLanguage.current(context) == AppLanguage.NORWEGIAN) Locale.forLanguageTag("nb-NO") else Locale.UK

    private fun round(v: Double, step: Int) = maxOf(step, (v / step).roundToInt() * step)

    private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercase() }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private companion object {
        /** A turn this close after the next one is said with it ("then …"), as on the screen. */
        const val THEN_M = 300.0
    }
}
