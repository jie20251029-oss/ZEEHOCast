package com.zeeho.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 屏幕编码器：MediaCodec H.264 硬编码（对应官方 h264_videotoolbox 4000k 方案）
 * 输出 AVCC 格式，发送前转为 AnnexB 或直接按帧发送（可配置）。
 */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 4_000_000,
    private val fps: Int = 30,
    private val onFrame: (data: ByteArray, isKeyFrame: Boolean) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var cachedSurface: Surface? = null

    val inputSurface: Surface?
        get() {
            if (cachedSurface == null) cachedSurface = codec?.createInputSurface()
            return cachedSurface
        }

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderBitrateMode.VBR)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        drainLoop()
    }

    /** 持续从编码器取输出并回调（内部线程） */
    private fun drainLoop() {
        val thread = Thread {
            val c = codec ?: return@Thread
            val info = MediaCodec.BufferInfo()
            val bufferInfo = info
            try {
                while (true) {
                    val idx = c.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = c.outputFormat
                            ECLog.i("enc", "输出格式: ${fmt.getString(MediaFormat.KEY_MIME)} " +
                                    "${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)}")
                        }
                        idx >= 0 -> {
                            val buf: ByteBuffer = c.getOutputBuffer(idx) ?: continue
                            val size = bufferInfo.size
                            val data = ByteArray(size)
                            buf.position(bufferInfo.offset)
                            buf.get(data, 0, size)
                            val isKey = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            // 判断是否有 SPS/PPS（AVCC 以 00 00 00 01 开头转 AnnexB）
                            if (isKey && size >= 4 && (data[0].toInt() and 0xFF) == 0 && (data[1].toInt() and 0xFF) == 0 &&
                                (data[2].toInt() and 0xFF) == 0 && (data[3].toInt() and 0xFF) == 1) {
                                // 已是 AnnexB，直接发
                                onFrame(data, true)
                            } else {
                                // AVCC 转 AnnexB：把每个 NALU 长度前缀(4B)换成 00 00 00 01
                                val annex = avccToAnnexB(data)
                                onFrame(annex, isKey)
                            }
                            c.releaseOutputBuffer(idx, false)
                        }
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 等待 */ }
                    }
                }
            } catch (e: IllegalStateException) {
                ECLog.i("enc", "编码器停止: ${e.message}")
            } catch (e: Exception) {
                ECLog.i("enc", "编码异常: ${e.message}")
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    /** AVCC(4B长度前缀) → AnnexB(00 00 00 01) */
    private fun avccToAnnexB(input: ByteArray): ByteArray {
        var i = 0
        val out = java.io.ByteArrayOutputStream(input.size + 64)
        while (i + 4 <= input.size) {
            val naluLen = ((input[i].toInt() and 0xFF) shl 24) or
                    ((input[i + 1].toInt() and 0xFF) shl 16) or
                    ((input[i + 2].toInt() and 0xFF) shl 8) or
                    (input[i + 3].toInt() and 0xFF)
            if (naluLen <= 0 || i + 4 + naluLen > input.size) {
                // 异常，直接带余下
                out.write(input, i, input.size - i)
                break
            }
            out.write(0); out.write(0); out.write(0); out.write(1)
            out.write(input, i + 4, naluLen)
            i += 4 + naluLen
        }
        return out.toByteArray()
    }

    /** 请求下一个关键帧（仪表 IFrame 请求） */
    fun requestKeyFrame() {
        runCatching {
            val b = android.os.Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(b)
        }
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
