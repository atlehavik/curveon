package com.example.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.example.data.model.MusicTrack
import com.example.data.model.NotificationItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.*

enum class CompanionConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SIMULATED_ACTIVE
}

class PhoneCompanionManager(
    private val scope: CoroutineScope
) {
    private val _connectionState = MutableStateFlow(CompanionConnectionState.SIMULATED_ACTIVE)
    val connectionState: StateFlow<CompanionConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow("Pixel 8 Pro (Intercom Paired)")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val sampleTracks = listOf(
        MusicTrack(
            title = "Riders on the Storm",
            artist = "The Doors",
            album = "L.A. Woman",
            durationSeconds = 434,
            currentPositionSeconds = 142,
            isPlaying = true,
            sourceApp = "Spotify",
            volumePercent = 85
        ),
        MusicTrack(
            title = "Born to Be Wild",
            artist = "Steppenwolf",
            album = "Steppenwolf",
            durationSeconds = 210,
            currentPositionSeconds = 45,
            isPlaying = true,
            sourceApp = "Spotify",
            volumePercent = 90
        ),
        MusicTrack(
            title = "Highway to Hell",
            artist = "AC/DC",
            album = "Highway to Hell",
            durationSeconds = 208,
            currentPositionSeconds = 72,
            isPlaying = true,
            sourceApp = "YouTube Music",
            volumePercent = 88
        ),
        MusicTrack(
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            durationSeconds = 244,
            currentPositionSeconds = 30,
            isPlaying = true,
            sourceApp = "Apple Music",
            volumePercent = 82
        )
    )

    private var currentTrackIndex = 0

    private val _currentTrack = MutableStateFlow(sampleTracks[0])
    val currentTrack: StateFlow<MusicTrack> = _currentTrack.asStateFlow()

    private val _notifications = MutableStateFlow(
        listOf(
            NotificationItem(
                id = "n_1",
                appName = "WhatsApp",
                senderName = "Sarah Connor",
                senderInitials = "SC",
                message = "The mountain pass is clear! Meeting at the lookout in 20 min ☕",
                timestampMs = System.currentTimeMillis() - 120_000
            ),
            NotificationItem(
                id = "n_2",
                appName = "SMS",
                senderName = "Marcus Moto Club",
                senderInitials = "MM",
                message = "Fuel stop at Mile 45. 98 Octane available.",
                timestampMs = System.currentTimeMillis() - 480_000
            )
        )
    )
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    init {
        startProgressTicker()
    }

    private fun startProgressTicker() {
        scope.launch {
            while (isActive) {
                delay(1000)
                val track = _currentTrack.value
                if (track.isPlaying && track.currentPositionSeconds < track.durationSeconds) {
                    _currentTrack.value = track.copy(
                        currentPositionSeconds = track.currentPositionSeconds + 1
                    )
                }
            }
        }
    }

    fun togglePlayPause() {
        val current = _currentTrack.value
        val newState = !current.isPlaying
        _currentTrack.value = current.copy(isPlaying = newState)
        sendCommand(if (newState) "CMD_MEDIA_PLAY" else "CMD_MEDIA_PAUSE")
    }

    fun skipNext() {
        currentTrackIndex = (currentTrackIndex + 1) % sampleTracks.size
        _currentTrack.value = sampleTracks[currentTrackIndex].copy(isPlaying = true)
        sendCommand("CMD_MEDIA_NEXT")
    }

    fun skipPrevious() {
        currentTrackIndex = if (currentTrackIndex > 0) currentTrackIndex - 1 else sampleTracks.size - 1
        _currentTrack.value = sampleTracks[currentTrackIndex].copy(isPlaying = true)
        sendCommand("CMD_MEDIA_PREV")
    }

    fun dismissNotification(id: String) {
        _notifications.value = _notifications.value.filter { it.id != id }
    }

    fun sendQuickReply(notificationId: String, replyText: String) {
        // Simulates quick canned reply through the phone's SMS / WhatsApp notification action
        dismissNotification(notificationId)
        sendCommand("CMD_REPLY_NOTIFICATION:$notificationId:$replyText")
    }

    fun triggerSimulatedNotification(appName: String, sender: String, message: String, isCall: Boolean = false) {
        val newNotif = NotificationItem(
            id = "sim_${System.currentTimeMillis()}",
            appName = appName,
            senderName = sender,
            senderInitials = sender.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2).uppercase(),
            message = message,
            timestampMs = System.currentTimeMillis(),
            isIncomingCall = isCall
        )
        _notifications.value = listOf(newNotif) + _notifications.value
    }

    private fun sendCommand(command: String) {
        scope.launch(Dispatchers.IO) {
            try {
                outputStream?.write((command + "\n").toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (_: Exception) {}
        }
    }
}
