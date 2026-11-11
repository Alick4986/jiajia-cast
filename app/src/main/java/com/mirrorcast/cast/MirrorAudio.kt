package com.mirrorcast.cast

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import com.mirrorcast.util.DiagLog
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

internal const val AUDIO_FRAME_BYTES = 3840 // 20 ms of 48 kHz, stereo, PCM 16-bit
private const val SAMPLE_RATE = 48_000
private const val PCM = AudioFormat.ENCODING_PCM_16BIT

private fun audioFormat(channels: Int): AudioFormat =
    AudioFormat.Builder().setEncoding(PCM).setSampleRate(SAMPLE_RATE).setChannelMask(channels).build()

/** Captures only playback explicitly allowed by the source app and Android. */
@TargetApi(29)
internal class PlaybackAudioCapture(
    private val projection: MediaProjection,
    private val onAudio: (ByteArray) -> Unit
) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var stopping = false

    fun start(): Boolean {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, PCM)
            check(bufferSize > 0)
            val input = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat(AudioFormat.CHANNEL_IN_STEREO))
                .setBufferSizeInBytes(maxOf(bufferSize, AUDIO_FRAME_BYTES * 3))
                .build()
            record = input
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            worker = Thread({ read(input) }, "mirror-audio-capture").also { it.start() }
            return true
        } catch (error: Exception) {
            DiagLog.log("无法获取应用声音：${error.message}")
            close()
            return false
        }
    }

    private fun read(input: AudioRecord) {
        try {
            val buffer = ByteBuffer.allocateDirect(AUDIO_FRAME_BYTES)
            while (!stopping) {
                buffer.clear()
                val count = input.read(buffer, AUDIO_FRAME_BYTES, AudioRecord.READ_BLOCKING)
                if (count <= 0) break
                if (!stopping) {
                    val data = ByteArray(count)
                    buffer.get(data)
                    onAudio(data)
                }
            }
        } catch (error: Exception) {
            if (!stopping) DiagLog.log("应用声音采集中断：${error.message}")
        }
    }

    fun close() {
        stopping = true
        val input = record ?: return
        record = null
        try { input.stop() } catch (_: Exception) {}
        try { worker?.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        input.release()
    }
}

/** Playback runs separately so a slow speaker does not block video reception. */
internal class MirrorAudioPlayer {
    private val queue = ArrayBlockingQueue<ByteArray>(6)
    private val worker = Thread(::play, "mirror-audio-playback").also { it.start() }

    fun push(data: ByteArray) {
        if (!queue.offer(data)) {
            queue.poll()
            queue.offer(data)
        }
    }

    private fun play() {
        var output: AudioTrack? = null
        try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, PCM)
            check(bufferSize > 0)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
                .setAudioFormat(audioFormat(AudioFormat.CHANNEL_OUT_STEREO))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(bufferSize, AUDIO_FRAME_BYTES * 3))
                .build()
            output = track
            track.play()
            val buffer = ByteBuffer.allocateDirect(AUDIO_FRAME_BYTES)
            while (!Thread.currentThread().isInterrupted) {
                buffer.clear()
                buffer.put(queue.take())
                buffer.flip()
                while (buffer.hasRemaining() && !Thread.currentThread().isInterrupted) {
                    val count = track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                    if (count < 0) return
                    if (count == 0) Thread.sleep(4)
                }
            }
        } catch (_: InterruptedException) {
            // The session ended.
        } catch (error: Exception) {
            DiagLog.log("投影仪无法播放投屏声音：${error.message}")
        } finally {
            try { output?.pause(); output?.flush() } catch (_: Exception) {}
            output?.release()
        }
    }

    fun close() {
        worker.interrupt()
        queue.clear()
        try { worker.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
