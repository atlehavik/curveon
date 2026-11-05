package com.curveon.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
import com.curveon.R
import com.curveon.data.update.AppUpdates
import com.curveon.data.update.Changelog
import com.curveon.data.update.ChangelogEntry
import com.curveon.data.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * New versions of Curveon on GitHub: looked for by itself and when the rider asks (Settings → Help), downloaded and
 * handed to Android's installer; and what changed, shown once after an update.
 */
class AppUpdateController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SharedPreferences,
    /** The welcome tour has been shown: a rider who never noted a version came from 1.0. */
    private val tourSeen: () -> Boolean,
    /** Waits while Curveon sleeps, so the startup check doesn't run in the background. */
    private val untilAwake: suspend () -> Unit,
) {

    /** Text in the language the rider chose (the app context follows it). */
    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private val appUpdates = AppUpdates(app)
    private val _appUpdate = MutableStateFlow<UpdateState>(UpdateState.Idle)
    /** A newer version on GitHub, and how far downloading and installing it has come. */
    val appUpdate: StateFlow<UpdateState> = _appUpdate.asStateFlow()
    private val _updateAnnounced = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** A newer version was just found by the startup check (once per version): its number. */
    val updateAnnounced: SharedFlow<String> = _updateAnnounced

    /** Starts the startup check, and follows what Android's installer says about a version handed to it. */
    fun start() {
        // A new version on GitHub: looked for soon after start, again every half hour until the
        // server has answered (often offline), then once a day. Developer builds are signed with
        // another key and can't take the release, so they only look when asked (Settings → Help).
        // The Google Play build is updated by Play and doesn't look at all.
        if (!com.curveon.BuildConfig.DEV && com.curveon.BuildConfig.SELF_UPDATE) scope.launch {
            delay(15_000)
            while (true) { untilAwake(); delay(if (checkNow(quiet = true)) 24 * 3_600_000L else 30 * 60_000L) }
        }
        scope.launch {
            AppUpdates.results.collect { r ->
                val release = (_appUpdate.value as? UpdateState.Installing)?.release
                _appUpdate.value = when (r) {
                    AppUpdates.InstallResult.Cancelled -> release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
                    is AppUpdates.InstallResult.Error -> UpdateState.Failed(r.message, release)
                }
            }
        }
    }

    /** Looks for a newer version now (Settings → Help, or [quiet]ly by itself). */
    fun checkForUpdate(quiet: Boolean = false) {
        if (!com.curveon.BuildConfig.SELF_UPDATE) return
        val s = _appUpdate.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading ||
            s is UpdateState.Installing) return
        scope.launch { checkNow(quiet) }
    }

    /** True once the server answered. */
    private suspend fun checkNow(quiet: Boolean): Boolean {
        if (!quiet) _appUpdate.value = UpdateState.Checking
        return runCatching { appUpdates.newer() }.fold(
            onSuccess = { release ->
                if (release == null) {
                    if (_appUpdate.value !is UpdateState.Available || !quiet) _appUpdate.value = UpdateState.UpToDate
                    appUpdates.cleanup()
                } else {
                    _appUpdate.value = UpdateState.Available(release)
                    if (quiet && settings.getString("updateAnnounced", null) != release.version) {
                        settings.edit().putString("updateAnnounced", release.version).apply()
                        _updateAnnounced.tryEmit(release.version)
                    }
                }
                true
            },
            onFailure = {
                if (!quiet) _appUpdate.value = UpdateState.Failed(str(R.string.update_offline), null)
                false
            }
        )
    }

    /** Downloads the new version and hands it to Android's installer, which asks the rider to confirm. */
    fun installUpdate() {
        val release = when (val s = _appUpdate.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        } ?: return
        scope.launch {
            runCatching {
                _appUpdate.value = UpdateState.Downloading(release, 0f)
                val apk = appUpdates.download(release) { f -> _appUpdate.value = UpdateState.Downloading(release, f) }
                _appUpdate.value = UpdateState.Installing(release)
                appUpdates.install(apk)
            }.onFailure {
                _appUpdate.value = UpdateState.Failed(str(
                    if (it is AppUpdates.SignedDifferently) R.string.update_signed_differently else R.string.update_download_failed
                ), release)
            }
        }
    }

    /** What changed, newest version first, in the rider's language. */
    fun changelog(): List<ChangelogEntry> =
        Changelog.read(app, com.curveon.AppLanguage.current(app).tag)

    /**
     * The versions installed since the app last ran, to show once after an update; empty on a
     * fresh install. (1.0 didn't note its version: a rider who has seen the tour came from 1.0.)
     */
    fun takeNewChanges(): List<ChangelogEntry> {
        val current = com.curveon.BuildConfig.VERSION_NAME
        val last = settings.getString("lastVersion", null) ?: if (tourSeen()) "1.0" else current
        settings.edit().putString("lastVersion", current).apply()
        if (!AppUpdates.isNewer(current, last)) return emptyList()
        return Changelog.since(changelog(), last).filterNot { AppUpdates.isNewer(it.version, current) }
    }
}
