package com.mirrorcast.cast

import kotlin.math.roundToInt

internal fun findEncoderSize(
    width: Int, height: Int, widthAlignment: Int, heightAlignment: Int,
    supported: (Int, Int) -> Boolean
): Pair<Int, Int>? {
    val longEdge = maxOf(width, height)
    val alignWidth = maxOf(2, widthAlignment)
    val alignHeight = maxOf(2, heightAlignment)
    var previous: Pair<Int, Int>? = null
    for (percent in 100 downTo 50) {
        val scale = percent / 100.0
        val w = (width * scale / alignWidth).roundToInt() * alignWidth
        val h = (height * scale / alignHeight).roundToInt() * alignHeight
        val candidate = w to h
        if (candidate != previous && w in 16..longEdge && h in 16..longEdge && supported(w, h)) return candidate
        previous = candidate
    }
    return null
}
