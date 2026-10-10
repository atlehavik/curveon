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
            }
            _state.value = s2
            round++
            delay(POLL_MS)
        }
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
        private const val TAG = "CurveonObd"
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_MS = 200L
        private const val SLOW_EVERY = 25
        private const val RETRY_MS = 10_000L
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
