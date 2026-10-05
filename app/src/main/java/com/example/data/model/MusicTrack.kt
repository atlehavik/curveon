package com.example.data.model

data class MusicTrack(
    val title: String = "Riders on the Storm",
    val artist: String = "The Doors",
    val album: String = "L.A. Woman",
    val albumArtUrl: String? = null,
    val albumArtLocalRes: Int? = null,
    val durationSeconds: Int = 434,
    val currentPositionSeconds: Int = 142,
    val isPlaying: Boolean = true,
    val sourceApp: String = "Spotify (via Bluetooth)",
    val volumePercent: Int = 85
) {
    val progressFraction: Float
        get() = if (durationSeconds > 0) (currentPositionSeconds.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedCurrentTime: String
        get() = formatTime(currentPositionSeconds)

    val formattedDuration: String
        get() = formatTime(durationSeconds)

    private fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }
}
