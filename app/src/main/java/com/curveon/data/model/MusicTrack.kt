package com.curveon.data.model

/** Now playing on the phone. Album art travels separately (see PhoneCompanionManager.albumArt). */
data class MusicTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Int = 0,
    val currentPositionSeconds: Int = 0,
    val isPlaying: Boolean = false,
    val sourceApp: String = "",
    val artId: String? = null
) {
    val progressFraction: Float
        get() = if (durationSeconds > 0) (currentPositionSeconds.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedCurrentTime: String
        get() = formatTime(currentPositionSeconds)

    val formattedDuration: String
        get() = formatTime(durationSeconds)

    private fun formatTime(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
}
