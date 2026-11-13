// Modified for 简易投屏: use Annex-B codec headers and preserve the decoded picture aspect ratio.
package com.mirrorcast.cast

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.mirrorcast.util.DiagLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 视频接收渲染：Annex-B 帧 → MediaCodec 硬解 → Surface。
 * 关键帧同步：解码器配置（SPS/PPS）延迟到首个关键帧提取；非关键帧一律丢弃，缺帧后由下一关键帧恢复。
 */
class VideoSink(private val width: Int, private val height: Int, private val previewSurface: Surface) {

    private var decoder: MediaCodec? = null
    private val decInfo = MediaCodec.BufferInfo()
    private var decodeThread: Thread? = null
    private val queue = ArrayBlockingQueue<Frame>(8)
    private var needsKeyframe = true

    @Volatile var running = false
        private set
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    /** 解码+渲染耗时（毫秒）：最近一帧最滑动均值 */
    @Volatile var lastDecodeMs = 0.0
        private set

    // ---- 探针计数（仅累计，不改逻辑） ----
    /** 接收端丢帧：①push 队列满丢旧帧 ②等关键帧期间丢非关键帧 */
    @Volatile var droppedFrames = 0L
        private set
    /** 接收端废弃帧：解码器输入缓冲不可用而丢弃的帧 */
    @Volatile var discardedFrames = 0L
        private set
    /** 接收端丢弃的关键帧数（探针）：仅统计 push 队列满时丢掉的真实 IDR 帧 */
    @Volatile var droppedKey = 0L
        private set
    /** 解码失败次数：解码线程捕获异常（含 MediaCodec 错误） */
    @Volatile var decodeErrors = 0L
        private set

    private var lastErrLogMs = 0L

    /** 解码器就绪时回调实际视频宽高（供 UI 动态比例去黑边） */
    var onFormatReady: ((Int, Int) -> Unit)? = null

    private data class Frame(val bytes: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    fun start(): Boolean {
        if (running) return true // 幂等
        running = true
        decodeThread = Thread {
            while (running) {
                try {
                    if (decoder == null) {
                        // 等关键帧（含 SPS/PPS）
                        val f = queue.poll()
                        if (f == null || !f.keyframe) {
                            if (f != null) droppedFrames++ // 探针：等关键帧期间丢弃的非关键帧
                            Thread.sleep(5)
                            continue
                        }
                        val fmt = buildFormatFromKeyframe(f.bytes)
                        if (fmt == null) {
                            DiagLog.log("关键帧缺 SPS/PPS，等待下一关键帧")
                            continue
                        }
                        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        // 记录解码器硬件名（证明硬解生效，因 Android 无 GPU 占用 API）
                        try { DiagLog.log("解码器名: " + dec.name) } catch (_: Throwable) {}
                        DiagLog.log("准备configure解码器: w/h=" + (fmt.getInteger(MediaFormat.KEY_WIDTH)) + "x" + fmt.getInteger(MediaFormat.KEY_HEIGHT))
                        try {
                            dec.configure(fmt, previewSurface, null, 0)
                            dec.start()
                        } catch (e: Throwable) {
                            try { dec.release() } catch (_: Exception) {}
                            DiagLog.log("解码器configure失败: " + e.javaClass.simpleName + " " + (e.message ?: ""))
                            throw e
                        }
                        decoder = dec
                        DiagLog.log("解码器已就绪（关键帧同步）")
                        feed(f)
                        continue
                    }
                    val dec = decoder!!
                    while (running && drain(dec)) { /* Release all ready output buffers. */ }
                    val f = queue.poll(4, TimeUnit.MILLISECONDS)
                    if (f != null) feed(f)
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    decodeErrors++ // 探针：解码线程异常
                    logErr(t)
                }
            }
        }.apply { isDaemon = true; name = "sink-dec"; start() }
        DiagLog.log("视频接收渲染已启动")
        return true
    }

    fun push(bytes: ByteArray, keyframe: Boolean, ptsUs: Long) {
        if (!running) return
        if (queue.remainingCapacity() == 0) {
            val skipped = ArrayList<Frame>()
            queue.drainTo(skipped)
            droppedFrames += skipped.size
            droppedKey += skipped.count { it.keyframe }
            needsKeyframe = true
        }
        if (needsKeyframe && !keyframe) {
            droppedFrames++
            return
        }
        queue.offer(Frame(bytes, ptsUs, keyframe))
        needsKeyframe = false
    }

    private fun feed(f: Frame) {
        val dec = decoder ?: return
        while (running) {
            val inIdx = dec.dequeueInputBuffer(4_000)
            if (inIdx >= 0) {
                val input = dec.getInputBuffer(inIdx)!!
                input.clear()
                input.put(f.bytes)
                dec.queueInputBuffer(inIdx, 0, f.bytes.size, f.ptsUs, 0)
                return
            }
            drain(dec)
        }
    }

    private fun drain(dec: MediaCodec): Boolean {
        val outIdx = dec.dequeueOutputBuffer(decInfo, 0)
        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // 解码器输出格式变化：上报真实宽高，供 UI 比例匹配（否则 videoRatio 用默认值会变形）
            try {
                val fmt = dec.outputFormat
                val w = if (fmt.containsKey("crop-right") && fmt.containsKey("crop-left"))
                    fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
                else fmt.getInteger(MediaFormat.KEY_WIDTH)
                val h = if (fmt.containsKey("crop-bottom") && fmt.containsKey("crop-top"))
                    fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
                else fmt.getInteger(MediaFormat.KEY_HEIGHT)
                DiagLog.log("解码输出格式: " + w + "x" + h)
                if (h > 0) onFormatReady?.invoke(w, h)
            } catch (_: Exception) {}
        } else if (outIdx >= 0) {
            val t0 = System.nanoTime()
            val waitUs = decInfo.presentationTimeUs - t0 / 1_000
            if (waitUs > 0) TimeUnit.MICROSECONDS.sleep(waitUs)
            val render = running && waitUs >= -120_000
            dec.releaseOutputBuffer(outIdx, render)
            if (render) renderedFrames++ else discardedFrames++
            lastDecodeMs = (System.nanoTime() - t0) / 1_000_000.0
        } else {
            return false
        }
        return true
    }

