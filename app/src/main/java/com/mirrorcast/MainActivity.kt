// Modified for JiaJia Cast.
package com.mirrorcast

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
    private var selectedDevice: String? = null
    private var manualConnection = false
    private var streamInfoView: TextView? = null
    private var mutePhone = false
    private val settings by lazy { getSharedPreferences("cast_settings", MODE_PRIVATE) }
    private var quality = VideoQuality.CLEAR
    private var bufferMs = 1000
    private var frameRate = 30
    private var fillScreen = false
    private var devices = emptyList<Discovery.Device>()
    private var senderStatus: TextView? = null
    private var deviceList: LinearLayout? = null
    private var senderColumns: LinearLayout? = null
    private var homeActions: LinearLayout? = null
    private var senderActions: LinearLayout? = null
    private var senderSettings: LinearLayout? = null
    private var senderDeviceHeading: TextView? = null
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
            streamInfoView?.text = Phase0Service.streamInfo
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
        if (!BuildConfig.RECEIVER_ONLY) {
            val page = senderColumn().apply { setPadding(dp(20), dp(40), dp(20), dp(24)) }
            val scroll = ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(page)
            }
            setContentView(scroll)
            page.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
                LinearLayout.LayoutParams(dp(64), dp(64)))
            page.addView(senderText(getString(R.string.app_name), 32, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            page.addView(senderText("两台设备连接同一个 Wi-Fi", 16, SECONDARY),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(28) })
            val actions = senderColumn().also(page::addView)
            actions.addView(senderRow("发送投屏", "分享屏幕", connect = true) { showSender() })
            actions.addView(senderRow("接收投屏", "观看投屏", connect = true) { showReceiver() })
            homeActions = actions
            layoutHomeActions()
            page.addView(senderText("版本 ${BuildConfig.VERSION_NAME}", 14, SECONDARY),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            return
        }
        val panel = panel()
        panel.addView(label(getString(R.string.app_name), 32, true))
        panel.addView(label("手机和投影仪连接同一个 Wi-Fi。", 17))
        val receive = action("接收投屏") { showReceiver() }
        val send = if (BuildConfig.RECEIVER_ONLY) null else action("发送投屏") { showSender() }
        send?.let(panel::addView)
        panel.addView(receive)
        val edition = if (BuildConfig.RECEIVER_ONLY) " · Android 6 接收版" else ""
        panel.addView(label("版本 ${BuildConfig.VERSION_NAME}$edition", 14))
        if (send == null || packageManager.hasSystemFeature("android.software.leanback")) receive.requestFocus()
        else send.requestFocus()
    }

    private fun showSender() {
        leaveScreen()
        screen = Screen.SEND
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
        }
        val page = senderColumn().apply { setPadding(dp(20), dp(18), dp(20), dp(28)) }
        scroll.addView(page, FrameLayout.LayoutParams(-1, -2))
        setContentView(scroll)
        page.addView(senderButton("‹ 返回") { showHome() }, LinearLayout.LayoutParams(-2, dp(48)))
        page.addView(senderText("发送投屏", 32, Color.WHITE, bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(22) })

        val columns = LinearLayout(this).also { senderColumns = it; page.addView(it) }
        val connection = senderColumn().also(columns::addView)
        senderDeviceHeading = senderHeading("可用设备").also(connection::addView)
        senderStatus = senderText("", 14, SECONDARY).apply {
            setPadding(0, 0, 0, dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(connection::addView)
        deviceList = senderColumn().also(connection::addView)
        senderActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = { LinearLayout.LayoutParams(0, dp(48), 1f) }
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                addView(senderButton("扫码连接") {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        openScanner()
                    } else {
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
                    }
                }, params().apply { marginEnd = dp(10) })
            }
            addView(senderButton("手动 IP") { showAddressInput() }, params())
        }.also { connection.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
        senderSettings = senderColumn().also(columns::addView)
        layoutSenderColumns()
        showSenderSettings()
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
        streamInfoView = null
        val active = Phase0Service.active
        val visibility = if (active) View.GONE else View.VISIBLE
        senderActions?.visibility = visibility
        senderSettings?.visibility = visibility
        senderDeviceHeading?.text = if (active) "投屏状态" else "可用设备"
        if (active) {
            senderStatus?.text = ""
            val status = Phase0Service.state.ifBlank { "正在准备投屏…" }
            val name = Phase0Service.receiverLabel
            val card = senderColumn().apply {
                background = senderShape(CARD)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(senderText(name.ifBlank { "接收设备" }, 20, Color.WHITE, bold = true))
                addView(senderText(status, 15, ACCENT).apply { setPadding(0, dp(8), 0, 0) })
                streamInfoView = senderText(Phase0Service.streamInfo, 14, SECONDARY).apply {
                    setPadding(0, dp(6), 0, 0)
                }.also(::addView)
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2))
            list.addView(senderButton("结束投屏", prominent = true) {
                stopService(Intent(this, Phase0Service::class.java))
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
            return
        }
        val error = Phase0Service.state
        senderStatus?.text = when {
            error.startsWith("连不上接收端") -> "$error\n核对接收页上的 IP 和两台设备的 Wi-Fi。"
            error.startsWith("H.264") -> "$error\n可试用上次成功的画质和帧率。"
            else -> error
        }.ifBlank {
            if (devices.isEmpty()) "在另一台设备打开「接收投屏」。" else "选择要投屏的设备。"
        }
        val lastAddress = settings.getString("last_address", null)
        val lastName = settings.getString("last_device", null)
        val lastDevice = devices.firstOrNull { it.address.hostAddress == lastAddress && it.name == lastName }
        if (lastAddress != null && lastDevice != null) {
            list.addView(senderRow("上次连接 · ${lastDevice.name}", "重连", connect = true) {
                if (selectedAddress != null || Phase0Service.active) return@senderRow
                quality = VideoQuality.entries.getOrNull(settings.getInt("last_quality", VideoQuality.CLEAR.ordinal))
                    ?: VideoQuality.CLEAR
                frameRate = settings.getInt("last_fps", 30)
                bufferMs = settings.getInt("last_buffer", 1000)
                fillScreen = settings.getBoolean("last_fill", false)
                mutePhone = settings.getBoolean("last_mute", false)
                showSenderSettings()
                requestCapture(lastAddress, lastDevice.name)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        for (device in devices) {
            list.addView(senderRow(device.name, "连接", connect = true) {
                device.address.hostAddress?.let { requestCapture(it, device.name) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun showSenderSettings() {
        val panel = senderSettings ?: return
        panel.removeAllViews()
        panel.addView(senderHeading("投屏设置"))
        val list = senderColumn().apply { background = senderShape(CARD) }
        panel.addView(list, LinearLayout.LayoutParams(-1, -2))
        list.addView(senderRow("画质", "${quality.label} ${quality.longEdge}") { showQualityOptions() })
        addSenderDivider(list)
        list.addView(senderRow("帧率", "$frameRate 帧/秒") {
            chooseOption("选择帧率上限", FPS_OPTIONS.map { "$it 帧/秒" }, FPS_OPTIONS.indexOf(frameRate)) {
                frameRate = FPS_OPTIONS[it]
                settings.edit().putInt("frame_rate", frameRate).apply()
            }
        })
        addSenderDivider(list)
        list.addView(senderRow("缓冲", bufferLabel(bufferMs)) {
            chooseOption("选择接收缓冲", BUFFER_OPTIONS.map(::bufferLabel), BUFFER_OPTIONS.indexOf(bufferMs)) {
                bufferMs = BUFFER_OPTIONS[it]
                settings.edit().putInt("buffer_ms", bufferMs).apply()
            }
        })
        val displayModes = listOf("显示完整画面", "铺满屏幕")
        val displayIndex = if (fillScreen) 1 else 0
        addSenderDivider(list)
        list.addView(senderRow("显示方式", displayModes[displayIndex]) {
            chooseOption("选择接收端显示方式", displayModes, displayIndex) {
                fillScreen = it == 1
                settings.edit().putBoolean("fill_screen", fillScreen).apply()
            }
        })
        if (Build.VERSION.SDK_INT >= 29) {
            addSenderDivider(list)
            list.addView(Switch(this).apply {
                text = "仅接收端出声"
                textSize = 16f
                setTextColor(Color.WHITE)
                minimumHeight = dp(56)
                setPadding(dp(16), 0, dp(16), 0)
                isChecked = mutePhone
                setOnCheckedChangeListener { _, checked -> mutePhone = checked }
            }, LinearLayout.LayoutParams(-1, -2))
            panel.addView(senderText("共享应用声音时，需允许「麦克风」权限。", 13, SECONDARY).apply {
                setPadding(dp(4), dp(10), dp(4), 0)
            })
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutSenderColumns()
        layoutHomeActions()
    }

    private fun layoutHomeActions() {
        val actions = homeActions ?: return
        val wide = resources.configuration.screenWidthDp >= 720 &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        actions.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (index in 0 until actions.childCount) {
            actions.getChildAt(index).layoutParams = if (wide) {
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(12) }
            } else {
                LinearLayout.LayoutParams(-1, -2).apply { if (index != 0) topMargin = dp(12) }
            }
        }
    }

    private fun layoutSenderColumns() {
        val columns = senderColumns ?: return
        val config = resources.configuration
        val sideBySide = config.screenWidthDp >= 720 && config.orientation == Configuration.ORIENTATION_LANDSCAPE
        columns.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        columns.getChildAt(0).layoutParams = if (sideBySide) {
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(20) }
        } else {
            LinearLayout.LayoutParams(-1, -2)
        }
        columns.getChildAt(1).layoutParams = if (sideBySide) {
            LinearLayout.LayoutParams(0, -2, 1f)
        } else {
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) }
        }
    }

    private fun senderColumn() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun senderText(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun senderHeading(value: String) = senderText(value, 19, Color.WHITE, bold = true).apply {
        setPadding(0, 0, 0, dp(10))
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun senderShape(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun senderRipple(color: Int) = RippleDrawable(
        ColorStateList.valueOf(0x2883E8CA),
        senderShape(color),
        null
    )

    private fun senderButton(value: String, prominent: Boolean = false, click: () -> Unit) =
        Button(this).apply {
            text = value
            textSize = 15f
            isAllCaps = false
            setTextColor(if (prominent) BACKGROUND else ACCENT)
            background = senderRipple(if (prominent) ACCENT else CARD)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { click() }
        }

    private fun senderRow(title: String, value: String, connect: Boolean = false, trailing: Boolean = true, click: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (connect) 64 else 54)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = senderRipple(if (connect) DEVICE_CARD else CARD)
            isFocusable = true
            contentDescription = "$title，$value"
            setOnClickListener { click() }
            addView(senderText(title, 16, Color.WHITE, bold = connect).apply {
                if (connect) {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(senderText(if (trailing) "$value  ›" else value, 14, ACCENT).apply {
                gravity = Gravity.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }

    private fun addSenderDivider(list: LinearLayout) {
        list.addView(View(this).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) })
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

    private fun dialogPanel(title: String) = senderColumn().apply {
        setPadding(dp(18), dp(18), dp(18), dp(16))
        background = senderShape(CARD)
        addView(senderHeading(title))
    }

    private fun senderDialog(content: LinearLayout): AlertDialog {
        val scroll = ScrollView(this).apply { addView(content) }
        return AlertDialog.Builder(this).setView(scroll).create().also { dialog ->
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                val width = (resources.displayMetrics.widthPixels - dp(40)).coerceIn(dp(220), dp(480))
                dialog.window?.setLayout(width, -2)
            }
        }
    }

    private fun chooseOption(title: String, options: List<String>, selected: Int, apply: (Int) -> Unit) {
        val content = dialogPanel(title)
        val dialog = senderDialog(content)
        options.forEachIndexed { index, option ->
            val checked = index == selected
            content.addView(senderRow(option, if (checked) "✓" else "", connect = checked, trailing = false) {
                apply(index)
                dialog.dismiss()
                showSenderSettings()
            }.apply {
                isSelected = checked
                contentDescription = if (checked) "$option，已选中" else option
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        content.addView(senderButton("取消") { dialog.dismiss() },
            LinearLayout.LayoutParams(-2, dp(48)).apply { gravity = Gravity.END; topMargin = dp(8) })
        dialog.show()
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
            } else {
                options.setCaptureActivity(ZoomScannerActivity::class.java)
            }
            startActivityForResult(options.createScanIntent(this), SCAN)
        } catch (error: RuntimeException) {
            DiagLog.log("扫码页面启动失败：${error.message}")
            senderStatus?.text = "无法打开扫码，请输入接收端 IP。"
        }
    }

    private fun showAddressInput() {
        val content = dialogPanel("手动连接")
        val dialog = senderDialog(content)
        content.addView(senderText("输入接收页上显示的 IP 地址", 14, SECONDARY))
        val addressField = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "例如 192.168.1.25"
            setTextColor(Color.WHITE)
            setHintTextColor(SECONDARY)
            background = senderShape(BACKGROUND)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        content.addView(addressField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val recent = settings.getString("manual_history", "").orEmpty().split(',').mapNotNull(::normalizeIpv4)
        if (recent.isNotEmpty()) {
            content.addView(senderText("最近连接", 14, SECONDARY, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            for (address in recent) {
                content.addView(senderRow(address, "填入", trailing = false) {
                    addressField.setText(address)
                    addressField.setSelection(address.length)
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(senderButton("取消") { dialog.dismiss() }, LinearLayout.LayoutParams(-2, dp(48)))
        actions.addView(senderButton("连接", prominent = true) {
            val address = normalizeIpv4(addressField.text.toString())
            if (address == null) {
                addressField.error = "请输入有效的 IPv4 地址"
                addressField.requestFocus()
            } else {
                dialog.dismiss()
                requestCapture(address, manual = true)
            }
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(10) })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.isFocusableInTouchMode = true
        content.requestFocus()
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
    private fun requestCapture(address: String, deviceName: String? = null, manual: Boolean = false) {
        if (selectedAddress != null || Phase0Service.active) return
        selectedAddress = address
        manualConnection = manual
        selectedDevice = deviceName ?: devices.firstOrNull { it.address.hostAddress == address }?.name ?: address
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
        val deviceName = selectedDevice
        selectedAddress = null
        selectedDevice = null
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
            .putExtra(Phase0Service.MANUAL, manualConnection)
            .putExtra(Phase0Service.QUALITY, quality.ordinal)
        Phase0Service.receiverLabel = deviceName ?: address
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
        val requestStatus = label("连接记录 · 尚未收到请求", 14)
        waiting.addView(requestStatus)
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
                    }, onRequestStatus = { status ->
                        runOnUiThread {
                            if (generation == current && receiver != null) requestStatus.text = "连接记录 · $status"
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
        selectedDevice = null
        senderStatus = null
        streamInfoView = null
        deviceList = null
        senderColumns = null
        homeActions = null
        senderActions = null
        senderSettings = null
        senderDeviceHeading = null
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
        private val CARD = 0xFF1A2A3B.toInt()
        private val SECONDARY = 0xFFB6C5D4.toInt()
        private val ACCENT = 0xFF83E8CA.toInt()
        private val DEVICE_CARD = 0xFF1A3741.toInt()
        private val DIVIDER = 0xFF2D4052.toInt()
    }
}
