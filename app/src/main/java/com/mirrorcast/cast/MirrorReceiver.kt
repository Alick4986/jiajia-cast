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
internal const val PROTOCOL_WITH_OPTIONS = 0x4C4D5233 // LMR3: sender buffer and screen fit
internal const val FLAG_AUDIO = 1
internal const val FLAG_FILL = 2
internal const val FRAME_AUDIO = 2
internal const val MAX_FRAME = 2 * 1024 * 1024
internal const val MAX_VIDEO_SIZE = 1920

internal class MirrorReceiver(
    private val surface: Surface,
    private val onVideo: (Int, Int) -> Unit,
    private val onWaiting: () -> Unit,
    private val onFailure: () -> Unit,
    private val onReady: () -> Unit = {},
    private val onConnected: (CastOptions) -> Unit = {},
    private val onRequestStatus: (String) -> Unit = {}
) {
    private val server = ServerSocket()
    @Volatile private var client: Socket? = null

    fun start() {
        Thread({
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(VIDEO_PORT))
                onReady()
                while (!server.isClosed) {
                    server.accept().use { connection ->
                        client = connection
                        if (!server.isClosed) {
                            onRequestStatus("已收到连接请求")
                            try {
                                receive(connection)
                            } catch (error: Exception) {
                                DiagLog.log("投屏连接结束：${error.message}")
                                if (!server.isClosed) {
                                    onRequestStatus(when (error) {
                                        is java.net.SocketTimeoutException -> "已收到请求，等待参数超时"
                                        is java.io.EOFException -> "已收到请求，发送端已关闭连接"
                                        else -> "已收到请求，连接中断（${error.javaClass.simpleName}）"
                                    })
                                }
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
        if (protocol != PROTOCOL && protocol != PROTOCOL_WITH_AUDIO && protocol != PROTOCOL_WITH_OPTIONS) {
            return onRequestStatus("已收到请求，协议版本不支持")
        }
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        if (width !in 16..MAX_VIDEO_SIZE || height !in 16..MAX_VIDEO_SIZE || width % 2 != 0 || height % 2 != 0) {
            return onRequestStatus("已收到请求，画面尺寸不支持（${width}×$height）")
        }
        val flags = if (protocol == PROTOCOL_WITH_OPTIONS) input.readUnsignedByte() else 0
        val options = if (protocol == PROTOCOL_WITH_OPTIONS) {
            val delayMs = input.readUnsignedShort()
            if (delayMs !in BUFFER_OPTIONS || flags and (FLAG_AUDIO or FLAG_FILL).inv() != 0) {
                return onRequestStatus("已收到请求，投屏设置无效")
            }
            CastOptions(delayMs, flags and FLAG_FILL != 0)
        } else {
            CastOptions()
        }
        val hasAudio = protocol == PROTOCOL_WITH_AUDIO || flags and FLAG_AUDIO != 0
        val sink = VideoSink(width, height, surface)
        sink.onFormatReady = onVideo
        val playback = PlaybackBuffer(options.bufferMs)
        val worker = Thread({
            var audio: MirrorAudioPlayer? = null
            try {
                while (true) {
                    val packet = playback.take() ?: break
                    if (packet.type == FRAME_AUDIO) {
                        val player = audio ?: MirrorAudioPlayer().also { audio = it }
                        player.push(packet.data)
                    } else {
                        sink.push(packet.data, packet.type == 1, packet.playAtUs)
                    }
                }
            } finally {
                audio?.close()
            }
        }, "mirror-playout")
        try {
            if (!sink.start()) return
            worker.start()
            DataOutputStream(connection.getOutputStream()).writeInt(protocol)
            onRequestStatus("已确认连接，等待音画")
            onConnected(options)
            // A static screen may produce no new frames. Closing the socket interrupts readFully().
            connection.soTimeout = 0
            while (!server.isClosed) {
                val length = input.readInt()
                require(length in 1..MAX_FRAME) { "Invalid frame length" }
                val type = input.readUnsignedByte()
                if (type == FRAME_AUDIO) {
                    require(hasAudio &&
                        length <= AUDIO_FRAME_BYTES && length % 4 == 0) { "Invalid audio frame" }
                } else {
                    require(type == 0 || type == 1) { "Invalid video frame type" }
                }
                val time = input.readLong()
                val data = ByteArray(length)
                input.readFully(data)
                playback.offer(data, type, time)
            }
        } finally {
            playback.close()
            try {
                worker.join()
            } finally {
                sink.stop()
            }
        }
    }

    fun close() {
        try { server.close() } catch (_: java.io.IOException) {}
        try { client?.close() } catch (_: java.io.IOException) {}
    }
}
