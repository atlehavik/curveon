package com.curveon.companion

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.curveon.phone.NavWatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Battery test: how much battery Google Maps and Curveon use on this phone while they navigate.
 * Every [SAMPLE_MS] it writes the battery's charge counter, current, voltage, temperature, the
 * screen and which app is navigating ([NavWatch]) to a CSV file in
 * Android/data/com.curveon.companion/files/batterytest/, and adds up mAh and minutes per app.
 * Time on the charger doesn't count. Runs inside [CompanionService], and carries on after the
 * app's process is restarted.
 *
 * Lines: `S,elapsedMs,wallMs,level,chargeUah,currentUa,voltageMv,tempDeciC,plugged,screenOn,
 * brightness,brightnessAuto,nav` for a sample, `E,elapsedMs,wallMs,text` for an event.
 */
object BatteryTest {

    /** What a part of the test is counted under. */
    enum class Bucket { NONE, GOOGLE_MAPS, CURVEON }

    /**
     * [uah] from the battery's charge counter; [currentUah] from the current drawn, for phones whose
     * counter only moves in big steps (used when the counter hasn't moved at all).
     */
    data class Totals(val ms: Long = 0, val uah: Long = 0, val currentUah: Long = 0, val brightnessMs: Long = 0) {
        val mah: Double get() = (if (uah > 0) uah else currentUah) / 1000.0
        val mahPerHour: Double get() = if (ms < 60_000) Double.NaN else mah / (ms / 3_600_000.0)
        /** Screen brightness (0–255) on average over the time counted. */
        val brightness: Int get() = if (ms == 0L) 0 else (brightnessMs / ms).toInt()
    }

    data class State(
        val running: Boolean = false,
        val file: File? = null,
        /** Wall clock time the test began. */
        val startedAt: Long = 0,
        val totals: Map<Bucket, Totals> = emptyMap(),
        val chargingMs: Long = 0,
        val now: Bucket = Bucket.NONE
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var last: Sample? = null

    private data class Sample(
        val elapsed: Long, val chargeUah: Long, val currentUa: Long, val plugged: Boolean, val brightness: Int, val bucket: Bucket
    )

    fun start(context: Context) {
        val dir = context.getExternalFilesDir("batterytest") ?: return
        dir.mkdirs()
        val file = File(dir, "battery-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv")
        prefs(context).edit().putString(KEY_FILE, file.path).apply()
        CompanionService.start(context) // samples from the service, which keeps the process alive
        resume(context)
    }

    fun stop(context: Context) {
        prefs(context).edit().remove(KEY_FILE).apply()
        handler?.post { event("stopped") }
        thread?.quitSafely()
        thread = null
        handler = null
        NavWatch.onEvent = null
        _state.value = _state.value.copy(running = false)
    }

    /** From [CompanionService]: carry on with a test that was running before the process restarted. */
    fun resume(context: Context) {
        if (thread != null) return
        val path = prefs(context).getString(KEY_FILE, null) ?: return
        val file = File(path)
        val app = context.applicationContext
        val t = HandlerThread("BatteryTest").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            last = null
            _state.value = State(running = true, file = file, startedAt = System.currentTimeMillis())
            if (file.exists()) replay(file) else event("started")
            NavWatch.onEvent = { text -> h.post { event(text) } }
            var lastApp = NavWatch.state.value
            val tick = object : Runnable {
                override fun run() {
                    val nav = NavWatch.state.value
                    if (nav != lastApp) { event("navigating: ${nav.app}"); lastApp = nav }
                    sample(app)
                    h.postDelayed(this, SAMPLE_MS)
                }
            }
            tick.run()
        }
    }

    private fun sample(context: Context) {
        val bm = context.getSystemService(BatteryManager::class.java)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val charge = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        val resolver = context.contentResolver
        val brightness = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(-1)
        val auto = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrDefault(0) == 1
        val nav = NavWatch.state.value
        val elapsed = SystemClock.elapsedRealtime()
        write("S,$elapsed,${System.currentTimeMillis()},$level,$charge,$current,$voltage,$temp,${b(plugged)},${b(screenOn)}," +
            "$brightness,${b(auto)},${nav.app}")
        add(Sample(elapsed, charge, current, plugged, brightness, bucketOf(nav.app)))
    }

    /** Counts the time since the last sample under what was navigating, unless it was charging or changed meanwhile. */
    private fun add(s: Sample) {
        val prev = last
        last = s
        var st = _state.value.copy(now = s.bucket)
        if (prev != null && prev.chargeUah > 0 && s.chargeUah > 0) {
            val dt = s.elapsed - prev.elapsed
            if (prev.plugged || s.plugged) st = st.copy(chargingMs = st.chargingMs + dt)
            else if (prev.bucket == s.bucket && dt in 1..MAX_GAP_MS) {
                val t = st.totals[s.bucket] ?: Totals()
                // The sign of the current differs between phones; on battery it is all drawn.
                val drawnUah = (kotlin.math.abs(prev.currentUa) + kotlin.math.abs(s.currentUa)) / 2 * dt / 3_600_000
                st = st.copy(totals = st.totals + (s.bucket to Totals(t.ms + dt, t.uah + (prev.chargeUah - s.chargeUah),
                    t.currentUah + drawnUah, t.brightnessMs + dt * prev.brightness.coerceAtLeast(0))))
            }
        }
        _state.value = st
    }

    /** Totals of a test that was running when the process restarted, from its file. */
    private fun replay(file: File) {
        file.forEachLine { line ->
            val f = line.split(',')
            if (f.size >= 3 && f[0] == "E" && f.getOrNull(3) == "started") f[2].toLongOrNull()?.let { _state.value = _state.value.copy(startedAt = it) }
            if (f.size >= 13 && f[0] == "S") runCatching {
                add(Sample(f[1].toLong(), f[4].toLong(), f[5].toLong(), f[8] == "1", f[10].toInt(),
                    bucketOf(NavWatch.App.valueOf(f[12]))))
            }
        }
        // The process was gone a while: don't count across the gap.
        last = null
        event("resumed")
    }

    private fun bucketOf(app: NavWatch.App) = when (app) {
        NavWatch.App.NONE -> Bucket.NONE
        NavWatch.App.GOOGLE_MAPS -> Bucket.GOOGLE_MAPS
        NavWatch.App.CURVEON -> Bucket.CURVEON
    }

    private fun event(text: String) = write("E,${SystemClock.elapsedRealtime()},${System.currentTimeMillis()},${text.replace(',', ';')}")

    private fun write(line: String) {
        val f = _state.value.file ?: return
        runCatching { f.appendText(line + "\n") }
    }

    private fun b(v: Boolean) = if (v) 1 else 0

    private fun prefs(context: Context) = context.getSharedPreferences("batterytest", Context.MODE_PRIVATE)

    private const val KEY_FILE = "file"
    private const val SAMPLE_MS = 10_000L
    /** Longer between two samples (the phone slept, the process was gone) and that stretch isn't counted. */
    private const val MAX_GAP_MS = 120_000L
}
