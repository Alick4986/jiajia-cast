// Modified for 简易投屏: codec setup, framing and capture resource ownership.
package com.mirrorcast.cast

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import com.mirrorcast.util.DiagLog

/** 编码帧回调：frame 为 Annex-B 访问单元（含起始码） */
fun interface FrameListener {
    fun onFrame(frame: ByteArray, keyframe: Boolean, ptsUs: Long)
}

/**
 * 视频源：VirtualDisplay → 编码器输入 Surface（零拷贝）→ 帧回调。
 * 发送端零像素处理（CPU≈0），翻转移至接收端；关键帧即时标记，供接收端重同步。
 */
class VideoSource(
    private val projection: MediaProjection,
    private val requestedWidth: Int,
    private val requestedHeight: Int,
    private val frameRate: Int = 30,
    private val bitrate: Int = 10_000_000,
    private val keyIntervalSec: Float = 0.5f
) {

    var frameListener: FrameListener? = null
    var width = requestedWidth
        private set
    var height = requestedHeight
        private set
    var startupError: String? = null
        private set

    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private val encInfo = MediaCodec.BufferInfo()
    private var encSurface: Surface? = null
    private var encodeThread: Thread? = null
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null

    @Volatile var running = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var encodedFrames = 0L
        private set
    @Volatile var encodedBytes = 0L
        private set

    // ---- 诊断统计（本 App 进程视角） ----
    /** 编码耗时滑动均值（毫秒）：最近一帧编码输出耗时 */
    @Volatile var lastEncodeMs = 0.0
        private set
    /** 实际测得码率（kbps）：滚动窗口内（约最近1s）编码字节数换算 */
    @Volatile var measuredBitrate = 0
        private set

    // 码率滚动窗口（仅在编码线程访问，无需加锁）
    private var bitrateWinBytes = 0L
    private var bitrateWinFrames = 0
    private var bitrateWinStart = System.nanoTime()

    private var lastErrLogMs = 0L

    @Synchronized
    fun prepare(): Boolean {
        val failures = mutableListOf<String>()
        val tried = mutableSetOf<String>()
        if (tryEncoder(null, tried, failures)) return true
        val alternatives = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                    (Build.VERSION.SDK_INT < 29 || !info.isAlias)
            }
        } catch (error: Exception) {
            failures += "读取备用编码器失败（${error.javaClass.simpleName}）"
            emptyList()
        }
        for (info in alternatives) {
            if (Thread.currentThread().isInterrupted) break
            if (info.name !in tried && tryEncoder(info.name, tried, failures)) return true
        }
        startupError = failures.joinToString("\n", prefix = "H.264 编码器启动准备失败\n")
        return false
    }

    private fun tryEncoder(name: String?, tried: MutableSet<String>, failures: MutableList<String>): Boolean {
        var codecName = name ?: "系统默认"
        var sizeLabel = "${requestedWidth}×$requestedHeight"
        var stage = "无法创建 H.264 编码器"
        return try {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val enc = if (name == null) MediaCodec.createEncoderByType(mime) else MediaCodec.createByCodecName(name)
            encoder = enc
            codecName = enc.name
            tried += codecName
            val info = enc.codecInfo
            tried += info.name
            if (Build.VERSION.SDK_INT >= 29) tried += info.canonicalName
            stage = "读取 H.264 编码能力失败"
            val capabilities = info.getCapabilitiesForType(mime)
            stage = "H.264 编码器不支持屏幕输入"
            require(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in capabilities.colorFormats)
            val videoCaps = requireNotNull(capabilities.videoCapabilities)
            val size = findEncoderSize(requestedWidth, requestedHeight, videoCaps.widthAlignment, videoCaps.heightAlignment) { w, h ->
                videoCaps.areSizeAndRateSupported(w, h, frameRate.toDouble())
            }
            if (size == null) {
                stage = "H.264 编码器不支持此尺寸和帧率"
                throw IllegalArgumentException()
            }
            width = size.first
            height = size.second
            sizeLabel = "${width}×$height"
            stage = "H.264 编码器配置失败"
            DiagLog.log("编码器：${enc.name}，请求 ${requestedWidth}×$requestedHeight，实际 ${width}×$height @ ${frameRate} 帧")
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            if (Build.VERSION.SDK_INT >= 29) {
                fmt.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
            }
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyIntervalSec.toInt().coerceAtLeast(1)) // 关键帧间隔(秒)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            stage = "H.264 编码器输入画面创建失败"
            encSurface = enc.createInputSurface()
            stage = "H.264 编码器启动失败"
            enc.start()
            true
        } catch (error: Exception) {
            val message = "$codecName · $sizeLabel @ ${frameRate} 帧 · $stage（${describe(error)}）"
            failures += message
            DiagLog.log(message)
            logErr(error)
            stop()
            false
        }
    }

    @Synchronized
    fun start(): Boolean {
        return try {
            val enc = checkNotNull(encoder)
            // 请求首关键帧立即输出（配置帧随 IDR 出）
            try { val b = android.os.Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); enc.setParameters(b) } catch (_: Exception) {}
            virtualDisplay = checkNotNull(projection.createVirtualDisplay(
                "V3Source", width, height, 240,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                checkNotNull(encSurface), null, null
            )) { "系统未能创建虚拟屏幕" }
            running = true
            DiagLog.log("视频源启动：${width}x${height} @${frameRate}fps ${bitrate / 1000}kbps 关键帧${keyIntervalSec}s")
            startEncodeThread()
            true
        } catch (error: Exception) {
            startupError = "安卓屏幕捕获启动失败（${describe(error)}）"
            DiagLog.log(startupError.orEmpty())
            logErr(error)
            stop()
            false
        }
    }

    private fun describe(error: Exception): String {
        val detail = (error as? MediaCodec.CodecException)?.diagnosticInfo
            ?: error.message?.lineSequence()?.firstOrNull()?.take(110)
        return error.javaClass.simpleName + (detail?.let { " · $it" }.orEmpty())
    }

    private fun startEncodeThread() {
        encodeThread = Thread {
            while (running) {
                try {
                    val enc = encoder ?: break
                    when (val idx = enc.dequeueOutputBuffer(encInfo, 100_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // 提取 SPS/PPS（仅此一次；多数编码器后续帧不再携带）
                            try {
                                val fmt = enc.outputFormat
                                csd0 = readCsd(fmt, "csd-0")
                                csd1 = readCsd(fmt, "csd-1")
                                DiagLog.log("视频源格式就绪: csd0=" + csd0?.size + " csd1=" + csd1?.size + " csd0首字节=" + (csd0?.firstOrNull()?.toInt()?.and(0x1f) ?: -1) + " 期望7=SPS")
                            } catch (e: Throwable) {
                                DiagLog.log("csd 提取失败：${e.javaClass.simpleName}")
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        in Int.MIN_VALUE until 0 -> {}
                        else -> {
                            val t0 = System.nanoTime()
                            val buf = enc.getOutputBuffer(idx)
                            if (buf != null) {
                                val bytes = ByteArray(encInfo.size)
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                buf.get(bytes)
                                encodedFrames++
                                encodedBytes += bytes.size
                                // 滑动窗口测实际码率（约最近 1s，最多 60 帧）
                                bitrateWinBytes += bytes.size
                                bitrateWinFrames++
                                if (bitrateWinFrames >= 30 || System.nanoTime() - bitrateWinStart > 1_000_000_000L) {
                                    val spanS = (System.nanoTime() - bitrateWinStart) / 1_000_000_000.0
                                    if (spanS > 0) measuredBitrate = (bitrateWinBytes * 8L / spanS / 1000).toInt().coerceAtLeast(0)
                                    bitrateWinBytes = 0; bitrateWinFrames = 0; bitrateWinStart = System.nanoTime()
                                }
                                val key = (encInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || isKeyframe(bytes)
                                // 修复：SPS/PPS 可能出现在任意 AU 开头（尤其 format-change 后首帧），对每帧都尝试提取
                                if (csd0 == null || csd1 == null) {
                                    extractSpsPps(bytes)
                                }
                                val out = if (key) withCsd(bytes) else bytes
                                frameListener?.onFrame(out, key, encInfo.presentationTimeUs)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            lastEncodeMs = (System.nanoTime() - t0) / 1_000_000.0
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    if (!running) break // 停止阶段的竞态不记录
                    logErr(t)
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                    // 编码器致命错误（如 CodecException）：停止并释放资源
                    if (running && t is android.media.MediaCodec.CodecException) {
                        DiagLog.log("编码器故障，自动停止：${t.diagnosticInfo}")
                        stop()
                        break
                    }
                }
            }
        }.apply { isDaemon = true; name = "src-enc"; start() }
    }

    /** 安全读取 csd buffer：rewind 读，并校验 NAL 类型（空壳返回 null，交由关键帧提取兜底） */
    private fun readCsd(fmt: android.media.MediaFormat, key: String): ByteArray? {
        return try {
            val buf = fmt.getByteBuffer(key) ?: return null
            val dup = buf.duplicate()
            dup.rewind()
            val out = ByteArray(dup.remaining())
            dup.get(out)
            val offset = when {
                out.size > 4 && out[0] == 0.toByte() && out[1] == 0.toByte() &&
                    out[2] == 0.toByte() && out[3] == 1.toByte() -> 4
                out.size > 3 && out[0] == 0.toByte() && out[1] == 0.toByte() &&
                    out[2] == 1.toByte() -> 3
                else -> 0
            }
            val type = out.getOrNull(offset)?.toInt()?.and(0x1f)
            if (type != 7 && type != 8) return null
            out.copyOfRange(offset, out.size)
        } catch (_: Exception) {
            null
        }
    }

    /** 从关键帧的 Annex-B 流里扫描 SPS(7)/PPS(8) NAL 并缓存（format csd 为空时的兜底） */
    private fun extractSpsPps(au: ByteArray) {
        val n = au.size
        var i = 0
        while (i + 3 < n) {
            var scLen = 0
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte()) {
                scLen = 3
            } else if (i + 4 < n && au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 0.toByte() && au[i + 3] == 1.toByte()) {
                scLen = 4
            }
            if (scLen > 0) {
                val payloadStart = i + scLen
                if (payloadStart < n) {
                    val type = au[payloadStart].toInt() and 0x1f
                    if (type == 7 || type == 8) {
                        // 找该 NAL 结束（下一起始码或末尾）
                        var end = payloadStart
                        var j = payloadStart + 1
                        while (j + 3 < n) {
                            if (au[j] == 0.toByte() && au[j + 1] == 0.toByte() && (au[j + 2] == 1.toByte() || (j + 3 < n && au[j + 2] == 0.toByte() && au[j + 3] == 1.toByte()))) {
                                end = j
                                break
                            }
                            j++
                        }
                        val payload = if (end <= payloadStart) au.copyOfRange(payloadStart, n) else au.copyOfRange(payloadStart, end)
                        if (type == 7 && csd0 == null) {
                            csd0 = payload
                            DiagLog.log("从关键帧提取 SPS，size=" + payload.size)
                        } else if (type == 8 && csd1 == null) {
                            csd1 = payload
                            DiagLog.log("从关键帧提取 PPS，size=" + payload.size)
                        }
                        i = if (end > payloadStart) end else (payloadStart + 1)
                        continue
                    }
                }
            }
            i++
        }
    }

    private fun withCsd(frame: ByteArray): ByteArray {
        val sps = csd0 ?: return frame
        val pps = csd1
        val spsLen = sps.size + 4
        val ppsLen = if (pps == null) 0 else pps.size + 4
        val out = ByteArray(spsLen + ppsLen + frame.size)
        // 00 00 00 01 + SPS
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(sps, 0, out, 4, sps.size)
        var off = spsLen
        if (pps != null) {
            out[off] = 0; out[off + 1] = 0; out[off + 2] = 0; out[off + 3] = 1
            System.arraycopy(pps, 0, out, off + 4, pps.size)
            off += ppsLen
        }
        System.arraycopy(frame, 0, out, off, frame.size)
        return out
    }

    /** 请求立即输出关键帧（供新接收端连接时调用，加快首帧/重同步） */
    fun requestSyncFrame() {
        try {
            val b = android.os.Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder?.setParameters(b)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        val worker = encodeThread
        worker?.interrupt()
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(200) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        encodeThread = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encSurface?.release() } catch (_: Exception) {}
        encSurface = null
        DiagLog.log("视频源已停止")
    }

    private fun logErr(t: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastErrLogMs > 2000) {
            lastErrLogMs = now
            Log.e(TAG, "source error", t)
            DiagLog.log("视频源错误：${t.javaClass.simpleName}: ${t.message ?: ""}")
            lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}"
        }
    }

    companion object {
        private const val TAG = "MirrorCastV3"

        /** 判定访问单元是否为关键帧：首个 NAL 类型 == 5（IDR）；SPS/PPS(7/8) 会随关键帧同来 */
        fun isKeyframe(au: ByteArray): Boolean {
            var i = 0
            while (i + 4 <= au.size) {
                // 找起始码 00 00 01 或 00 00 00 01
                if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 0.toByte() && au[i + 3] == 1.toByte()) {
                    val type = au.getOrNull(i + 4)?.toInt()?.and(0x1F) ?: return false
                    // 跳过 SPS(7)/PPS(8)/SEI(6)，直到 IDR(5) 或非配置 NAL
                    if (type == 5) return true
                    if (type == 7 || type == 8 || type == 6) { i += 4; continue }
                    return false
                }
                if (i + 3 <= au.size && au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte()) {
                    val type = au.getOrNull(i + 3)?.toInt()?.and(0x1F) ?: return false
                    if (type == 5) return true
                    if (type == 7 || type == 8 || type == 6) { i += 3; continue }
                    return false
                }
                i++
            }
            return false
        }
    }
}