package com.curveon

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports kept on the device, one file each under `Android/data/<package>/files/crashes/`
 * (`adb pull` reaches it on release builds too, which have no run-as). Kotlin crashes are written
 * as they happen; native crashes (the map's renderer, GPU drivers) and "not responding" can't be
 * caught in the app, so they're copied from Android's own record of why the app last ended, on the
 * next start. Kept small and plain so the files can later be sent on as they are.
 */
object CrashLog {
    private const val KEEP = 30
    @Volatile private var installed = false

    fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "crashes")

    /** The reports, newest first. */
    fun reports(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.name }.orEmpty()

    /** From the first screen or service to start: catch Kotlin crashes, and collect what Android saw since last time. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                write(app, System.currentTimeMillis(), "crash", "Thread: ${thread.name}", trace)
            }
            previous?.uncaughtException(thread, e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Thread({ runCatching { collectExits(app) } }, "CrashLog").start()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun collectExits(context: Context) {
        val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        val since = prefs.getLong("lastExitRead", 0L)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.timestamp > since }
        exits.forEach { exit ->
            val kind = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                ApplicationExitInfo.REASON_ANR -> "anr"
                else -> return@forEach // Kotlin crashes are written by the handler, with their stack
            }
            val trace = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
            val details = buildString {
                appendLine("Reason: ${exit.description ?: "-"}")
                appendLine("On screen: ${exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND}")
            }
            when {
                trace == null -> write(context, exit.timestamp, kind, details, "(Android kept no trace)")
                // Native: the crashing thread's stack, read from Android's tombstone; the whole
                // tombstone (all threads, the log) goes next to it as .pb.
                kind == "native" -> write(context, exit.timestamp, kind, details,
                    runCatching { Tombstone.summary(trace) }.getOrElse { "(Tombstone unreadable: $it)" }, trace)
                else -> write(context, exit.timestamp, kind, details, String(trace))
            }
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("lastExitRead", it).apply() }
    }

    private fun write(context: Context, time: Long, kind: String, details: String, trace: String, tombstone: ByteArray? = null) {
        val dir = dir(context).apply { mkdirs() }
        val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(time)) + "-$kind"
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        File(dir, "$name.txt").writeText(buildString {
            appendLine("Curveon ${version ?: "?"} (${if (BuildConfig.DEBUG) "debug" else "release"}), ${context.packageName}")
            appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(time)))
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Kind: $kind")
            append(details)
            appendLine()
            append(trace)
        })
        tombstone?.let { File(dir, "$name.pb").writeBytes(it) }
        // Only the latest few; a crash loop mustn't fill the device.
        dir.listFiles()?.groupBy { it.nameWithoutExtension }?.toSortedMap(compareByDescending { it })?.values
            ?.drop(KEEP)?.flatten()?.forEach { it.delete() }
    }
}

/**
 * Just enough of Android's tombstone protobuf (system/core/debuggerd/proto/tombstone.proto) to
 * print the signal and the crashing thread's backtrace, like logcat's crash dump.
 */
private object Tombstone {
    private class Field(val number: Int, val varint: Long, val bytes: ByteArray?) {
        val text get() = bytes?.let { String(it) } ?: ""
    }

    private fun fields(b: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl shift); shift += 7; if (x < 0x80) return r }
        }
        while (i < b.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += Field(number, varint(), null)
                1 -> { out += Field(number, 0, b.copyOfRange(i, i + 8)); i += 8 }
                2 -> { val n = varint().toInt(); out += Field(number, 0, b.copyOfRange(i, i + n)); i += n }
                5 -> { out += Field(number, 0, b.copyOfRange(i, i + 4)); i += 4 }
                else -> error("wire type ${key and 7}")
            }
        }
        return out
    }

    fun summary(pb: ByteArray): String = buildString {
        val top = fields(pb)
        val tid = top.firstOrNull { it.number == 6 }?.varint
        top.firstOrNull { it.number == 2 }?.let { appendLine("Build: ${it.text}") }
        top.firstOrNull { it.number == 20 }?.let { appendLine("Process uptime: ${it.varint}s") }
        top.firstOrNull { it.number == 10 }?.bytes?.let { sig ->
            val f = fields(sig)
            fun v(n: Int) = f.firstOrNull { it.number == n }
            append("Signal ${v(1)?.varint} (${v(2)?.text}), code ${v(3)?.varint} (${v(4)?.text})")
            v(9)?.let { append(", fault addr 0x${it.varint.toULong().toString(16)}") }
            appendLine()
        }
        top.firstOrNull { it.number == 14 }?.let { appendLine("Abort message: ${it.text}") }
        top.filter { it.number == 15 }.forEach { cause -> fields(cause.bytes!!).firstOrNull { it.number == 1 }?.let { appendLine("Cause: ${it.text}") } }
        // threads: map<uint32, Thread>, each entry key = 1, value = 2; the crashing one has the tid.
        val thread = top.filter { it.number == 16 }.map { fields(it.bytes!!) }
            .firstOrNull { e -> e.firstOrNull { it.number == 1 }?.varint == tid }?.firstOrNull { it.number == 2 }?.bytes?.let(::fields)
            ?: return@buildString
        appendLine()
        appendLine("Thread ${tid}: ${thread.firstOrNull { it.number == 2 }?.text}")
        thread.filter { it.number == 7 }.forEach { appendLine("  note: ${it.text}") }
        thread.filter { it.number == 4 }.forEachIndexed { n, frame ->
            val f = fields(frame.bytes!!)
            fun v(k: Int) = f.firstOrNull { it.number == k }
            append("  #%02d pc %016x  %s".format(n, v(1)?.varint ?: 0L, v(6)?.text ?: ""))
            v(4)?.text?.takeIf { it.isNotEmpty() }?.let { append(" ($it+${v(5)?.varint ?: 0})") }
            appendLine()
        }
    }
}
