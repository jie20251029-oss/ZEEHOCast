package com.zeeho.cast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zeeho.cast.databinding.ActivityMainBinding
import com.zeeho.cast.ec.ECConstants
import com.zeeho.cast.ec.ECDiscovery
import com.zeeho.cast.ec.ECLog
import com.zeeho.cast.ui.CastActivity
import com.zeeho.cast.ui.QRScanActivity
import kotlinx.coroutines.*

/**
 * 极核投屏 · 主界面
 * 三种连接方式：自动发现(mDNS _EasyConn._tcp) / 扫码 / 手动IP
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var discovery: ECDiscovery? = null
    private val devices = mutableListOf<ECDiscovery.Device>()
    private lateinit var deviceAdapter: DeviceAdapter

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                pendingTarget?.let { (host, port) ->
                    CastService.start(this, result.data!!, host, port)
                    startActivity(Intent(this, CastActivity::class.java))
                }
            } else {
                Toast.makeText(this, "未授权录屏，无法投屏", Toast.LENGTH_SHORT).show()
            }
        }

    private var pendingTarget: Pair<String, Int>? = null

    private val qrLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val d = result.data!!
                val host = d.getStringExtra("auto_connect_host")
                val port = d.getIntExtra("auto_connect_port", ECConstants.DEFAULT_PORT)
                if (host != null) {
                    binding.etIp.setText(host)
                    binding.etPort.setText(port.toString())
                    setTab(2)
                    connectTo(host, port)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ECLog.i("ui", "主界面启动")
        setupRecycler()
        setupTabs()
        setupActions()
    }

    private fun setupRecycler() {
        deviceAdapter = DeviceAdapter(devices) { d ->
            connectTo(d.host, d.port)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupTabs() {
        binding.tabDiscover.setOnClickListener {
            setTab(0)
            startDiscovery()
        }
        binding.tabQr.setOnClickListener {
            setTab(1)
            stopDiscovery()
        }
        binding.tabManual.setOnClickListener {
            setTab(2)
            stopDiscovery()
        }
        setTab(0)
        startDiscovery()
    }

    private fun setTab(i: Int) {
        binding.tabDiscover.isSelected = i == 0
        binding.tabQr.isSelected = i == 1
        binding.tabManual.isSelected = i == 2
        binding.panelDiscover.visibility = if (i == 0) View.VISIBLE else View.GONE
        binding.panelQr.visibility = if (i == 1) View.VISIBLE else View.GONE
        binding.panelManual.visibility = if (i == 2) View.VISIBLE else View.GONE
    }

    private fun setupActions() {
        binding.btnRefresh.setOnClickListener { startDiscovery() }
        binding.btnQrScan.setOnClickListener {
            if (checkCamera()) qrLauncher.launch(Intent(this, QRScanActivity::class.java))
        }
        binding.btnConnect.setOnClickListener { doManualConnect() }
        binding.btnExportLog.setOnClickListener { exportLog() }
        binding.tvDisclaimer.text = getString(com.zeeho.cast.R.string.disclaimer)
    }

    // ---------- 自动发现 ----------
    private fun startDiscovery() {
        binding.tvHint.text = getString(R.string.hint_searching)
        binding.emptyView.visibility = View.GONE
        binding.rvDevices.visibility = View.VISIBLE
        discovery?.stop()
        devices.clear()
        deviceAdapter.notifyDataSetChanged()
        discovery = ECDiscovery(this).also { d ->
            d.start(
                onFound = { dev ->
                    scope.launch {
                        if (!devices.any { it.host == dev.host && it.port == dev.port }) {
                            devices.add(dev)
                            deviceAdapter.notifyDataSetChanged()
                            binding.emptyView.visibility = View.GONE
                        }
                    }
                },
                onDone = {
                    scope.launch {
                        if (devices.isEmpty()) {
                            binding.emptyView.visibility = View.VISIBLE
                            binding.rvDevices.visibility = View.GONE
                        }
                    }
                }
            )
        }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    // ---------- 手动连接 ----------
    private fun doManualConnect() {
        val host = binding.etIp.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()
        if (host.isEmpty()) { binding.etIp.error = "请输入 IP"; return }
        val port = portStr.toIntOrNull() ?: ECConstants.DEFAULT_PORT
        connectTo(host, port)
    }

    /** 请求录屏权限并启动投屏 */
    private fun connectTo(host: String, port: Int) {
        pendingTarget = host to port
        ECLog.i("ui", "连接目标 $host:$port，请求录屏授权")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    // ---------- 扫码 ----------
    private fun checkCamera(): Boolean {
        if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
            return false
        }
        return true
    }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) qrLauncher.launch(Intent(this, QRScanActivity::class.java))
            else Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show()
        }

    // ---------- 日志 ----------
    private fun exportLog() {
        val path = CastService.exportLog(this)
        Toast.makeText(this, "日志已导出: $path", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        stopDiscovery()
        scope.cancel()
        super.onDestroy()
    }
}

/** 设备列表适配器 */
class DeviceAdapter(
    private val items: MutableList<ECDiscovery.Device>,
    private val onClick: (ECDiscovery.Device) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    class VH(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.view.findViewById<android.widget.TextView>(R.id.tvDevName).text = d.name
        holder.view.findViewById<android.widget.TextView>(R.id.tvDevAddr).text = "${d.host}:${d.port}"
        holder.view.findViewById<android.widget.TextView>(R.id.tvDevTag).text = "AE5i"
        holder.view.setOnClickListener { onClick(d) }
    }

    override fun getItemCount() = items.size
}
