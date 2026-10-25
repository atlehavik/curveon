package com.curveon.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sin

/** What the engine panel can show; the rider picks which in Settings → Engine. */
enum class ObdValue { FUEL, RPM, COOLANT, VOLTAGE }

/** The adapter link and the latest readings (null = not read yet, or the bike doesn't report it). */
data class ObdState(
    val status: Status = Status.OFF,
    val rpm: Int? = null,
    val fuelPercent: Int? = null,
    /** From the tank table (Settings → Engine → Fuel gauge), once set up. */
    val fuelLiters: Float? = null,
    val coolantC: Int? = null,
    val voltage: Float? = null
) {
    enum class Status { OFF, NO_ADAPTER, CONNECTING, CONNECTED, FAILED }
}

/** A Bluetooth device the tablet is paired with, offered as the OBD adapter. */
data class PairedDevice(val address: String, val name: String)

/**
 * Reads engine data from an ELM327-style OBD2 adapter over Bluetooth (the serial port profile
 * most cheap adapters speak), paired in Android's Bluetooth settings first. Polls RPM often and
 * the slower values now and then; a value the bike answers "NO DATA" to is no longer asked for.
 * [DEMO] instead of an address makes up readings (the emulator has no Bluetooth).
 */
class ObdLink(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state.asStateFlow()

    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private var address: String? = null

    /** The tank table and the signal that follows the fuel level; the fuel reading comes from it once set up. */
    @Volatile private var fuelCalibration = FuelCalibration()
    private val fuelSamples = ArrayDeque<Int>()
    @Volatile private var fuelReadAt = 0L
    @Volatile private var fuelSourceChanged = false

    fun setFuelCalibration(c: FuelCalibration) {
        if (c.source != fuelCalibration.source) fuelSourceChanged = true
        fuelCalibration = c
        if (c.source == null || c.tankLiters == null) _state.update { it.copy(fuelLiters = null) }
    }

    private var pendingCapture: CompletableDeferred<Map<String, Int>?>? = null

    /**
     * Reads everything the bike tells at this moment, for registering a fuel level: the standard
     * engine data it supports and ~4 s of its bus traffic, as [FuelSignal] keys. Takes about ten
     * seconds (the readings on the dashboard pause meanwhile); null when the adapter isn't talking.
     */
    suspend fun capture(): Map<String, Int>? {
        if (_state.value.status != ObdState.Status.CONNECTED) return null
        val request = CompletableDeferred<Map<String, Int>?>()
        pendingCapture = request
        return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { request.await() }.also { if (it == null) pendingCapture = null }
    }

    private val canConnect get() =
        android.os.Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedDevice> {
        if (!canConnect) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.map { PairedDevice(it.address, it.name ?: it.address) } }.getOrDefault(emptyList())
            .sortedBy { it.name.lowercase() }
    }

    /** Connect to [address] (keeps retrying), or stop when null. */
    fun use(address: String?) {
        if (address == this.address && (job?.isActive == true || address == null)) return
        this.address = address
        job?.cancel()
        closeSocket()
        if (address == null) { _state.value = ObdState(); return }
        job = scope.launch(Dispatchers.IO) {
            if (address == DEMO) demo() else while (isActive) {
                try {
                    run(address)
                } catch (e: IOException) {
                    Log.i(TAG, "Link dropped: ${e.message}")
                } catch (e: SecurityException) {
                    Log.w(TAG, "No Bluetooth permission", e)
                }
                closeSocket()
                if (!isActive) break
                _state.update { it.copy(status = ObdState.Status.FAILED) }
                delay(RETRY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun run(address: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled || !canConnect) {
            _state.value = ObdState(ObdState.Status.NO_ADAPTER)
            throw IOException("Bluetooth off or not allowed")
        }
        val device = adapter.getRemoteDevice(address)
        _state.update { it.copy(status = ObdState.Status.CONNECTING) }
        val s = connect(device)
        socket = s
        val elm = Elm327(s.inputStream, s.outputStream)
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) elm.send(cmd, if (cmd == "ATZ") 3_000 else 1_500)
        _state.update { it.copy(status = ObdState.Status.CONNECTED) }
        Log.i(TAG, "Connected to ${device.name}")
        // Once per app start: it holds the readings back for half a minute.
        if (surveyed == null) surveyed = runCatching { survey(elm, device.name ?: address) }.getOrElse { Log.w(TAG, "Survey failed", it); emptyList() }
        val extra = surveyed.orEmpty()
        val unsupported = mutableSetOf<String>()
        var round = 0
        var rpmAt = android.os.SystemClock.elapsedRealtime()
        while (scope.isActive && job?.isActive == true) {
            val slow = round % SLOW_EVERY == 0
            var s2 = _state.value
            // RPM is asked first: "NO DATA" to anything else only means the bike lacks it when the engine answered that
            // (with the ignition off nothing answers), and RPM itself is never given up on, so it comes back with the ignition.
            var engineAnswered = false
            fun ask(pid: String, parse: (List<Int>) -> Unit) {
                if (pid in unsupported) return
                val answer = elm.send(pid, 5_000)
                val bytes = Elm327.parse(pid, answer)
                if (bytes != null) {
                    if (pid == RPM) engineAnswered = true
                    parse(bytes)
                } else if (pid != RPM && engineAnswered && Elm327.notReported(answer)) unsupported += pid
            }
            pendingCapture?.let { request ->
                pendingCapture = null
                request.complete(runCatching { capture(elm) }.getOrElse { if (it is IOException) throw it; Log.w(TAG, "Capture failed", it); null })
            }
            ask(RPM) { b -> if (b.size >= 2) s2 = s2.copy(rpm = (b[0] * 256 + b[1]) / 4) }
            // The engine stopped (or the ignition went off): no revs rather than the last ones, until it answers again.
            if (engineAnswered) rpmAt = android.os.SystemClock.elapsedRealtime()
            else if (android.os.SystemClock.elapsedRealtime() - rpmAt > RPM_GONE_MS) s2 = s2.copy(rpm = null)
            val tankTable = fuelCalibration.takeIf { it.source != null && it.tankLiters != null }
            if (tankTable != null) readFuel(elm, tankTable)?.let { s2 = s2.copy(fuelLiters = it, fuelPercent = (it * 100 / tankTable.tankLiters!!).toInt().coerceIn(0, 100)) }
            if (slow) {
                if (tankTable == null) ask("012F") { b -> if (b.isNotEmpty()) s2 = s2.copy(fuelPercent = (b[0] * 100 + 127) / 255) }
                ask("0105") { b -> if (b.isNotEmpty()) s2 = s2.copy(coolantC = b[0] - 40) }
                Elm327.voltage(elm.send("ATRV", 2_000))?.let { s2 = s2.copy(voltage = it) }
                // Whatever else the bike reports that could tell fuel use, into the ride trace.
                for (pid in extra) com.curveon.data.location.NavTrace.event("obd $pid ${elm.send(pid, 2_000).trim().replace(Regex("\\s+"), " ")}")
                s2.rpm?.let { com.curveon.data.location.NavTrace.event("obd rpm $it coolant ${s2.coolantC} volt ${s2.voltage}") }
            }
            // Only the readings: the status, or a tank table removed meanwhile, may have changed during the round.
            _state.update { cur ->
                val table = fuelCalibration.let { it.source != null && it.tankLiters != null }
                cur.copy(rpm = s2.rpm, fuelPercent = s2.fuelPercent, coolantC = s2.coolantC, voltage = s2.voltage,
                    fuelLiters = if (table) s2.fuelLiters else null)
            }
            round++
            delay(POLL_MS)
        }
    }

    /** See [capture]: runs on the polling loop, since the adapter handles one thing at a time. */
    private fun capture(elm: Elm327): Map<String, Int> {
        val seen = HashMap<String, MutableList<Int>>()
        fun add(prefix: String, bytes: List<Int>) {
            for (i in bytes.indices) {
                seen.getOrPut("$prefix:$i") { ArrayList() } += bytes[i]
                if (i + 1 < bytes.size) seen.getOrPut("$prefix:$i-${i + 1}") { ArrayList() } += bytes[i] * 256 + bytes[i + 1]
            }
        }
        val pids = supportedPids.filter { it.substring(2).toInt(16) % 0x20 != 0 }.take(CAPTURE_MAX_PIDS)
        repeat(3) { for (pid in pids) Elm327.parse(pid, elm.send(pid, 1_500))?.let { add("pid:$pid", it) } }
        // The bus traffic with headers and spaces, so each frame is an id and its bytes.
        elm.send("ATH1", 1_000); elm.send("ATS1", 1_000)
        val traffic = elm.monitor(CAPTURE_BUS_MS)
        elm.send("ATS0", 1_000); elm.send("ATH0", 1_000)
        for (line in traffic.lines()) {
            val frame = Elm327.frame(line) ?: continue
            add("can:${frame.first}", frame.second)
        }
        // Only what held still while reading: a counter or a fast signal can't be the fuel level.
        return seen.mapNotNull { (k, v) ->
            if (v.max() - v.min() > CAPTURE_STEADY) null else k to v.sorted()[v.size / 2]
        }.toMap()
    }

    /** The litres now, from the signal the tank table is set up with; about every 5 s (15 s over the bus, which holds the readings up). */
    private fun readFuel(elm: Elm327, c: FuelCalibration): Float? {
        val src = FuelSignal.parse(c.source ?: return null) ?: return null
        if (fuelSourceChanged) { fuelSourceChanged = false; fuelSamples.clear(); fuelReadAt = 0L }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - fuelReadAt >= if (src.isPid) FUEL_EVERY_MS else FUEL_BUS_EVERY_MS) {
            fuelReadAt = now
            val bytes = if (src.isPid) Elm327.parse(src.id, elm.send(src.id, 2_000)) else {
                elm.send("ATH1", 1_000); elm.send("ATS1", 1_000); elm.send("ATCRA${src.id}", 1_000)
                val text = elm.monitor(1_500) { Elm327.frame(it)?.first == src.id }
                elm.send("ATCRA", 1_000); elm.send("ATS0", 1_000); elm.send("ATH0", 1_000)
                text.lines().firstNotNullOfOrNull { l -> Elm327.frame(l)?.takeIf { it.first == src.id }?.second }
            }
            src.value(bytes ?: emptyList())?.let {
                fuelSamples.addLast(it)
                if (fuelSamples.size > FUEL_SAMPLES) fuelSamples.removeFirst()
            }
        }
        if (fuelSamples.isEmpty()) return null
        return c.liters(fuelSamples.sorted()[fuelSamples.size / 2])
    }

    /**
     * Writes down what this bike's ECU answers, for finding the fuel level and fuel use on bikes
     * that don't give PID 012F (see device-checks.md): the protocol, which mode-01 PIDs it
     * supports, one answer to each, and 20 s of raw bus traffic (the dash may broadcast the fuel
     * gauge). Goes to files/navtrace/obd-*.txt. Returns PIDs worth logging while riding.
     */
    private fun survey(elm: Elm327, name: String): List<String> {
        val dir = context.getExternalFilesDir("navtrace") ?: return emptyList()
        dir.mkdirs()
        val out = StringBuilder()
        fun log(cmd: String, timeout: Long = 3_000): String {
            val a = elm.send(cmd, timeout)
            out.append("> ").append(cmd).append('\n').append(a.trim()).append("\n\n")
            return a
        }
        out.append("Adapter ").append(name).append(", ").append(java.util.Date()).append("\n\n")
        log("ATI"); log("ATDP"); log("ATDPN")
        val supported = mutableListOf<String>()
        var base = 0
        while (base <= 0xC0) {
            val cmd = "01%02X".format(base)
            val bytes = Elm327.parse(cmd, log(cmd)) ?: break
            if (bytes.size < 4) break
            val mask = (bytes[0].toLong() shl 24) or (bytes[1].toLong() shl 16) or (bytes[2].toLong() shl 8) or bytes[3].toLong()
            for (bit in 0 until 32) if (mask and (1L shl (31 - bit)) != 0L) supported += "01%02X".format(base + bit + 1)
            if (mask and 1L == 0L) break
            base += 0x20
        }
        out.append("Supported: ").append(supported.joinToString(" ")).append("\n\n")
        for (pid in supported) if (pid.substring(2).toInt(16) % 0x20 != 0) log(pid)
        log("0902", 5_000) // VIN
        // Raw traffic with headers: listen, then any key stops the monitor.
        log("ATH1")
        out.append("> ATMA (20 s)\n").append(elm.monitor(20_000)).append("\n\n")
        log("ATH0")
        runCatching {
            java.io.File(dir, "obd-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt").writeText(out.toString())
        }
        Log.i(TAG, "Survey: supported ${supported.joinToString(" ")}")
        supportedPids = supported
        return supported.filter { it in FUEL_HINTS }
    }

    private suspend fun demo() {
        _state.value = ObdState(ObdState.Status.CONNECTED, fuelPercent = 72, coolantC = 88, voltage = 13.9f)
        var t = 0.0
        var level = 72.0
        var captures = 0
        while (scope.isActive) {
            t += 0.25
            level = (level - 1 / 120.0).coerceAtLeast(5.0)
            // Registering a refuelling: two readings per level (the second is the control), and the levels
            // alternate between "before" (DEMO_LEVELS, so give the pump litres of tank × (1 − level)) and full.
            pendingCapture?.let { request ->
                pendingCapture = null
                delay(2_000)
                val reading = captures++
                val pair = reading / 2
                level = if (pair % 2 == 0) DEMO_LEVELS[pair / 2 % DEMO_LEVELS.size] else 100.0
                request.complete(mapOf(
                    "can:3F0:2" to (level * 2.55).toInt(), "can:3F0:3" to 17,
                    // A counter (moves between the two readings) and a slow drift (like an odometer).
                    "can:5A0:1" to (reading * 37 % 100), "can:5B0:0-1" to 1_000 + pair * 3,
                    "pid:0105:0" to 128, "pid:0142:0-1" to 13_900
                ))
            }
            val cal = fuelCalibration
            val litres = if (cal.source == "can:3F0:2") cal.liters((level * 2.55).toInt()) else null
            _state.update { it.copy(rpm = (4_500 + 3_000 * sin(t / 3)).toInt(), fuelPercent = level.toInt(), fuelLiters = litres) }
            delay(250)
        }
    }

    /**
     * An encrypted link first, then an unencrypted one: many cheap adapters take only that, some only now and then the
     * encrypted one. Always trying both keeps the engine data coming; the link only carries engine readings.
     */
    @SuppressLint("MissingPermission")
    private fun connect(device: android.bluetooth.BluetoothDevice): BluetoothSocket {
        val secure = device.createRfcommSocketToServiceRecord(SPP)
        try {
            secure.connect()
            return secure
        } catch (e: IOException) {
            runCatching { secure.close() }
            Log.i(TAG, "Encrypted link refused (${e.message}), trying without")
        }
        return device.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        const val DEMO = "demo"
        private var surveyed: List<String>? = null
        private const val TAG = "CurveonObd"
        private const val RPM = "010C"
        /** No answer to RPM for this long: the engine is off. */
        private const val RPM_GONE_MS = 5_000L
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_MS = 200L
        private const val SLOW_EVERY = 25
        private const val RETRY_MS = 10_000L
        private const val CAPTURE_TIMEOUT_MS = 90_000L
        private const val CAPTURE_BUS_MS = 4_000L
        private const val CAPTURE_MAX_PIDS = 40
        /** A signal that moved more than this while being read isn't the fuel level (the float sloshes a little). */
        private const val CAPTURE_STEADY = 4
        private const val FUEL_EVERY_MS = 5_000L
        private const val FUEL_BUS_EVERY_MS = 15_000L
        private const val FUEL_SAMPLES = 5
        private val DEMO_LEVELS = listOf(25.0, 50.0, 12.5, 75.0)
        /** Every mode-01 PID the bike says it supports, from the survey. */
        private var supportedPids: List<String> = emptyList()
        /** Load, MAP, speed, intake temp, MAF, throttle, fuel rate, fuel level, fuel system/trims. */
        private val FUEL_HINTS = setOf("0104", "010B", "010D", "010F", "0110", "0111", "015E", "012F", "0103", "0106", "0107", "0151")
    }
}

