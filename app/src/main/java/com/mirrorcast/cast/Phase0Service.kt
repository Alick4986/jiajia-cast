// Modified for JiaJia Cast.
package com.mirrorcast.cast

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mirrorcast.MainActivity
import com.mirrorcast.R
import com.mirrorcast.util.DiagLog
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.SocketException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class Phase0Service : Service() {
    private data class Frame(val data: ByteArray, val type: Int, val time: Long)
    private val frames = ArrayBlockingQueue<Frame>(8)
    private val audioFrames = ArrayBlockingQueue<Frame>(6)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var connection = Socket()
    private data class Channel(val input: DataInputStream, val output: DataOutputStream)
    private class ConnectionFailure(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
    private var projection: MediaProjection? = null
    private var source: VideoSource? = null
    private var worker: Thread? = null
    private var savedMediaVolume: Int? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (worker != null) return START_NOT_STICKY
        try {
            @Suppress("DEPRECATION")
            val permission = if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra(PERMISSION, Intent::class.java)
            else intent?.getParcelableExtra<Intent>(PERMISSION)
            val address = intent?.getStringExtra(ADDRESS)
            val width = intent?.getIntExtra(WIDTH, 0) ?: 0
            val height = intent?.getIntExtra(HEIGHT, 0) ?: 0
            val mutePhone = intent?.getBooleanExtra(MUTE_PHONE, false) == true
            val bitrate = intent?.getIntExtra(BITRATE, 3_200_000) ?: 3_200_000
            val frameRate = intent?.getIntExtra(FRAME_RATE, 30)?.takeIf { it in FPS_OPTIONS } ?: 30
            val bufferMs = intent?.getIntExtra(BUFFER_MS, 1000)?.takeIf { it in BUFFER_OPTIONS } ?: 1000
            val options = CastOptions(bufferMs, intent?.getBooleanExtra(FILL_SCREEN, false) == true)
            val manual = intent?.getBooleanExtra(MANUAL, false) == true
            val qualityIndex = intent?.getIntExtra(QUALITY, VideoQuality.CLEAR.ordinal) ?: VideoQuality.CLEAR.ordinal
            require(permission != null && !address.isNullOrBlank() &&
                width in 16..MAX_VIDEO_SIZE && height in 16..MAX_VIDEO_SIZE)
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
            val media = requireNotNull(getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, permission))
            projection = media
            media.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!stopping) stopSelf() }
            }, main)
            state = "正在连接投影仪…"
            worker = Thread({
                send(media, address, width, height, bitrate, frameRate, options, mutePhone, manual, qualityIndex)
            }, "mirror-sender").also { it.start() }
        } catch (error: Exception) {
            DiagLog.log("无法启动投屏：${error.message}")
            state = "无法启动投屏，请重试"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun send(
        media: MediaProjection, address: String, width: Int, height: Int, bitrate: Int,
        frameRate: Int, options: CastOptions, mutePhone: Boolean, manual: Boolean, qualityIndex: Int
    ) {
        var audio: PlaybackAudioCapture? = null
        try {
            val canCaptureAudio = Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val video = VideoSource(media, width, height, frameRate, bitrate, keyIntervalSec = 1f)
            source = video
            if (stopping) return
            if (!video.prepare()) {
                state = video.startupError ?: "H.264 编码器准备失败"
                return
            }
            if (stopping) return
            val modern = openChannel(address, video.width, video.height, canCaptureAudio, options)
            val (input, output) = modern ?: run {
                check(!stopping)
                requireNotNull(openChannel(address, video.width, video.height, canCaptureAudio, null))
            }
            // The receiver has no other messages. EOF lets us stop even on a static screen.
            Thread({
                try { input.read() } catch (_: java.io.IOException) {}
                if (!stopping) {
                    state = "已连上接收端，随后断开"
                    stopSelf()
                }
            }, "mirror-disconnect").start()
            var needsKeyFrame = true
            var videoClockOffset: Long? = null
            video.frameListener = FrameListener { data, key, time ->
                val nowUs = System.nanoTime() / 1_000L
                val offset = videoClockOffset ?: (if (kotlin.math.abs(nowUs - time) > 2_000_000L) {
                    nowUs - time
                } else {
                    0L
                }).also { videoClockOffset = it }
                val timestamp = time + offset
                if (data.size <= MAX_FRAME && (!needsKeyFrame || key)) {
                    if (!frames.offer(Frame(data, if (key) 1 else 0, timestamp))) {
                        frames.clear()
                        needsKeyFrame = true
                        if (key) {
                            frames.offer(Frame(data, 1, timestamp))
                            needsKeyFrame = false
                        } else {
                            video.requestSyncFrame()
                        }
                    } else {
                        needsKeyFrame = false
                    }
                }
            }
            if (stopping) return
            if (!video.start()) {
                state = video.startupError ?: "无法启动屏幕编码"
                return
            }
            if (canCaptureAudio && Build.VERSION.SDK_INT >= 29) {
                val capture = PlaybackAudioCapture(media) { data ->
                    val durationUs = data.size * 20_000L / AUDIO_FRAME_BYTES
                    val frame = Frame(data, FRAME_AUDIO, System.nanoTime() / 1_000L - durationUs)
                    if (!audioFrames.offer(frame)) {
                        audioFrames.poll()
                        audioFrames.offer(frame)
                    }
                }
                if (capture.start()) audio = capture
            }
            state = when {
                audio == null -> "正在投屏（仅画面）"
                !mutePhone -> "正在投屏"
                mutePhoneMedia() -> "正在投屏（仅接收端出声）"
                else -> "正在投屏（请手动调低手机媒体音量）"
            }
            if (modern == null) state += " · 旧版接收端，请升级以应用缓冲和显示设置"
            streamInfo = "${video.width} × ${video.height} · 上限 $frameRate 帧/秒"
            var recorded = false
            var sampleStart = android.os.SystemClock.elapsedRealtime()
            var sentFrames = 0
            while (!stopping && video.running) {
                val sound = audioFrames.poll()
                if (sound != null) writeFrame(output, sound)
                val frame = if (sound != null) frames.poll()
                    else frames.poll(if (audio != null) 12L else 500L, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    writeFrame(output, frame)
                    sentFrames++
                    if (!recorded && frame.type == 1) {
                        recordConnection(address, frameRate, options, mutePhone, manual, qualityIndex)
                        recorded = true
                    }
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - sampleStart >= 1000) {
                    val fps = sentFrames * 1000L / (now - sampleStart)
                    streamInfo = "${video.width} × ${video.height} · 发送 $fps 帧/秒"
                    sampleStart = now
                    sentFrames = 0
                }
            }
        } catch (error: Exception) {
            if (!stopping) {
                DiagLog.log("投屏中断：${error.message}")
                state = if (error is ConnectionFailure) error.message ?: "连接失败"
                else "已连上接收端，随后断开（${error.javaClass.simpleName}）"
            }
        } finally {
            stopping = true
            restorePhoneMedia()
            audio?.close()
            source?.stop()
            try { connection.close() } catch (_: java.io.IOException) {}
            stopSelf()
        }
    }

    private fun recordConnection(
        address: String, frameRate: Int, options: CastOptions, mutePhone: Boolean, manual: Boolean, qualityIndex: Int
    ) {
        val prefs = getSharedPreferences("cast_settings", MODE_PRIVATE)
        val edit = prefs.edit()
            .putString("last_address", address)
            .putString("last_device", receiverLabel)
            .putInt("last_quality", qualityIndex)
            .putInt("last_fps", frameRate)
            .putInt("last_buffer", options.bufferMs)
            .putBoolean("last_fill", options.fillScreen)
            .putBoolean("last_mute", mutePhone)
        if (manual) {
            val previous = prefs.getString("manual_history", "").orEmpty().split(',')
            val recent = (listOf(address) + previous).filter { it.isNotEmpty() }.distinct().take(5)
            edit.putString("manual_history", recent.joinToString(","))
        }
        edit.apply()
    }

    private fun openChannel(
        address: String, width: Int, height: Int, audio: Boolean, options: CastOptions?
    ): Channel? {
        connection.close()
        val socket = Socket()
        connection = socket
        check(!stopping)
        socket.tcpNoDelay = true
        try {
            socket.connect(InetSocketAddress(address, VIDEO_PORT), 4500)
        } catch (error: java.io.IOException) {
            val reason = when (error) {
                is java.net.SocketTimeoutException -> "连接超时"
                is java.net.ConnectException -> "无法建立连接 · ConnectException"
                else -> error.javaClass.simpleName
            }
            throw ConnectionFailure("连不上接收端 $address（$reason）", error)
        }
        socket.soTimeout = 5000
        val protocol = when {
            options != null -> PROTOCOL_WITH_OPTIONS
            audio -> PROTOCOL_WITH_AUDIO
            else -> PROTOCOL
        }
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            output.writeInt(protocol)
            output.writeShort(width)
            output.writeShort(height)
            if (options != null) {
                val flags = (if (audio) FLAG_AUDIO else 0) or (if (options.fillScreen) FLAG_FILL else 0)
                output.writeByte(flags)
                output.writeShort(options.bufferMs)
            }
            output.flush()
            if (input.readInt() != protocol) throw ConnectionFailure("接收端确认回复的协议版本不匹配")
            socket.soTimeout = 0
            return Channel(input, output)
        } catch (error: java.net.SocketTimeoutException) {
            throw ConnectionFailure("已连到接收端，但等待确认超时", error)
        } catch (error: java.io.IOException) {
            if (options != null && (error is EOFException || error is SocketException)) return null
            if (error is ConnectionFailure) throw error
            val reason = if (error is EOFException || error is SocketException) {
                "接收端在确认前关闭了连接，请查看接收页的连接记录"
            } else {
                "已连到接收端，但确认失败（${error.javaClass.simpleName}）"
            }
            throw ConnectionFailure(reason, error)
        }
    }

    private fun writeFrame(output: DataOutputStream, frame: Frame) {
        output.writeInt(frame.data.size)
        output.writeByte(frame.type)
        output.writeLong(frame.time)
        output.write(frame.data)
        output.flush()
    }

    @Synchronized
    private fun mutePhoneMedia(): Boolean {
        if (stopping) return false
        val manager = getSystemService(AudioManager::class.java)
        return try {
            if (manager.isVolumeFixed) return false
            val original = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (original == 0) return true
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            savedMediaVolume = original
            manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        } catch (error: RuntimeException) {
            DiagLog.log("无法静音手机媒体：${error.message}")
            false
        }
    }

    @Synchronized
    private fun restorePhoneMedia() {
        val original = savedMediaVolume ?: return
        savedMediaVolume = null
        val manager = getSystemService(AudioManager::class.java)
        try {
            // If the user raised the volume during the session, keep their choice.
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            }
        } catch (error: RuntimeException) {
            DiagLog.log("无法恢复手机媒体音量：${error.message}")
        }
    }

    override fun onDestroy() {
        stopping = true
        restorePhoneMedia()
        try { connection.close() } catch (_: java.io.IOException) {}
        worker?.interrupt()
        try { projection?.stop() } catch (_: Exception) {}
        if (state.startsWith("正在投屏") || state == "正在连接投影仪…") state = "投屏已结束"
        streamInfo = ""
        active = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mirror", "投屏", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, Phase0Service::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "mirror")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在共享屏幕")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "结束投屏", stop).build())
            .build()
    }

    companion object {
        const val STOP = "com.mirrorcast.STOP"
        const val PERMISSION = "permission"
        const val ADDRESS = "address"
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val BITRATE = "bitrate"
        const val FRAME_RATE = "frame_rate"
        const val BUFFER_MS = "buffer_ms"
        const val FILL_SCREEN = "fill_screen"
        const val MUTE_PHONE = "mute_phone_media"
        const val MANUAL = "manual"
        const val QUALITY = "quality"
        @Volatile var streamInfo = ""
        @Volatile var receiverLabel = ""
        @Volatile var active = false
        @Volatile var state = ""
    }
}
