package com.music.bitchord.data.settings

/**
 * Stream bitrate ceiling and source tier for each connection.
 *
 * [permits] decides which sources may answer before YouTube is used as fallback:
 *
 * - [LOSSLESS] — the user's own addons and JioSaavn both asked.
 * - [HIGH] — addons skipped, JioSaavn asked, then YouTube's best available stream.
 * - [MEDIUM] and [LOW] — YouTube only, with a real bitrate ceiling of [maxKbps].
 *
 * [hourly] estimates the data consumed by an hour of continuous playback.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val hourly: String,
) {
    LOW(64, "Low", "~64 kbps · smallest download", "29 MB/hr"),
    MEDIUM(128, "Medium", "~128 kbps Opus · balanced data use", "58 MB/hr"),
    HIGH(Int.MAX_VALUE, "High", "JioSaavn up to 320kbps, YouTube fallback", "144 MB/hr"),
    LOSSLESS(Int.MAX_VALUE, "Lossless", "Your addons + JioSaavn, bit-exact where available", "300+ MB/hr"),
    ;
}

/** The surface that was last open inside the expanded player. */
enum class LastPlayerScreen {
    MAIN,
    LYRICS,
    QUEUE,
}