    /** 从关键帧里提取 SPS/PPS 构造解码器 MediaFormat；缺任一返回 null */
    private fun buildFormatFromKeyframe(au: ByteArray): MediaFormat? {
        // 诊断：打印首个 NAL 的起始字节，确认帧头结构
        if (au.size >= 8) {
            DiagLog.log("关键帧原始头：0x${au[0].toInt() and 0xff} ${au[1].toInt() and 0xff} ${au[2].toInt() and 0xff} ${au[3].toInt() and 0xff} | ${au[4].toInt() and 0xff} ${au[5].toInt() and 0xff} ${au[6].toInt() and 0xff} ${au[7].toInt() and 0xff}")
        }
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val nals = splitNals(au)
        for (t in nals) {
            val payload = t.second
            if (payload.isEmpty()) continue
            when (t.third) {
                7 -> sps = payload
                8 -> pps = payload
                6 -> {} // SEI 忽略
                5 -> {} // IDR 切片
            }
        }
        DiagLog.log("关键帧 NAL 统计：sp=${sps?.size} pps=${pps?.size}")
        if (sps == null || pps == null) return null
        // 用构造宽高(>0)创建 format；注意 MediaCodec 会以 SPS(csd) 为准确定实际输出尺寸，
        // onFormatReady 实际取自解码输出格式(outputFormat)的真实宽高，故比例仍正确。
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            val startCode = byteArrayOf(0, 0, 0, 1)
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(startCode + sps))
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(startCode + pps))
        }
    }

    /** 拆分 Annex-B NAL：返回 (起始码长度, NAL 载荷不含起始码, nal_type) */
    private fun splitNals(au: ByteArray): List<Triple<Int, ByteArray, Int>> {
        val n = au.size
        if (n < 4) return emptyList()
        val result = ArrayList<Triple<Int, ByteArray, Int>>()
        var i = 0
        // 找第一个起始码
        var firstStart = -1
        while (i + 3 <= n) {
            if (isStartCode(au, i)) { firstStart = i; break }
            i++
        }
        if (firstStart < 0) return emptyList()
        var cur = firstStart
        while (true) {
            // 当前 NAL 起始码长度
            val curLen = startCodeLen(au, cur, n)
            val payloadStart = cur + curLen
            // 找下一个起始码（从 payload 起点之后）
            var next = payloadStart
            var nextStart = -1
            while (next + 3 <= n) {
                if (isStartCode(au, next)) { nextStart = next; break }
                next++
            }
            val payloadEnd = if (nextStart >= 0) nextStart else n
            if (payloadEnd > payloadStart) {
                val payload = au.copyOfRange(payloadStart, payloadEnd)
                val nalType = payload[0].toInt() and 0x1F
                result.add(Triple(curLen, payload, nalType))
            }
            if (nextStart < 0) break
            cur = nextStart
        }
        return result
    }

    private fun isStartCode(au: ByteArray, i: Int): Boolean {
        val n = au.size
        if (i + 3 > n) return false
        if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && (i + 2 < n && au[i + 2] == 1.toByte())) return true
        if (i + 4 <= n && au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 0.toByte() && au[i + 3] == 1.toByte()) return true
        return false
    }

    private fun startCodeLen(au: ByteArray, i: Int, n: Int): Int {
        if (i + 3 < n && au[i + 3] == 1.toByte()) return 4
        return 3
    }

    private fun logErr(t: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastErrLogMs > 2000) {
            lastErrLogMs = now
            Log.e(TAG, "sink error", t)
            DiagLog.log("接收渲染错误：${t.javaClass.simpleName}: ${t.message ?: ""}")
            lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}"
        }
    }

    fun stop() {
        running = false
        decodeThread?.interrupt()
        try { decodeThread?.join(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        decodeThread = null
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        queue.clear()
        DiagLog.log("视频接收渲染已停止")
    }

    companion object {
        private const val TAG = "MirrorCastV3"
    }
}