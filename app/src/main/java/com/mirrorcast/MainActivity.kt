// Modified for JiaJia Cast.
package com.mirrorcast

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.mirrorcast.cast.BUFFER_OPTIONS
import com.mirrorcast.cast.FPS_OPTIONS
import com.mirrorcast.cast.MirrorReceiver
import com.mirrorcast.cast.Phase0Service
import com.mirrorcast.cast.VideoQuality
import com.mirrorcast.util.DiagLog
import com.mirrorcast.util.Discovery
import java.net.DatagramSocket
import java.net.Inet4Address
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
    private var mutePhone = false
    private val settings by lazy { getSharedPreferences("cast_settings", MODE_PRIVATE) }
    private var quality = VideoQuality.CLEAR
    private var bufferMs = 1000
    private var frameRate = 30
    private var fillScreen = false
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
        quality = VideoQuality.entries.getOrNull(settings.getInt("video_quality", VideoQuality.CLEAR.ordinal))
            ?: VideoQuality.CLEAR
        bufferMs = settings.getInt("buffer_ms", 1000).takeIf { it in BUFFER_OPTIONS } ?: 1000
        frameRate = settings.getInt("frame_rate", 30).takeIf { it in FPS_OPTIONS } ?: 30
        fillScreen = settings.getBoolean("fill_screen", false)
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
        panel.addView(label("版本 ${BuildConfig.VERSION_NAME}", 14))
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
            panel.addView(CheckBox(this).apply {
                text = "仅接收端出声"
                isChecked = mutePhone
                setOnCheckedChangeListener { _, checked -> mutePhone = checked }
            })
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
            if (devices.isEmpty()) "在另一台设备打开「接收投屏」。" else "选择要投屏的设备。"
        }
        for (device in devices) {
            list.addView(action(device.name) { requestCapture(device.address.hostAddress ?: return@action) })
        }
        list.addView(action("画质 · ${quality.label} · 长边 ${quality.longEdge}") { showQualityOptions() })
        list.addView(action("帧率 · $frameRate 帧/秒") {
            chooseOption("选择帧率上限", FPS_OPTIONS.map { "$it 帧/秒" }, FPS_OPTIONS.indexOf(frameRate)) {
                frameRate = FPS_OPTIONS[it]
                settings.edit().putInt("frame_rate", frameRate).apply()
            }
        })
        list.addView(action("缓冲 · ${bufferLabel(bufferMs)}") {
            chooseOption("选择接收缓冲", BUFFER_OPTIONS.map(::bufferLabel), BUFFER_OPTIONS.indexOf(bufferMs)) {
                bufferMs = BUFFER_OPTIONS[it]
                settings.edit().putInt("buffer_ms", bufferMs).apply()
            }
        })
        val displayModes = listOf("显示完整画面", "铺满屏幕")
        val displayIndex = if (fillScreen) 1 else 0
        list.addView(action("画面 · ${displayModes[displayIndex]}") {
            chooseOption("选择接收端显示方式", displayModes, displayIndex) {
                fillScreen = it == 1
                settings.edit().putBoolean("fill_screen", fillScreen).apply()
            }
        })
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            list.addView(action("扫码连接") {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    openScanner()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
                }
            })
        }
        list.addView(action("输入接收端 IP") { showAddressInput() })
    }

    private fun showQualityOptions() {
        val options = VideoQuality.entries.map { "${it.label} · 长边 ${it.longEdge} 像素" }
        chooseOption("选择画质", options, quality.ordinal) { index ->
            quality = VideoQuality.entries[index]
            settings.edit().putInt("video_quality", index).apply()
        }
    }

    private fun bufferLabel(ms: Int): String = when (ms) {
        5000 -> "5 秒（看视频）"
        2000 -> "2 秒"
        1000 -> "1 秒"
        else -> "$ms 毫秒"
    }

    private fun chooseOption(title: String, options: List<String>, selected: Int, apply: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, index ->
                apply(index)
                dialog.dismiss()
                showDevices()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun openScanner() {
        try {
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描接收端显示的投屏二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
            if (resources.configuration.smallestScreenWidthDp < 600) {
                options.setCaptureActivity(PortraitScannerActivity::class.java)
            }
            startActivityForResult(options.createScanIntent(this), SCAN)
        } catch (error: RuntimeException) {
            DiagLog.log("扫码页面启动失败：${error.message}")
            senderStatus?.text = "无法打开扫码，请输入接收端 IP。"
        }
    }

    private fun showAddressInput() {
        val addressField = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "接收端显示的 IPv4 地址"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("手动连接")
            .setMessage("在平板或投影仪的「接收投屏」页面查看 IP。")
            .setView(addressField)
            .setNegativeButton("取消", null)
            .setPositiveButton("连接", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = normalizeIpv4(addressField.text.toString())
                if (address == null) {
                    addressField.error = "请输入有效的 IPv4 地址，例如 192.168.1.25"
                    return@setOnClickListener
                }
                dialog.dismiss()
                requestCapture(address)
            }
        }
        dialog.show()
    }

    private fun normalizeIpv4(address: String): String? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { part ->
            if (part.length !in 1..3 || part.any { it !in '0'..'9' }) return null
            part.toInt()
        }
        if (numbers.any { it !in 0..255 } || numbers[0] !in 1..223 || numbers[0] == 127) return null
        return numbers.joinToString(".")
    }

    private fun receiverAddresses(): List<String> {
        val manager = getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork
        @Suppress("DEPRECATION")
        return manager.allNetworks.asSequence()
            .sortedByDescending { it == active }
            .filter { network ->
                val caps = manager.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }
            .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress?.let(::normalizeIpv4) }
            .distinct()
            .toList()
    }

    private fun connectionQr(address: String): Bitmap {
        val size = 256
        val code = QRCodeWriter().encode(QR_PREFIX + address, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { index ->
            if (code[index % size, index / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
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
        if (screen != Screen.SEND) return
        if (requestCode == CAMERA_PERMISSION) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openScanner()
            else senderStatus?.text = "未获得相机权限，可输入接收端 IP。"
        } else if (requestCode == PLAYBACK_PERMISSION && selectedAddress != null) {
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
        if (requestCode == SCAN) {
            if (screen != Screen.SEND) showSender()
            if (resultCode != RESULT_OK || data == null) return
            val contents = ScanIntentResult.parseActivityResult(resultCode, data).contents ?: return
            val address = if (contents.startsWith(QR_PREFIX)) normalizeIpv4(contents.removePrefix(QR_PREFIX)) else null
            if (address == null) senderStatus?.text = "请扫描佳佳简易投屏接收端显示的二维码。"
            else requestCapture(address)
            return
        }
        if (requestCode != CAPTURE) return
        val address = selectedAddress
        selectedAddress = null
        if (resultCode != RESULT_OK || data == null || address == null) {
            senderStatus?.text = "已取消屏幕共享"
            return
        }
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val (width, height) = quality.dimensions(metrics.widthPixels, metrics.heightPixels)
        val service = Intent(this, Phase0Service::class.java)
            .putExtra(Phase0Service.PERMISSION, data)
            .putExtra(Phase0Service.ADDRESS, address)
            .putExtra(Phase0Service.WIDTH, width)
            .putExtra(Phase0Service.HEIGHT, height)
            .putExtra(Phase0Service.BITRATE, quality.bitrate)
            .putExtra(Phase0Service.FRAME_RATE, frameRate)
            .putExtra(Phase0Service.BUFFER_MS, bufferMs)
            .putExtra(Phase0Service.FILL_SCREEN, fillScreen)
            .putExtra(Phase0Service.MUTE_PHONE, mutePhone)
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
        var fillPlayback = false
        root.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        val waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(BACKGROUND)
        }
        waiting.addView(label("等待投屏", 32, true))
        val qr = ImageView(this).apply {
            visibility = View.GONE
            contentDescription = "投屏连接二维码"
        }
        val qrSize = dp((resources.configuration.screenHeightDp / 3).coerceIn(96, 160))
        waiting.addView(qr, LinearLayout.LayoutParams(qrSize, qrSize))
        val hint = label("正在启动接收…", 17)
        waiting.addView(hint)
        val exit = action("返回") { showHome() }
        waiting.addView(exit, LinearLayout.LayoutParams(dp(200), dp(56)))
        val waitingScreen = ScrollView(this).apply {
            isFillViewport = true
            addView(waiting, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(waitingScreen, FrameLayout.LayoutParams(-1, -1))
        val current = generation
        var videoWidth = 0
        var videoHeight = 0
        var waitingHint = hint.text
        fun fitVideo() {
            if (videoWidth <= 0 || videoHeight <= 0 || root.width <= 0 || root.height <= 0) return
            val scaleX = root.width.toFloat() / videoWidth
            val scaleY = root.height.toFloat() / videoHeight
            val fitScale = minOf(scaleX, scaleY)
            val width = (videoWidth * fitScale).roundToInt()
            val height = (videoHeight * fitScale).roundToInt()
            val zoom = if (fillPlayback) maxOf(scaleX, scaleY) / fitScale else 1f
            video.scaleX = zoom
            video.scaleY = zoom
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
                    receiver = MirrorReceiver(requireNotNull(receiverSurface), { width, height ->
                        runOnUiThread {
                            if (generation == current) {
                                videoWidth = width
                                videoHeight = height
                                waitingScreen.visibility = View.GONE
                                fitVideo()
                            }
                        }
                    }, {
                        runOnUiThread {
                            if (generation == current) {
                                waitingScreen.visibility = View.VISIBLE
                                fillPlayback = false
                                fitVideo()
                                videoWidth = 0
                                videoHeight = 0
                                hint.text = waitingHint
                                exit.requestFocus()
                            }
                        }
                    }, {
                        runOnUiThread {
                            if (generation == current) {
                                closeReceiver()
                                qr.visibility = View.GONE
                                hint.text = "无法启动接收。请返回后重试。"
                            }
                        }
                    }, onReady = {
                        runOnUiThread {
                            if (generation == current && video.isAvailable && receiver != null) {
                                val addresses = receiverAddresses()
                                val address = addresses.firstOrNull()
                                if (address != null) {
                                    qr.setImageBitmap(connectionQr(address))
                                    qr.visibility = View.VISIBLE
                                    hint.text = "用发送端的「扫码连接」扫描\n接收端 IP：${addresses.joinToString(" / ")}"
                                } else {
                                    qr.visibility = View.GONE
                                    hint.text = "未找到局域网 IPv4 地址，请检查 Wi-Fi 或网线。"
                                }
                                waitingHint = hint.text
                                try {
                                    val wifi = applicationContext.getSystemService(WifiManager::class.java)
                                    wifiLock = wifi?.createMulticastLock("lan-mirror")?.also { it.acquire() }
                                } catch (_: Exception) {
                                    // Direct connections do not depend on the Wi-Fi multicast lock.
                                }
                                try {
                                    advertisement = Discovery.advertise(Build.MODEL)
                                } catch (_: java.io.IOException) {
                                    // The TCP receiver can still accept direct connections.
                                }
                            }
                        }
                    }, onConnected = { options ->
                        runOnUiThread {
                            if (generation == current) {
                                fillPlayback = options.fillScreen
                                hint.text = "正在缓冲 · ${bufferLabel(options.bufferMs)}"
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
                    qr.visibility = View.GONE
                    hint.text = "正在启动接收…"
                    waitingScreen.visibility = View.VISIBLE
                    fillPlayback = false
                    videoWidth = 0
                    videoHeight = 0
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
        private const val QR_PREFIX = "jiajia-cast:1:"
        private const val CAPTURE = 201
        private const val PLAYBACK_PERMISSION = 202
        private const val CAMERA_PERMISSION = 203
        private const val SCAN = 204
        private val BACKGROUND = 0xFF101C29.toInt()
    }
}
