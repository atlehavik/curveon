package com.curveon.data.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.curveon.BuildConfig
import com.curveon.CrashLog
import com.curveon.phone.RELAY_HOST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** What the rider has written but not sent yet; kept while the app runs, so turning the screen or closing loses nothing. */
data class Draft(val title: String = "", val text: String = "", val images: List<String> = emptyList(), val withCrash: Boolean = true)

/** A report the rider sent, kept on the device so they can follow it on GitHub. */
data class SentReport(val number: Int, val url: String, val title: String, val bug: Boolean, val time: Long)

/** How a sent report stands on GitHub: open, done (closed as completed) or closed otherwise, and how many replies. */
data class ReportStatus(val open: Boolean, val done: Boolean, val replies: Int)

sealed interface SendResult {
    data class Sent(val report: SentReport) : SendResult
    /** The relay's limit: too many reports from this address in the last hour. */
    data object TooMany : SendResult
    data object Failed : SendResult
}

/**
 * Bug reports and ideas: sent to the official relay, which makes a GitHub issue of them (the rider
 * needs no GitHub account), and remembered here with the issue's link. Only on the rider's tap; the
 * pictures are re-encoded first, which also drops a photo's location.
 */
object Feedback {
    const val MAX_IMAGES = 4
    private const val REPO = "atlehavik/curveon"
    private const val MAX_SIDE = 1600
    private const val CRASH_DAYS = 3
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    /** Unsent drafts, one for a bug and one for an idea. */
    val drafts = mutableMapOf<Boolean, Draft>()

    private fun prefs(context: Context) = context.getSharedPreferences("feedback", Context.MODE_PRIVATE)

    /** Newest first. */
    fun sent(context: Context): List<SentReport> {
        val a = runCatching { JSONArray(prefs(context).getString("sent", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            SentReport(it.getInt("n"), it.getString("u"), it.getString("t"), it.optBoolean("b"), it.optLong("at"))
        }.sortedByDescending { it.time }
    }

    private fun remember(context: Context, r: SentReport) {
        val a = JSONArray()
        (listOf(r) + sent(context)).take(30).forEach {
            a.put(JSONObject().put("n", it.number).put("u", it.url).put("t", it.title).put("b", it.bug).put("at", it.time))
        }
        prefs(context).edit().putString("sent", a.toString()).apply()
    }

    /** The start of a crash report from the last few days, for a bug report; null when there is none. */
    fun recentCrash(context: Context): String? = CrashLog.reports(context).firstOrNull()
        ?.takeIf { System.currentTimeMillis() - it.lastModified() < CRASH_DAYS * 86_400_000L }
        ?.let { f -> runCatching { f.readLines().take(30).joinToString("\n").take(2500) }.getOrNull() }

    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    private fun app() = "${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"})"

    suspend fun send(context: Context, bug: Boolean, title: String, text: String, images: List<Uri>, withCrash: Boolean): SendResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject()
                    .put("kind", if (bug) "bug" else "idea")
                    .put("title", title.trim())
                    .put("text", text.trim())
                    .put("app", app())
                    .put("device", device())
                    .put("images", JSONArray(images.take(MAX_IMAGES).map { Base64.encodeToString(jpeg(context, it), Base64.NO_WRAP) }))
                if (bug && withCrash) recentCrash(context)?.let { json.put("log", it) }
                val request = Request.Builder().url("https://$RELAY_HOST/feedback")
                    .post(json.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).execute().use { res ->
                    when {
                        res.code == 429 -> SendResult.TooMany
                        !res.isSuccessful -> SendResult.Failed
                        else -> {
                            val body = JSONObject(res.body!!.string())
                            val report = SentReport(body.getInt("number"), body.getString("url"), title.trim(), bug, System.currentTimeMillis())
                            remember(context, report)
                            SendResult.Sent(report)
                        }
                    }
                }
            }.getOrDefault(SendResult.Failed)
        }

    /** A picture at most [MAX_SIDE] on its long side, as JPEG (no location or other EXIF data left). */
    private fun jpeg(context: Context, uri: Uri): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            if (side > MAX_SIDE) decoder.setTargetSize(info.size.width * MAX_SIDE / side, info.size.height * MAX_SIDE / side)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it); bitmap.recycle() }.toByteArray()
    }

    /** Where a sent report stands, from GitHub's public API; null offline or when GitHub won't say. */
    suspend fun status(report: SentReport): ReportStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("https://api.github.com/repos/$REPO/issues/${report.number}")
                .header("Accept", "application/vnd.github+json").build()
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val o = JSONObject(res.body!!.string())
                ReportStatus(o.optString("state") == "open", o.optString("state_reason") == "completed", o.optInt("comments"))
            }
        }.getOrNull()
    }

    /** When the relay can't be reached: a new issue on GitHub filled in with what the rider wrote (pictures are added there). */
    fun githubUrl(bug: Boolean, title: String, text: String, crash: String?, crashNote: String): String {
        val body = buildString {
            appendLine(text.trim())
            appendLine()
            appendLine("---")
            appendLine("Curveon ${app()} · ${device()}")
            if (crash != null) {
                appendLine()
                appendLine(crashNote)
                appendLine("```")
                appendLine(crash)
                appendLine("```")
            }
        }
        return "https://github.com/$REPO/issues/new?labels=${if (bug) "bug" else "enhancement"}" +
            "&title=${Uri.encode(title.trim())}&body=${Uri.encode(body)}"
    }
}
