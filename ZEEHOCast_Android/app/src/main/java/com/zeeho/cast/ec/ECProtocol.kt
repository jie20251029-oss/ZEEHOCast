package com.zeeho.cast.ec

/**
 * 极核 AE5i 投屏协议层（基于 MotoPlay/亿连 EC-SDK 逆向实现）
 *
 * 逆向依据（cfmoto-client-ios 3.0.5 + GEMINI_PACK_SW 1.01.05 libECSDK.so）：
 *  - 仪表 WiFi AP，网关 192.168.49.1，TCP 端口 1807（WifiManager::getWifiPort）
 *  - mDNS 服务名 _EasyConn._tcp（Bonjour）
 *  - 第一个命令为 EC_Ping 握手（ECSDKImp::ping）
 *  - 传输类型 EC_TRANSPORT_WIFI
 *  - 命令体系统一由 PXC 类发送（sendVehicleSpeed / sendOdometer / sendTouchEvent ...）
 *  - 镜像为 H.264 视频帧（官方 VideoToolbox/FFmpeg 4000kbps，仪表侧 libECSink 渲染）
 *
 * 注意：帧头字节级布局（magic/命令ID/长度字段）为逆向推断的合理模板，
 *      实测不通过时修改 [ECFrame.TEMPLATE_*] 并导出 HEX 日志对比即可。
 */
object ECConstants {
    /** 仪表 AP 网关（投屏网段） */
    const val DEFAULT_HOST = "192.168.49.1"
    /** EC 服务端口（libECSDK.so WifiManager::getWifiPort 读取值） */
    const val DEFAULT_PORT = 1807
    /** mDNS 服务类型 */
    const val SERVICE_TYPE = "_EasyConn._tcp"
    /** 投屏 mDNS 服务名（部分固件形如 zeeho_XXXX._EasyConn._tcp） */
    const val SERVICE_NAME_PREFIX = "zeeho"

    /** 传输类型：WIFI */
    const val TRANSPORT_WIFI = 0

    /** 版本号（协议版本，推断值可调） */
    const val PROTOCOL_VERSION = 1
}

/** EC 命令 ID（PXC 命令体系，数值为推断映射，实测可调） */
object ECCmd {
    const val PING = 0x0001
    const val PONG = 0x0002
    const val HELLO = 0x0003          // 连接建立后客户端问候（含产品/版本）
    const val OPEN_TRANSPORT = 0x0010 // openTransport(WIFI)
    const val OPEN_MIRROR = 0x0020    // openMirrorConnection(含配置)
    const val START_MIRROR = 0x0021
    const val CLOSE_MIRROR = 0x0022
    const val MIRROR_ACK = 0x0023
    const val VIDEO_FRAME = 0x0030    // H.264 视频帧
    const val VIDEO_INFO = 0x0031     // 视频参数（宽/高/码率）
    const val IFRAME_REQUEST = 0x0032 // 仪表请求关键帧
    const val AUDIO_FRAME = 0x0040    // 音频帧(AAC)
    // --- PXC 车机命令（P2C/C2P，见逆向符号表） ---
    const val BULK_DATA = 0x0100      // 通用透传
    const val CUSTOM_DATA = 0x0101
    const val TOUCH_EVENT = 0x0110
    const val BTN_EVENT = 0x0111
    const val OPEN_APP_PAGE = 0x0120
    const val CLOSE_APP_PAGE = 0x0121
    const val SWITCH_MAIN_PAGE = 0x0122
    const val VEHICLE_SPEED = 0x0200  // 仪表→手机 车速
    const val ENGINE_RPM = 0x0201
    const val ODOMETER = 0x0202
    const val GEAR_STATUS = 0x0203
    const val NIGHT_MODE = 0x0204
    const val LIGHT_STATUS = 0x0205
    const val DRIVING_STATUS = 0x0206
    const val LOCATION = 0x0207
    const val COMPASS = 0x0208
    const val TIRE_PRESSURE = 0x0209
    const val ACCELEROMETER = 0x020A
    const val GYROSCOPE = 0x020B
    const val GPS_SATELLITE = 0x020C
    const val CAR_STATUS = 0x020D
    const val VEHICLE_INFO = 0x020E
    // --- HUD 导航 ---
    const val ENABLE_HUD = 0x0300
    const val DISABLE_HUD = 0x0301
    const val HUD_FRAME = 0x0302
    // --- OTA/License ---
    const val CHECK_SN = 0x0400
    const val CHECK_CAR_UUID = 0x0401
    const val DOWNLOAD_LICENSE = 0x0402
    const val REGISTER_UUID = 0x0403
    const val OTA_STATUS = 0x0404
    const val OTA_DATA = 0x0405
}

/** 帧头字段偏移/常量（推断模板，可调） */
object ECFrame {
    const val HDR_MAGIC = 0
    const val HDR_VER = 4
    const val HDR_CMD = 5        // u16 大端
    const val HDR_FLAGS = 7
    const val HDR_LEN = 8        // u32 大端（payload 长度）
    const val HEADER_SIZE = 12

    /** magic 四字节（推断，可调） */
    val MAGIC: ByteArray = byteArrayOf(0x45, 0x43, 0x41, 0x50) // "ECAP"

    const val FLAG_REQUEST = 0x01
    const val FLAG_RESPONSE = 0x02
    const val FLAG_STREAM = 0x04  // 视频/音频流帧
}

/**
 * 日志总线：所有协议收发的 HEX 明细都经过这里，
 * 供 "导出日志" 功能与协议校准使用。
 */
object ECLog {
    private val listeners = mutableListOf<(String) -> Unit>()
    private val buffer = StringBuffer(8192)

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun i(tag: String, msg: String) {
        val line = "[$tag] $msg\n"
        buffer.append(line)
        if (buffer.length > 65536) buffer.delete(0, 16384)
        listeners.forEach { runCatching { it(line.trim()) } }
    }

    @Synchronized
    fun hex(tag: String, bytes: ByteArray, max: Int = 512) {
        val show = bytes.copyOf(minOf(bytes.size, max))
        val sb = StringBuilder(show.size * 3)
        for (b in show) sb.append("%02X ".format(b and 0xFF))
        if (bytes.size > max) sb.append("…(+${bytes.size - max}B)")
        i(tag, "HEX[${bytes.size}B] $sb")
    }

    @Synchronized
    fun dump(): String = buffer.toString()

    @Synchronized
    fun clear() { buffer.setLength(0) }
}