/**
 * Talks to an ELM327: a command per line, answers end with a '>' prompt. What the adapter sends is read by a thread
 * of its own, so waiting for an answer sleeps until bytes come instead of looking every few milliseconds.
 */
internal class Elm327(input: java.io.InputStream, private val output: java.io.OutputStream) {

    private val inbox = LinkedBlockingQueue<ByteArray>()
    private var chunk = ByteArray(0)
    private var at = 0

    /** The last command got no prompt in time: its answer may still come, and must not be taken for the next one's. */
    private var late = false

    init {
        thread(name = "curveon-obd-read", isDaemon = true) {
            val buf = ByteArray(512)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) inbox.put(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            inbox.put(CLOSED)
        }
    }

    /** Sends [cmd] and returns the answer (without echo and prompt). */
    fun send(cmd: String, timeoutMs: Long): String {
        skipStale()
        output.write("$cmd\r".toByteArray())
        output.flush()
        val sb = StringBuilder()
        val until = deadline(timeoutMs)
        while (true) {
            val c = next(until)
            if (c < 0) { late = true; break }
            if (c == PROMPT) break
            sb.append(c.toChar())
        }
        // A command sent while the adapter was still busy only stopped that one; what is left of it may still be
        // answered (often "?"), so that is skipped before the next command.
        if (sb.contains("STOPPED")) late = true
        return sb.toString()
    }

