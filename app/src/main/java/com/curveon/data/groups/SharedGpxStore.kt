package com.curveon.data.groups

import com.curveon.groups.GpxShare
import com.curveon.groups.SharedGpx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Someone shared a GPX group or track, and all of it has come: to save among the rider's GPX tracks, or ignore. */
data class SharedGpxOffer(
    val shareId: String, val name: String, val isGroup: Boolean, val tracks: Int, val km: Int,
    val fromNickname: String?, val groupName: String?, val at: Long
)

/**
 * What has come of shared GPX, in [dir]: the parts of each share until the rider has answered, and which shares they
 * have answered, which the relay may hand over again and which are not offered again. Parts of a share that never
 * got whole go after [KEEP_MS], when the relay has forgotten them too.
 */
class SharedGpxStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val answeredFile = File(dir, "answered.json")
    private val answered = LinkedHashSet<String>()
    private val _offers = MutableStateFlow<List<SharedGpxOffer>>(emptyList())

    /** Oldest first. */
    val offers: StateFlow<List<SharedGpxOffer>> = _offers.asStateFlow()

    init {
        dir.mkdirs()
        runCatching { JSONArray(answeredFile.readText()).let { a -> (0 until a.length()).forEach { answered += a.getString(it) } } }
        val all = dir.listFiles { f -> f.isDirectory }.orEmpty()
        all.filter { now() - it.lastModified() > KEEP_MS }.forEach { it.deleteRecursively() }
        _offers.value = all.filter { it.exists() }.mapNotNull { offerIn(it) }.sortedBy { it.at }
    }

    /** A part of a share that came ([doc]); once it is whole, it is offered. */
    @Synchronized
    fun received(doc: String, fromNickname: String?, groupName: String?) {
        val part = GpxShare.parsePart(doc) ?: return
        if (part.shareId in answered) return
        val share = File(dir, part.shareId).apply { mkdirs() }
        runCatching {
            File(share, "${part.index}.json").writeText(
                JSONObject().put("doc", doc).put("nick", fromNickname ?: JSONObject.NULL).put("gname", groupName ?: JSONObject.NULL).put("at", now()).toString()
            )
        }
        if (_offers.value.none { it.shareId == part.shareId }) offerIn(share)?.let { o -> _offers.value = _offers.value + o }
    }

    /** What was shared, ready to save; null if it cannot be read. */
    fun read(shareId: String): SharedGpx? = GpxShare.unpack(partsIn(File(dir, shareId)).map { it.first })

    /** The rider has answered: its parts go, and it is not offered again. */
    @Synchronized
    fun answered(shareId: String) {
        answered += shareId
        while (answered.size > MAX_ANSWERED) answered.remove(answered.first())
        runCatching { answeredFile.writeText(JSONArray(answered.toList()).toString()) }
        File(dir, shareId).deleteRecursively()
        _offers.value = _offers.value.filterNot { it.shareId == shareId }
    }

    private fun partsIn(share: File): List<Pair<GpxShare.Part, JSONObject>> = share.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching { JSONObject(f.readText()) }.getOrNull()?.let { o -> GpxShare.parsePart(o.optString("doc"))?.let { it to o } }
    }

    /** The offer for [share] when all its parts are here. */
    private fun offerIn(share: File): SharedGpxOffer? {
        val parts = partsIn(share)
        val (first, o) = parts.firstOrNull() ?: return null
        if (parts.map { it.first.index }.toSet() != (0 until first.count).toSet()) return null
        fun text(key: String) = if (o.isNull(key)) null else o.optString(key)
        return SharedGpxOffer(first.shareId, first.name, first.isGroup, first.tracks, first.km, text("nick"), text("gname"), parts.maxOf { it.second.optLong("at") })
    }

    private companion object {
        const val KEEP_MS = 31L * 24 * 3600_000
        const val MAX_ANSWERED = 200
    }
}

/** Names of what a rider already keeps, for what is shared with them. Capitals and spaces at the ends do not make another name. */
object ShareNames {
    private fun norm(s: String) = s.trim().lowercase()

    fun taken(name: String, names: Collection<String>) = names.any { norm(it) == norm(name) }

    /** [name], or if the rider has that, the first of "name (2)", "name (3)" … they do not have. */
    fun free(name: String, names: Collection<String>): String {
        if (!taken(name, names)) return name
        return generateSequence(2) { it + 1 }.map { "${name.trim()} ($it)" }.first { !taken(it, names) }
    }

    /** Whether a shared thing can be saved under [name]. */
    fun usable(name: String, names: Collection<String>) = name.isNotBlank() && !taken(name, names)
}
