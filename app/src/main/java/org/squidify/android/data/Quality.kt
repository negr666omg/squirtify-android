package org.squidify.android.data

import kotlinx.serialization.Serializable

/** Streaming quality tiers, mirroring squidify.org's original/mp3/opus selector. */
@Serializable
enum class StreamQuality(val label: String) {
    ORIGINAL("Original — source quality"),
    MP3_320("MP3 · 320 kbps"),
    MP3_192("MP3 · 192 kbps"),
    MP3_128("MP3 · 128 kbps"),
    MP3_64("MP3 · 64 kbps (data saver)"),
    OPUS_192("Opus · 192 kbps"),
    OPUS_128("Opus · 128 kbps"),
    OPUS_96("Opus · 96 kbps"),
    OPUS_64("Opus · 64 kbps (data saver)");

    val format: String? get() = when (this) {
        ORIGINAL -> null
        else -> name.split('_').first().lowercase()
    }

    val maxBitRate: Int? get() = when (this) {
        ORIGINAL -> null
        else -> name.split('_')[1].toInt()
    }

    val extension: String get() = when (this) {
        ORIGINAL -> "flac"
        else -> format ?: "mp3"
    }

    val shortLabel: String get() = when (this) {
        ORIGINAL -> "Original"
        else -> "${format?.uppercase()} ${maxBitRate}"
    }
}

/** Quality preference for khinsider downloads/plays. */
@Serializable
enum class KhiQuality(val label: String) {
    BEST("Best available"),
    FLAC("FLAC (lossless)"),
    MP3("MP3");
}
