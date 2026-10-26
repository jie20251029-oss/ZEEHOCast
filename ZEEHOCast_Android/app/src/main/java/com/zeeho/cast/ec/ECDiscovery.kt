package com.zeeho.cast.ec

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * 仪表自动发现：mDNS 查询 _EasyConn._tcp（端口 1807）
 * 对应 libECSDK.so 内置的 Bonjour/mDNSResponder 实现。
 */
class ECDiscovery(private val context: Context) {

    data class Device(
        val name: String,
        val host: String,
        val port: Int,
    )

    private var nsdManager: NsdManager? = null
    private var active = false
    private var currentListener: NsdManager.DiscoveryListener? = null

    fun start(onFound: (Device) -> Unit, onDone: (() -> Unit)? = null) {
        stop()
        val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = mgr
        active = true
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                ECLog.i("mdns", "发现启动: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                ECLog.i("mdns", "发现服务: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                // 解析 host/port
                mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        ECLog.i("mdns", "解析失败 code=$errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        onFound(
                            Device(
                                name = serviceInfo.serviceName,
                                host = host,
                                port = if (serviceInfo.port > 0) serviceInfo.port else ECConstants.DEFAULT_PORT,
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                ECLog.i("mdns", "服务丢失: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                onDone?.invoke()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                ECLog.i("mdns", "启动失败 code=$errorCode")
                onDone?.invoke()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                ECLog.i("mdns", "停止失败 code=$errorCode")
            }
        }
        currentListener = listener
        try {
            mgr.discoverServices(ECConstants.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            ECLog.i("mdns", "异常: ${e.message}")
            onDone?.invoke()
        }
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { currentListener?.let { nsdManager?.stopServiceDiscovery(it) } }
        currentListener = null
        runCatching { nsdManager = null }
    }
}
