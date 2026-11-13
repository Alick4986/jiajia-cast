package com.mirrorcast.cast

import kotlin.math.roundToInt

internal enum class VideoQuality(val label: String, val longEdge: Int, val bitrate: Int) {
    SMOOTH("流畅", 960, 1_800_000),
    CLEAR("清晰", 1280, 3_200_000),
    HD("高清", MAX_VIDEO_SIZE, 6_000_000);

    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, longEdge.toDouble() / maxOf(width, height, 1))
        fun aligned(pixels: Int) = ((pixels * scale / 8).roundToInt() * 8).coerceIn(16, longEdge)
        return aligned(width) to aligned(height)
    }
}
