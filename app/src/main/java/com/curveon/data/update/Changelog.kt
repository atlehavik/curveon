package com.curveon.data.update

import android.content.Context
import org.json.JSONArray

/** What changed in one version, in the rider's language. */
data class ChangelogEntry(val version: String, val changes: List<String>)

/** What's new per version, from assets/changelog.json (in the app, so it is there offline). Newest first. */
object Changelog {

    fun read(context: Context, norwegian: Boolean): List<ChangelogEntry> = runCatching {
        val a = JSONArray(context.assets.open("changelog.json").bufferedReader().use { it.readText() })
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val lines = o.optJSONArray(if (norwegian) "nb" else "en") ?: o.getJSONArray("en")
            ChangelogEntry(o.getString("version"), (0 until lines.length()).map { lines.getString(it) })
        }
    }.getOrDefault(emptyList())

    /** The versions newer than [since] (all when null). */
    fun since(entries: List<ChangelogEntry>, since: String?) =
        entries.filter { since == null || AppUpdates.isNewer(it.version, since) }
}
