package com.curveon.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.curveon.BuildConfig
import com.curveon.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A version of Curveon published as a GitHub release. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val bytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val fraction: Float) : UpdateState
    /** Handed to Android's installer, which asks the rider to confirm. */
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
}

/**
 * New versions of the app from the GitHub releases page (while Curveon isn't in an app store):
 * looks for a newer release, downloads its APK and hands it to Android's package installer, which
 * asks the rider to confirm (and, the first time, to allow Curveon to install apps).
 */
class AppUpdates(private val context: Context) {

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val dir get() = File(context.cacheDir, "updates")

    /** The newest release, when it is newer than this build; null when this build is the newest. Throws when offline. */
    suspend fun newer(): AppRelease? = withContext(Dispatchers.IO) {
        val json = http.newCall(
            Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
        ).execute().use { r ->
            check(r.isSuccessful) { "GitHub: HTTP ${r.code}" }
            JSONObject(r.body!!.string())
        }
        val version = json.getString("tag_name").removePrefix("v")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
        // The tablet app is "Curveon-1.1.apk"; the phone's companion sits beside it.
        val apk = apks.firstOrNull { APK_NAME.matches(it.getString("name")) }
            ?: apks.firstOrNull { !it.getString("name").contains("Companion", ignoreCase = true) }
            ?: return@withContext null
        AppRelease(version, plainText(json.optString("body")), apk.getString("browser_download_url"), apk.optLong("size"))
    }

    /** Downloads [release]'s APK into the cache; [onProgress] gets 0..1. */
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, "Curveon-${release.version}.apk")
        if (file.length() > 0 && file.length() == release.bytes) return@withContext file
        val part = File(dir, file.name + ".part")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            check(r.isSuccessful) { "GitHub: HTTP ${r.code}" }
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.bytes
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        check(part.renameTo(file))
        file
    }

    /** Hands [apk] to Android's installer; the outcome comes back on [results]. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("curveon.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            // Mutable: the installer adds the status to it.
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    /** Old downloads, once they are installed (or a newer one is out). */
    fun cleanup() { dir.listFiles()?.forEach { it.delete() } }

    /** What the installer said: null when installed (the app restarts), true when the rider said no, else the error. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit
                PackageInstaller.STATUS_FAILURE_ABORTED -> _results.tryEmit(InstallResult.Cancelled)
                else -> {
                    android.util.Log.w("AppUpdates", "install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    _results.tryEmit(InstallResult.Error(context.getString(
                        if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                            R.string.update_signed_differently else R.string.update_failed
                    )))
                }
            }
        }
    }

    sealed interface InstallResult {
        data object Cancelled : InstallResult
        data class Error(val message: String) : InstallResult
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/atlehavik/curveon/releases/latest"
        private val APK_NAME = Regex("Curveon-[0-9][0-9.]*\\.apk")

        private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)
        val results: SharedFlow<InstallResult> = _results

        /** Release notes are Markdown: without its marks, and bullets as bullets. */
        fun plainText(markdown: String) = markdown.replace("\r", "").lines().joinToString("\n") { line ->
            line.replace(Regex("^\\s*[-*] "), "• ").replace(Regex("^#+\\s*"), "").replace("**", "").replace("`", "")
        }.replace(Regex("\n{3,}"), "\n\n").trim()

        /** "1.10" is newer than "1.9", and "1.1" than "1.0.3". */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}