    /** Raw bus monitor (after "ATMA"): everything heard for [ms], then stops it and waits for the prompt. */
    fun monitor(ms: Long, stopWhen: ((String) -> Boolean)? = null): String {
        skipStale()
        output.write("ATMA\r".toByteArray())
        output.flush()
        val sb = StringBuilder()
        val until = deadline(ms)
        var lineStart = 0
        while (sb.length < 2_000_000) {
            val c = next(until)
            if (c < 0) break
            sb.append(c.toChar())
            if (c == '\r'.code || c == '\n'.code) {
                if (stopWhen != null && stopWhen(sb.substring(lineStart))) break
                lineStart = sb.length
            }
        }
        output.write("\r".toByteArray())
        output.flush()
        val end = deadline(2_000)
        while (true) {
            val c = next(end)
            if (c < 0) { late = true; break }
            if (c == PROMPT) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Throws away what came unasked: after a timeout, the late answer up to its prompt (if it comes soon), then anything else waiting. */
    private fun skipStale() {
        if (late) {
            val until = deadline(LATE_MS)
            while (true) { val c = next(until); if (c < 0 || c == PROMPT) break }
            late = false
        }
        while (next(System.nanoTime()) >= 0) Unit
    }

    private fun deadline(ms: Long) = System.nanoTime() + ms * 1_000_000

    /** The next byte from the adapter, or -1 when none came by [untilNanos]; throws once the link is closed. */
    private fun next(untilNanos: Long): Int {
        while (at >= chunk.size) {
            val wait = untilNanos - System.nanoTime()
            val got = (if (wait > 0) inbox.poll(wait, TimeUnit.NANOSECONDS) else inbox.poll()) ?: return -1
            if (got === CLOSED) { inbox.put(CLOSED); throw IOException("closed") }
            chunk = got
            at = 0
        }
        return chunk[at++].toInt() and 0xFF
    }

    companion object {
        private val CLOSED = ByteArray(0)
        private const val PROMPT = '>'.code
        /** How long a late answer is waited for before the next command goes anyway. */
        private const val LATE_MS = 1_000L

        /** Whether [answer] says the bike doesn't have this value ("NO DATA", or "?" for a command the adapter doesn't know). */
        fun notReported(answer: String): Boolean = answer.uppercase().contains("NO DATA") || answer.trim() == "?"

        /**
         * The data bytes of a mode-01 answer to [pid] ("010C" → after "41 0C"), or null when the bike
         * doesn't report it ("NO DATA", "?", errors).
         */
        fun parse(pid: String, answer: String): List<Int>? {
            val hex = answer.uppercase().replace("SEARCHING...", "").replace(Regex("[^0-9A-F\n\r]"), "")
            val want = "4" + pid.substring(1)
            val line = hex.lines().map { it.trim() }.firstOrNull { it.startsWith(want) } ?: return null
            val data = line.substring(want.length)
            if (data.isEmpty() || data.length % 2 != 0) return null
            return data.chunked(2).map { it.toInt(16) }
        }

        /**
         * A bus frame from the monitor (headers and spaces on: "3F0 01 02 7F"): the message id and
         * its data bytes, or null for anything else ("BUFFER FULL", "STOPPED", a cut-off line).
         */
        fun frame(line: String): Pair<String, List<Int>>? {
            val parts = line.trim().uppercase().split(Regex("\\s+"))
            if (parts.size < 2) return null
            val id = parts[0]
            if ((id.length != 3 && id.length != 8) || !id.all { it in "0123456789ABCDEF" }) return null
            val data = parts.drop(1)
            if (data.size > 8 || data.any { it.length != 2 || !it.all { c -> c in "0123456789ABCDEF" } }) return null
            return id to data.map { it.toInt(16) }
        }

        /** "12.6V" → 12.6. */
        fun voltage(answer: String): Float? = Regex("(\\d+(?:\\.\\d+)?)\\s*V").find(answer)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
