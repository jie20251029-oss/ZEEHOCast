package com.zeeho.cast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.zeeho.cast.ec.ECConnection
import com.zeeho.cast.ec.ECConstants
import com.zeeho.cast.ec.ECLog
import kotlinx.coroutines.*

/**
 * 投屏前台服务：MediaProjection 录屏 + H.264 编码 + EC 协议推流
 * 前台服务保证熄屏后进程不被回收（熄屏投屏）。
 */
class CastService : Service() {

    companion object {
        private const val CHANNEL_ID = "zeeho_cast"
        private const val NOTIF_ID = 1001
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        fun start(context: Context, resultData: Intent, host: String, port: Int) {
            val i = Intent(context, CastService::class.java)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
            context.startForegroundService(i)
        }

        /** 导出协议日志到应用外部目录 */
        fun exportLog(context: Context): String {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = java.io.File(dir, "zeeho_cast_${System.currentTimeMillis()}.log")
            java.io.FileOutputStream(f).use { it.write(ECLog.dump().toByteArray()) }
            return f.absolutePath
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var conn: ECConnection? = null
    private var job: Job? = null

    private var host = ECConstants.DEFAULT_HOST
    private var port = ECConstants.DEFAULT_PORT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        host = intent?.getStringExtra(EXTRA_HOST) ?: ECConstants.DEFAULT_HOST
        port = intent?.getIntExtra(EXTRA_PORT, ECConstants.DEFAULT_PORT) ?: ECConstants.DEFAULT_PORT
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        ECLog.i("svc", "启动投屏: $host:$port")
        if (resultData != null) {
            startCasting(resultData)
        }
        return START_NOT_STICKY
    }

    private fun startCasting(resultData: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection = mgr.getMediaProjection(Activity.RESULT_OK, resultData)
        } catch (e: Exception) {
            ECLog.i("svc", "无法获取 MediaProjection: ${e.message}")
            stopSelf()
            return
        }
        val proj = projection ?: run { stopSelf(); return }

        job = scope.launch {
            // 1. 编码器（仪表 1024x600 横屏，官方 480P；手机竖屏镜像，使用 720 宽对齐）
            val enc = ScreenEncoder(720, 1280, bitrate = 4_000_000, fps = 30) { data, key ->
                conn?.sendVideoFrame(data, key)
            }
            encoder = enc
            enc.start()
            val surface = enc.inputSurface ?: run { stopSelf(); return@launch }

            // 2. VirtualDisplay（内容投射到编码器输入面）
            virtualDisplay = proj.createVirtualDisplay(
                "ZEEHOCast",
                720, 1280,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            // 3. EC 连接 + 握手 + 开镜像
            val c = ECConnection(host, port)
            conn = c
            c.onCommand = { cmd, _ ->
                if (cmd == com.zeeho.cast.ec.ECCmd.IFRAME_REQUEST) enc.requestKeyFrame()
            }
            c.onState = { s ->
                updateNotification("投屏状态: $s")
                ECLog.i("svc", "状态: $s")
                if (s == ECConnection.State.ERROR || s == ECConnection.State.CLOSED) {
                    stopAll()
                }
            }
            val ok = c.connect()
            if (!ok) {
                updateNotification("连接失败: ${c.error}")
                stopAll()
                return@launch
            }
            // 握手成功后开镜像
            c.openMirror(720, 1280, 4_000_000, 30)
            c.sendVideoInfo(720, 1280, 4_000_000)
            c.startMirror()
            ECLog.i("svc", "镜像通道已打开，开始推流")
        }
    }

    private fun stopAll() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { encoder?.stop() }
        encoder = null
        runCatching { conn?.close() }
        conn = null
        runCatching { projection?.stop() }
        projection = null
        job?.cancel()
        job = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    // ---------- 通知 ----------
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "投屏服务", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun startForegroundCompat() {
        val notif = buildNotification("正在建立投屏通道…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, ui.CastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("极核投屏")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** 导出协议日志到 Download 目录（实例版，供界面调用） */
    fun exportLogInstance(): String = exportLog(this)
}
