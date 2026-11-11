// Modified for JiaJia Cast.
package com.mirrorcast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mirrorcast.cast.MirrorReceiver
import com.mirrorcast.cast.Phase0Service
import com.mirrorcast.util.Discovery
import java.net.DatagramSocket
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class Screen { HOME, SEND, RECEIVE }
    private var screen = Screen.HOME
    private val handler = Handler(Looper.getMainLooper())
    private var search: DatagramSocket? = null
    private var advertisement: DatagramSocket? = null
    private var wifiLock: WifiManager.MulticastLock? = null
    private var receiver: MirrorReceiver? = null
    private var receiverSurface: Surface? = null
    private var selectedAddress: String? = null
    private var devices = emptyList<Discovery.Device>()
    private var senderStatus: TextView? = null
    private var deviceList: LinearLayout? = null
    private var shownState = ""
    private var shownActive = false
    private var generation = 0

    private val updateSender = object : Runnable {
        override fun run() {
            if (screen != Screen.SEND) return
            val state = Phase0Service.state
            val active = Phase0Service.active
            if (shownState != state || shownActive != active) {
                shownActive = active
                shownState = state
                showDevices()
            }
            handler.postDelayed(this, 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Phase0Service.active) showSender() else showHome()
    }

    private fun showHome() {
        leaveScreen()
        screen = Screen.HOME
        val panel = panel()
        panel.addView(label(getString(R.string.app_name), 32, true))
        panel.addView(label("手机和投影仪连接同一个 Wi-Fi。", 17))
        val receive = action("接收投屏") { showReceiver() }
        val send = action("发送投屏") { showSender() }
        panel.addView(send)
        panel.addView(receive)
        if (packageManager.hasSystemFeature("android.software.leanback")) receive.requestFocus()
        else send.requestFocus()
    }

    private fun showSender() {
        leaveScreen()
        screen = Screen.SEND
        val panel = panel()
        panel.addView(label("发送投屏", 30, true))
        senderStatus = label("", 17).also(panel::addView)
        if (Build.VERSION.SDK_INT >= 29) {
            panel.addView(label("允许「麦克风」权限后，可同时共享应用声音。", 14))
        }
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(panel::addView)
        panel.addView(action("返回") { showHome() })
        shownState = Phase0Service.state
        shownActive = Phase0Service.active
        showDevices()
        val current = generation
        try {
            search = Discovery.search { results ->
                runOnUiThread {
                    if (generation == current && devices != results) {
                        devices = results
                        if (!Phase0Service.active && selectedAddress == null) showDevices()
                    }
                }
            }
        } catch (_: java.io.IOException) {
            senderStatus?.text = "无法搜索，请检查 Wi-Fi 连接"
        }
        handler.post(updateSender)
    }

    private fun showDevices() {
        val list = deviceList ?: return
        list.removeAllViews()
        if (Phase0Service.active) {
            senderStatus?.text = Phase0Service.state.ifBlank { "正在准备投屏…" }
            list.addView(action("结束投屏") {
                stopService(Intent(this, Phase0Service::class.java))
            })
            return
        }
        senderStatus?.text = Phase0Service.state.ifBlank {
            if (devices.isEmpty()) "请在投影仪上打开「接收投屏」。" else "选择要投屏的设备。"
        }
        for (device in devices) {
            list.addView(action(device.name) { requestCapture(device.address.hostAddress ?: return@action) })
        }
    }

    @Suppress("DEPRECATION")
    private fun requestCapture(address: String) {
        if (selectedAddress != null || Phase0Service.active) return
        selectedAddress = address
        Phase0Service.state = ""
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PLAYBACK_PERMISSION)
        } else {
            launchCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == PLAYBACK_PERMISSION && selectedAddress != null && screen == Screen.SEND) {
            launchCapture()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchCapture() {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            startActivityForResult(intent, CAPTURE)
        } catch (_: Exception) {
            selectedAddress = null
            senderStatus?.text = "无法打开安卓屏幕共享授权"
        }
    }

    @Deprecated("Uses the platform capture authorization activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE) return
        val address = selectedAddress
        selectedAddress = null
        if (resultCode != RESULT_OK || data == null || address == null) {
            senderStatus?.text = "已取消屏幕共享"
            return
        }
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val scale = minOf(1.0, 1280.0 / maxOf(metrics.widthPixels, metrics.heightPixels, 1))
        val width = ((metrics.widthPixels * scale).roundToInt() / 16 * 16).coerceAtLeast(16)
        val height = ((metrics.heightPixels * scale).roundToInt() / 16 * 16).coerceAtLeast(16)
        val service = Intent(this, Phase0Service::class.java)
            .putExtra(Phase0Service.PERMISSION, data)
            .putExtra(Phase0Service.ADDRESS, address)
            .putExtra(Phase0Service.WIDTH, width)
            .putExtra(Phase0Service.HEIGHT, height)
        Phase0Service.state = "正在准备投屏…"
        Phase0Service.active = true
        try {
            startForegroundService(service)
        } catch (_: Exception) {
            Phase0Service.active = false
            Phase0Service.state = "无法启动投屏，请重试"
        }
        showDevices()
    }

    @Suppress("DEPRECATION")
    private fun showReceiver() {
        leaveScreen()
        screen = Screen.RECEIVE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this)
        root.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        val waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(BACKGROUND)
        }
        waiting.addView(label("等待投屏", 32, true))
        val hint = label("在手机上点「发送投屏」，选择 ${Build.MODEL}。", 17)
        waiting.addView(hint)
        val exit = action("返回") { showHome() }
        waiting.addView(exit, LinearLayout.LayoutParams(dp(200), dp(56)))
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        val current = generation
        var videoWidth = 0
        var videoHeight = 0
        fun fitVideo() {
            if (videoWidth <= 0 || videoHeight <= 0 || root.width <= 0 || root.height <= 0) return
            val scale = minOf(root.width.toFloat() / videoWidth, root.height.toFloat() / videoHeight)
            val width = (videoWidth * scale).roundToInt()
            val height = (videoHeight * scale).roundToInt()
            val params = video.layoutParams as FrameLayout.LayoutParams
            if (width != params.width || height != params.height) {
                video.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                if (generation != current) return
                receiverSurface = Surface(texture)
                try {
                    val wifi = applicationContext.getSystemService(WifiManager::class.java)
                    wifiLock = wifi?.createMulticastLock("lan-mirror")?.also { it.acquire() }
                    advertisement = Discovery.advertise(Build.MODEL)
                    receiver = MirrorReceiver(requireNotNull(receiverSurface), { width, height ->
                        runOnUiThread {
                            if (generation == current) {
                                videoWidth = width
                                videoHeight = height
                                fitVideo()
                                waiting.visibility = View.GONE
                            }
                        }
                    }, {
                        runOnUiThread {
                            if (generation == current) {
                                waiting.visibility = View.VISIBLE
                                exit.requestFocus()
                            }
                        }
                    }, {
                        runOnUiThread {
                            if (generation == current) {
                                closeReceiver()
                                hint.text = "无法启动接收。请返回后重试。"
                            }
                        }
                    }).also { it.start() }
                } catch (_: Exception) {
                    closeReceiver()
                    hint.text = "无法启动接收。请检查 Wi-Fi 后重试。"
                }
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (generation == current) {
                    closeReceiver()
                    waiting.visibility = View.VISIBLE
                }
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {}
        }
        setContentView(root)
        exit.requestFocus()
    }

    private fun closeReceiver() {
        advertisement?.close()
        advertisement = null
        receiver?.close()
        receiver = null
        receiverSurface?.release()
        receiverSurface = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    @Suppress("DEPRECATION")
    private fun leaveScreen() {
        generation++
        closeReceiver()
        search?.close()
        search = null
        devices = emptyList()
        selectedAddress = null
        senderStatus = null
        deviceList = null
        handler.removeCallbacks(updateSender)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = 0
    }

    private fun panel(): LinearLayout {
        val root = ScrollView(this).apply { setBackgroundColor(BACKGROUND); isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -2))
        setContentView(root)
        return column
    }

    private fun label(text: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        gravity = Gravity.CENTER
        setTextColor(if (bold) Color.WHITE else 0xFFB6C5D4.toInt())
        setPadding(0, dp(4), 0, dp(14))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun action(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        isAllCaps = false
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(-1, dp(58))
        setOnClickListener { click() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    @Deprecated("Supports physical Android TV remotes on Android 8 and later")
    override fun onBackPressed() {
        if (screen == Screen.HOME) super.onBackPressed() else showHome()
    }

    override fun onDestroy() {
        leaveScreen()
        super.onDestroy()
    }

    companion object {
        private const val CAPTURE = 201
        private const val PLAYBACK_PERMISSION = 202
        private val BACKGROUND = 0xFF101C29.toInt()
    }
}
