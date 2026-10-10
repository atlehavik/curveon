package com.curveon.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import kotlin.math.sin

/** What the engine panel can show; the rider picks which in Settings → Engine. */
enum class ObdValue { FUEL, RPM, COOLANT, VOLTAGE }

/** The adapter link and the latest readings (null = not read yet, or the bike doesn't report it). */
data class ObdState(
    val status: Status = Status.OFF,
    val rpm: Int? = null,
    val fuelPercent: Int? = null,
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
                _state.value = _state.value.copy(status = ObdState.Status.FAILED)
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
        _state.value = _state.value.copy(status = ObdState.Status.CONNECTING)
        val s = runCatching { device.createRfcommSocketToServiceRecord(SPP).also { it.connect() } }
            .getOrElse { device.createInsecureRfcommSocketToServiceRecord(SPP).also { s -> s.connect() } }
        socket = s
        val elm = Elm327(s.inputStream, s.outputStream)
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) elm.send(cmd, if (cmd == "ATZ") 3_000 else 1_500)
        _state.value = _state.value.copy(status = ObdState.Status.CONNECTED)
        Log.i(TAG, "Connected to ${device.name}")
        // Once per app start: it holds the readings back for half a minute.
        if (surveyed == null) surveyed = runCatching { survey(elm, device.name ?: address) }.getOrElse { Log.w(TAG, "Survey failed", it); emptyList() }
        val extra = surveyed.orEmpty()
        val unsupported = mutableSetOf<String>()
        var round = 0
        while (scope.isActive && job?.isActive == true) {
            val slow = round % SLOW_EVERY == 0
            var s2 = _state.value
            fun ask(pid: String, parse: (List<Int>) -> Unit) {
                if (pid in unsupported) return
                val bytes = Elm327.parse(pid, elm.send(pid, 5_000))
                if (bytes == null) unsupported += pid else parse(bytes)
            }
            ask("010C") { b -> if (b.size >= 2) s2 = s2.copy(rpm = (b[0] * 256 + b[1]) / 4) }
            if (slow) {
                ask("012F") { b -> if (b.isNotEmpty()) s2 = s2.copy(fuelPercent = (b[0] * 100 + 127) / 255) }
                ask("0105") { b -> if (b.isNotEmpty()) s2 = s2.copy(coolantC = b[0] - 40) }
                Elm327.voltage(elm.send("ATRV", 2_000))?.let { s2 = s2.copy(voltage = it) }
                // Whatever else the bike reports that could tell fuel use, into the ride trace.
                for (pid in extra) com.curveon.data.location.NavTrace.event("obd $pid ${elm.send(pid, 2_000).trim().replace(Regex("\\s+"), " ")}")
                s2.rpm?.let { com.curveon.data.location.NavTrace.event("obd rpm $it coolant ${s2.coolantC} volt ${s2.voltage}") }
            }
            _state.value = s2
            round++
            delay(POLL_MS)
        }
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
        return supported.filter { it in FUEL_HINTS }
    }

    private suspend fun demo() {
        _state.value = ObdState(ObdState.Status.CONNECTED, fuelPercent = 72, coolantC = 88, voltage = 13.9f)
        var t = 0.0
        while (scope.isActive) {
            t += 0.25
            val fuel = (72 - t / 120).toInt().coerceAtLeast(5)
            _state.value = _state.value.copy(rpm = (4_500 + 3_000 * sin(t / 3)).toInt(), fuelPercent = fuel)
            delay(250)
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        const val DEMO = "demo"
        private var surveyed: List<String>? = null
        private const val TAG = "CurveonObd"
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_MS = 200L
        private const val SLOW_EVERY = 25
        private const val RETRY_MS = 10_000L
        /** Load, MAP, speed, intake temp, MAF, throttle, fuel rate, fuel level, fuel system/trims. */
        private val FUEL_HINTS = setOf("0104", "010B", "010D", "010F", "0110", "0111", "015E", "012F", "0103", "0106", "0107", "0151")
    }
}

/** Talks to an ELM327: a command per line, answers end with a '>' prompt. */
internal class Elm327(private val input: java.io.InputStream, private val output: java.io.OutputStream) {

    /** Sends [cmd] and returns the answer (without echo and prompt). */
    fun send(cmd: String, timeoutMs: Long): String {
        output.write("$cmd\r".toByteArray())
        output.flush()
        val sb = StringBuilder()
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("closed")
                if (c.toChar() == '>') break
                sb.append(c.toChar())
            } else Thread.sleep(10)
        }
        return sb.toString()
    }

    /** Raw bus monitor (after "ATMA"): everything heard for [ms], then stops it and waits for the prompt. */
    fun monitor(ms: Long): String {
        output.write("ATMA\r".toByteArray())
        output.flush()
        val sb = StringBuilder()
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && sb.length < 2_000_000) {
            if (input.available() > 0) sb.append(input.read().toChar()) else Thread.sleep(5)
        }
        output.write("\r".toByteArray())
        output.flush()
        val end = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < end) {
            if (input.available() > 0) { val c = input.read(); if (c < 0 || c.toChar() == '>') break; sb.append(c.toChar()) } else Thread.sleep(5)
        }
        return sb.toString()
    }

    companion object {
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

        /** "12.6V" → 12.6. */
        fun voltage(answer: String): Float? = Regex("(\\d+(?:\\.\\d+)?)\\s*V").find(answer)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
