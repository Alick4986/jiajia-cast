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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class Phase0Service : Service() {
    private data class Frame(val data: ByteArray, val type: Int, val time: Long)
    private val frames = ArrayBlockingQueue<Frame>(8)
    private val audioFrames = ArrayBlockingQueue<Frame>(6)
    private val main = Handler(Looper.getMainLooper())
    private val connection = Socket()
    private var projection: MediaProjection? = null
    private var source: VideoSource? = null
    private var worker: Thread? = null
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
            require(permission != null && !address.isNullOrBlank() && width in 16..1280 && height in 16..1280)
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
            worker = Thread({ send(media, address, width, height) }, "mirror-sender").also { it.start() }
        } catch (error: Exception) {
            DiagLog.log("无法启动投屏：${error.message}")
            state = "无法启动投屏，请重试"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun send(media: MediaProjection, address: String, width: Int, height: Int) {
        var audio: PlaybackAudioCapture? = null
        try {
            val canCaptureAudio = Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val protocol = if (canCaptureAudio) PROTOCOL_WITH_AUDIO else PROTOCOL
            connection.tcpNoDelay = true
            connection.connect(InetSocketAddress(address, VIDEO_PORT), 4500)
            connection.soTimeout = 5000
            val output = DataOutputStream(BufferedOutputStream(connection.getOutputStream()))
            output.writeInt(protocol)
            output.writeShort(width)
            output.writeShort(height)
            output.flush()
            val input = DataInputStream(connection.getInputStream())
            check(input.readInt() == protocol)
            connection.soTimeout = 0
            // The receiver has no other messages. EOF lets us stop even on a static screen.
            Thread({
                try { input.read() } catch (_: java.io.IOException) {}
                if (!stopping) {
                    state = "投影仪已断开"
                    stopSelf()
                }
            }, "mirror-disconnect").start()
            val video = VideoSource(media, width, height, 25, 4_000_000, keyIntervalSec = 1f)
            var needsKeyFrame = true
            video.frameListener = FrameListener { data, key, time ->
                if (data.size <= MAX_FRAME && (!needsKeyFrame || key)) {
                    if (!frames.offer(Frame(data, if (key) 1 else 0, time))) {
                        frames.clear()
                        needsKeyFrame = true
                        if (key) {
                            frames.offer(Frame(data, 1, time))
                            needsKeyFrame = false
                        } else {
                            video.requestSyncFrame()
                        }
                    } else {
                        needsKeyFrame = false
                    }
                }
            }
            source = video
            if (stopping) return
            if (!video.start()) {
                state = "手机无法启动屏幕编码"
                return
            }
            if (canCaptureAudio && Build.VERSION.SDK_INT >= 29) {
                val capture = PlaybackAudioCapture(media) { data ->
                    val frame = Frame(data, FRAME_AUDIO, System.nanoTime() / 1000L)
                    if (!audioFrames.offer(frame)) {
                        audioFrames.poll()
                        audioFrames.offer(frame)
                    }
                }
                if (capture.start()) audio = capture
            }
            state = if (audio != null) "正在投屏" else "正在投屏（仅画面）"
            while (!stopping && video.running) {
                val sound = audioFrames.poll()
                if (sound != null) writeFrame(output, sound)
                val frame = if (sound != null) frames.poll()
                    else frames.poll(if (audio != null) 12L else 500L, TimeUnit.MILLISECONDS)
                if (frame != null) writeFrame(output, frame)
            }
        } catch (error: Exception) {
            if (!stopping) {
                DiagLog.log("投屏中断：${error.message}")
                state = "连接失败，请确认投影仪正在接收"
            }
        } finally {
            stopping = true
            audio?.close()
            source?.stop()
            try { connection.close() } catch (_: java.io.IOException) {}
            stopSelf()
        }
    }

    private fun writeFrame(output: DataOutputStream, frame: Frame) {
        output.writeInt(frame.data.size)
        output.writeByte(frame.type)
        output.writeLong(frame.time)
        output.write(frame.data)
        output.flush()
    }

    override fun onDestroy() {
        stopping = true
        try { connection.close() } catch (_: java.io.IOException) {}
        worker?.interrupt()
        try { projection?.stop() } catch (_: Exception) {}
        if (state.startsWith("正在投屏") || state == "正在连接投影仪…") state = "投屏已结束"
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
        @Volatile var active = false
        @Volatile var state = ""
    }
}
