package com.mirrorcast.cast

import android.view.Surface
import com.mirrorcast.util.DiagLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

internal const val VIDEO_PORT = 49743
internal const val PROTOCOL = 0x4C4D5231 // LMR1: video only
internal const val PROTOCOL_WITH_AUDIO = 0x4C4D5232 // LMR2: video and PCM audio
internal const val FRAME_AUDIO = 2
internal const val MAX_FRAME = 2 * 1024 * 1024

internal class MirrorReceiver(
    private val surface: Surface,
    private val onVideo: (Int, Int) -> Unit,
    private val onWaiting: () -> Unit,
    private val onFailure: () -> Unit
) {
    private val server = ServerSocket()
    @Volatile private var client: Socket? = null

    fun start() {
        Thread({
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(VIDEO_PORT))
                while (!server.isClosed) {
                    server.accept().use { connection ->
                        client = connection
                        if (!server.isClosed) {
                            try { receive(connection) } catch (error: Exception) {
                                DiagLog.log("投屏连接结束：${error.message}")
                            }
                        }
                        client = null
                    }
                    if (!server.isClosed) onWaiting()
                }
            } catch (error: java.io.IOException) {
                if (!server.isClosed) {
                    DiagLog.log("无法接收投屏：${error.message}")
                    onFailure()
                }
            } finally {
                close()
            }
        }, "mirror-receiver").start()
    }

    private fun receive(connection: Socket) {
        connection.soTimeout = 8000
        val input = DataInputStream(BufferedInputStream(connection.getInputStream()))
        val protocol = input.readInt()
        if (protocol != PROTOCOL && protocol != PROTOCOL_WITH_AUDIO) return
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        if (width !in 16..1280 || height !in 16..1280 || width % 2 != 0 || height % 2 != 0) return
        val sink = VideoSink(width, height, surface)
        sink.onFormatReady = onVideo
        var audio: MirrorAudioPlayer? = null
        try {
            if (!sink.start()) return
            DataOutputStream(connection.getOutputStream()).writeInt(protocol)
            // A static screen may produce no new frames. Closing the socket interrupts readFully().
            connection.soTimeout = 0
            while (!server.isClosed) {
                val length = input.readInt()
                require(length in 1..MAX_FRAME) { "Invalid frame length" }
                val type = input.readUnsignedByte()
                if (type == FRAME_AUDIO) {
                    require(protocol == PROTOCOL_WITH_AUDIO &&
                        length <= AUDIO_FRAME_BYTES && length % 4 == 0) { "Invalid audio frame" }
                } else {
                    require(type == 0 || type == 1) { "Invalid video frame type" }
                }
                val time = input.readLong()
                val data = ByteArray(length)
                input.readFully(data)
                if (type == FRAME_AUDIO) {
                    val player = audio ?: MirrorAudioPlayer().also { audio = it }
                    player.push(data)
                } else {
                    sink.push(data, type == 1, time)
                }
            }
        } finally {
            audio?.close()
            sink.stop()
        }
    }

    fun close() {
        try { server.close() } catch (_: java.io.IOException) {}
        try { client?.close() } catch (_: java.io.IOException) {}
    }
}
