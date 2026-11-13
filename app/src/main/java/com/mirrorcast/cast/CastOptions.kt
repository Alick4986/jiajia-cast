package com.mirrorcast.cast

internal val BUFFER_OPTIONS = listOf(250, 500, 1000, 2000, 5000)
internal val FPS_OPTIONS = listOf(25, 30, 45, 60)

internal data class CastOptions(val bufferMs: Int = 1000, val fillScreen: Boolean = false)
