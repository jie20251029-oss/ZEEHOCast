package com.zeeho.cast.ec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * EC 协议连接：TCP 1807 + 帧封装 + 握手状态机
 *
 * 状态机（对应 EC-SDK 连接流程）：
 *   CONNECT → PING → HELLO → OPEN_TRANSPORT → OPEN_MIRROR → STREAMING
 */
class ECConnection(
    val host: String,
    val port: Int = ECConstants.DEFAULT_PORT,
) {
    enum class State { IDLE, CONNECTING, HANDSHAKE, READY, STREAMING, ERROR, CLOSED }

    @Volatile var state = State.IDLE
        private set
    @Volatile var error: String? = null
        private set

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var receiver: Thread? = null
    private var seq = 0

    /** 收到仪表主动帧（传感器/IFrame请求/ACK）回调 */
    var onCommand: ((cmd: Int, payload: ByteArray) -> Unit)? = null
    /** 状态变化回调 */
    var onState: ((State) -> Unit)? = null

    @Synchronized
    private fun setState(s: State) {
        state = s
        onState?.invoke(s)
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            setState(State.CONNECTING)
            ECLog.i("conn", "连接 $host:$port")
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), 5000)
            socket = sock
            input = BufferedInputStream(sock.getInputStream(), 65536)
            output = BufferedOutputStream(sock.getOutputStream(), 65536)
            startReceiver()
            // 握手：PING → 等 PONG
            setState(State.HANDSHAKE)
            sendFrame(ECCmd.PING, byteArrayOf(ECConstants.PROTOCOL_VERSION.toByte(), 0, 0, 0), ECFrame.FLAG_REQUEST)
            true
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            ECLog.i("conn", "连接失败: $error")
            setState(State.ERROR)
            close()
            false
        }
    }

    private fun startReceiver() {
        receiver = Thread {
            val inp = input ?: return@Thread
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val magic = inp.readNBytes(ECFrame.HEADER_SIZE)
                    if (magic.isEmpty) { ECLog.i("conn", "对端关闭"); break }
                    // 校验 magic
                    if (magic.size < ECFrame.HEADER_SIZE) { ECLog.i("conn", "短帧"); break }
                    val ok = ECFrame.MAGIC.contentEquals(magic.copyOf(4))
                    val cmd = ((magic[ECFrame.HDR_CMD].toInt() and 0xFF) shl 8) or (magic[ECFrame.HDR_CMD + 1].toInt() and 0xFF)
                    val flags = magic[ECFrame.HDR_FLAGS].toInt() and 0xFF
                    val len = ((magic[ECFrame.HDR_LEN].toInt() and 0xFF) shl 24) or
                            ((magic[ECFrame.HDR_LEN + 1].toInt() and 0xFF) shl 16) or
                            ((magic[ECFrame.HDR_LEN + 2].toInt() and 0xFF) shl 8) or
                            (magic[ECFrame.HDR_LEN + 3].toInt() and 0xFF)
                    if (!ok) {
                        ECLog.i("conn", "magic不匹配, cmd=$cmd len=$len 已断开")
                        break
                    }
                    if (len > 4 * 1024 * 1024) { ECLog.i("conn", "异常长度 $len"); break }
                    var payload = ByteArray(0)
                    if (len > 0) {
                        payload = inp.readNBytes(len)
                        if (payload.size < len) { ECLog.i("conn", "payload截断"); break }
                    }
                    ECLog.hex("rx", byteArrayOf(*magic, *payload))
                    handleFrame(cmd, flags, payload)
                }
            } catch (e: EOFException) {
            } catch (e: Exception) {
                if (state != State.CLOSED) ECLog.i("conn", "接收线程: ${e.message}")
            } finally {
                if (state != State.CLOSED) { error = "连接中断"; setState(State.ERROR) }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handleFrame(cmd: Int, flags: Int, payload: ByteArray) {
        when (cmd) {
            ECCmd.PONG -> {
                ECLog.i("conn", "收到 PONG，握手成功")
                setState(State.READY)
                // 发送 HELLO（产品信息）
                val hello = "ZEEHOCast/1.0".toByteArray()
                sendFrame(ECCmd.HELLO, hello, ECFrame.FLAG_REQUEST)
            }
            ECCmd.MIRROR_ACK -> {
                ECLog.i("conn", "镜像通道已确认")
                setState(State.STREAMING)
            }
            ECCmd.IFRAME_REQUEST -> {
                ECLog.i("conn", "仪表请求关键帧")
                onCommand?.invoke(cmd, payload)
            }
            else -> onCommand?.invoke(cmd, payload)
        }
    }

    /** 发送一帧（带命令ID+payload） */
    @Synchronized
    fun sendFrame(cmd: Int, payload: ByteArray, flags: Int = ECFrame.FLAG_REQUEST) {
        val out = output ?: return
        try {
            val header = ByteArray(ECFrame.HEADER_SIZE)
            ECFrame.MAGIC.copyInto(header, ECFrame.HDR_MAGIC)
            header[ECFrame.HDR_VER] = ECConstants.PROTOCOL_VERSION.toByte()
            header[ECFrame.HDR_CMD] = ((cmd shr 8) and 0xFF).toByte()
            header[ECFrame.HDR_CMD + 1] = (cmd and 0xFF).toByte()
            header[ECFrame.HDR_FLAGS] = flags.toByte()
            val len = payload.size
            header[ECFrame.HDR_LEN] = ((len shr 24) and 0xFF).toByte()
            header[ECFrame.HDR_LEN + 1] = ((len shr 16) and 0xFF).toByte()
            header[ECFrame.HDR_LEN + 2] = ((len shr 8) and 0xFF).toByte()
            header[ECFrame.HDR_LEN + 3] = (len and 0xFF).toByte()
            out.write(header)
            if (len > 0) out.write(payload)
            out.flush()
            ECLog.hex("tx", byteArrayOf(*header, *payload))
        } catch (e: Exception) {
            ECLog.i("conn", "发送失败: ${e.message}")
            if (state != State.CLOSED) { error = "发送失败"; setState(State.ERROR) }
        }
    }

    /** 打开镜像通道（带分辨率/码率配置） */
    fun openMirror(width: Int, height: Int, bitrate: Int, fps: Int) {
        // 配置载荷：宽(u16) 高(u16) 码率(u32) 帧率(u8) 传输类型(u8) 预留
        val p = ByteArray(12)
        p[0] = ((width shr 8) and 0xFF).toByte(); p[1] = (width and 0xFF).toByte()
        p[2] = ((height shr 8) and 0xFF).toByte(); p[3] = (height and 0xFF).toByte()
        p[4] = ((bitrate shr 24) and 0xFF).toByte(); p[5] = ((bitrate shr 16) and 0xFF).toByte()
        p[6] = ((bitrate shr 8) and 0xFF).toByte(); p[7] = (bitrate and 0xFF).toByte()
        p[8] = fps.toByte()
        p[9] = ECConstants.TRANSPORT_WIFI.toByte()
        p[10] = 0; p[11] = 0
        sendFrame(ECCmd.OPEN_MIRROR, p)
    }

    fun startMirror() {
        sendFrame(ECCmd.START_MIRROR, byteArrayOf(0))
    }

    /** 推视频帧（H.264），可选关键帧标记 */
    fun sendVideoFrame(data: ByteArray, isKeyFrame: Boolean) {
        val flags = if (isKeyFrame) ECFrame.FLAG_STREAM or 0x08 else ECFrame.FLAG_STREAM
        sendFrame(ECCmd.VIDEO_FRAME, data, flags)
    }

    fun sendVideoInfo(width: Int, height: Int, bitrate: Int) {
        val p = ByteArray(12)
        p[0] = ((width shr 8) and 0xFF).toByte(); p[1] = (width and 0xFF).toByte()
        p[2] = ((height shr 8) and 0xFF).toByte(); p[3] = (height and 0xFF).toByte()
        p[4] = ((bitrate shr 24) and 0xFF).toByte(); p[5] = ((bitrate shr 16) and 0xFF).toByte()
        p[6] = ((bitrate shr 8) and 0xFF).toByte(); p[7] = (bitrate and 0xFF).toByte()
        p[8] = 0; p[9] = 0; p[10] = 0; p[11] = 0
        sendFrame(ECCmd.VIDEO_INFO, p)
    }

    /** 发送自定义数据（对应 PXC::sendBulkDataToPhone） */
    fun sendCustomData(data: ByteArray) {
        sendFrame(ECCmd.CUSTOM_DATA, data)
    }

    fun close() {
        val s = state
        if (s == State.CLOSED) return
        ECLog.i("conn", "关闭连接")
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        setState(State.CLOSED)
    }
}
