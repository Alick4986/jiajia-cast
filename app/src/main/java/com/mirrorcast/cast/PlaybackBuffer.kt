package com.mirrorcast.cast

import java.util.ArrayDeque

/** Maps sender timestamps to one receiver playout clock for both streams. */
internal class PlaybackBuffer(
    bufferMs: Int = 1000,
    private val nowUs: () -> Long = { System.nanoTime() / 1_000L }
) {
    private val bufferUs = bufferMs * 1_000L
    private val maxAheadUs = bufferUs + 750_000L
    private val maxVideoFrames = maxOf(48, (bufferMs + 750) * 65 / 1_000)
    private val maxAudioFrames = maxOf(64, (bufferMs + 750) / 10)
    private val maxVideoBytes = 8 * 1024 * 1024
    data class Packet(val data: ByteArray, val type: Int, val playAtUs: Long) {
        val dispatchAtUs: Long
            get() = if (type == FRAME_AUDIO) playAtUs else playAtUs - DECODE_LEAD_US
    }

    private val lock = Object()
    private val video = ArrayDeque<Packet>()
    private val audio = ArrayDeque<Packet>()
    private var videoBytes = 0
    private var firstVideoUs: Long? = null
    private var offsetUs = 0L
    private var audioOffsetUs: Long? = null
    private var needsKey = true
    private var closed = false

    fun offer(data: ByteArray, type: Int, senderUs: Long) = synchronized(lock) {
        if (closed || senderUs !in 0..Long.MAX_VALUE / 2) return@synchronized
        val now = nowUs()
        if (firstVideoUs == null) {
            if (type != 1) return@synchronized
            firstVideoUs = senderUs
            offsetUs = now + bufferUs - senderUs
        }
        val packetOffset = if (type == FRAME_AUDIO) {
            // Older senders could use different time origins for audio and video.
            audioOffsetUs ?: (if (kotlin.math.abs(senderUs + offsetUs - now - bufferUs) > LEGACY_CLOCK_US) {
                now + bufferUs - senderUs
            } else {
                offsetUs
            }).also { audioOffsetUs = it }
        } else {
            offsetUs
        }
        val packet = Packet(data, type, senderUs + packetOffset)
        if (packet.playAtUs > now + maxAheadUs) return@synchronized
        if (type == FRAME_AUDIO) {
            if (packet.playAtUs >= now - MAX_LATE_US && audio.size < maxAudioFrames) audio.addLast(packet)
        } else {
            if (packet.playAtUs < now - MAX_LATE_US || video.size >= maxVideoFrames ||
                videoBytes + data.size > maxVideoBytes) {
                video.clear()
                videoBytes = 0
                needsKey = true
            }
            if (packet.playAtUs >= now - MAX_LATE_US && (!needsKey || type == 1)) {
                video.addLast(packet)
                videoBytes += data.size
                needsKey = false
            }
        }
        lock.notifyAll()
    }

    fun take(): Packet? {
        synchronized(lock) {
            while (!closed) {
                val now = nowUs()
                while (audio.peekFirst()?.let { it.playAtUs < now - MAX_LATE_US } == true) audio.removeFirst()
                if (video.peekFirst()?.let { it.playAtUs < now - MAX_LATE_US } == true) {
                    removeVideo()
                    while (video.peekFirst()?.type == 0) removeVideo()
                    needsKey = video.isEmpty()
                    continue
                }
                val picture = video.peekFirst()
                val sound = audio.peekFirst()
                val packet = if (picture != null && (sound == null || picture.dispatchAtUs <= sound.dispatchAtUs)) {
                    picture
                } else {
                    sound
                }
                if (packet == null) {
                    lock.wait()
                    continue
                }
                val waitUs = packet.dispatchAtUs - now
                if (waitUs <= 0) return if (packet.type == FRAME_AUDIO) audio.removeFirst() else removeVideo()
                lock.wait(waitUs / 1_000, (waitUs % 1_000).toInt() * 1_000)
            }
            return null
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        video.clear()
        audio.clear()
        videoBytes = 0
        lock.notifyAll()
    }

    private fun removeVideo(): Packet = video.removeFirst().also { videoBytes -= it.data.size }

    private companion object {
        const val DECODE_LEAD_US = 35_000L
        const val MAX_LATE_US = 120_000L
        const val LEGACY_CLOCK_US = 5_000_000L
    }
}
