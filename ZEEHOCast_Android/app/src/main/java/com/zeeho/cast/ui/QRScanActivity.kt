package com.zeeho.cast.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.zeeho.cast.databinding.ActivityQrScanBinding
import com.zeeho.cast.ec.ECConstants
import com.zeeho.cast.ec.ECLog
import com.zeeho.cast.MainActivity
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * 扫码连接：解析仪表二维码（投屏信息），支持形如
 *  zeeho://192.168.49.1:1807 或纯 IP/URL 格式
 */
class QRScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var analyzing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnCancel.setOnClickListener { finish() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                if (analyzing) return@setAnalyzer
                analyzing = true
                runCatching {
                    val bmp = imageToBitmap(image)
                    val result = decode(bmp)
                    if (result != null) {
                        ECLog.i("qr", "扫码结果: $result")
                        analyzing = false
                        handleResult(result)
                    } else {
                        analyzing = false
                    }
                }.onFailure { analyzing = false }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                ECLog.i("qr", "相机启动失败: ${e.message}")
                analyzing = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageToBitmap(image: ImageProxy): android.graphics.Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride

        val y = ByteArray(yBuf.remaining()); yBuf.get(y)
        val u = ByteArray(uBuf.remaining()); uBuf.get(u)
        val v = ByteArray(vBuf.remaining()); vBuf.get(v)

        val out = IntArray(width * height)
        var idx = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIdx = row * yRow + col
                val uvIdx = (row / 2) * uvRow + (col / 2) * uvPix
                var yy = y[yIdx].toInt() and 0xFF
                var uu = u[uvIdx].toInt() and 0xFF
                var vv = v[uvIdx].toInt() and 0xFF
                // YUV420 → RGB (BT.601)
                var r = (yy + 1.402 * (vv - 128)).toInt()
                var g = (yy - 0.344136 * (uu - 128) - 0.714136 * (vv - 128)).toInt()
                var b = (yy + 1.772 * (uu - 128)).toInt()
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
                out[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return android.graphics.Bitmap.createBitmap(out, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun decode(bmp: android.graphics.Bitmap): String? {
        return try {
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            QRCodeReader().decode(bitmap)?.text
        } catch (e: Exception) { null }
    }

    private fun handleResult(raw: String) {
        runOnUiThread {
            val target = parseTarget(raw)
            if (target != null) {
                Toast.makeText(this, "识别到仪表: ${target.first}:${target.second}", Toast.LENGTH_LONG).show()
                ECLog.i("qr", "目标 ${target.first}:${target.second}")
                // 交给主页发起投屏（回传目标）
                val i = Intent(this, MainActivity::class.java)
                    .putExtra("auto_connect_host", target.first)
                    .putExtra("auto_connect_port", target.second)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                setResult(RESULT_OK, i)
                finish()
            } else {
                Toast.makeText(this, "未识别到有效的仪表地址", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 解析扫码内容 → (host, port) */
    private fun parseTarget(raw: String): Pair<String, Int>? {
        val t = raw.trim()
        // zeeho://host:port
        Pattern.compile("zeeho://([\\d.]+)(?::(\\d+))?", Pattern.CASE_INSENSITIVE)
            .matcher(t).let { if (it.find()) {
                return it.group(1) to (it.group(2)?.toIntOrNull() ?: ECConstants.DEFAULT_PORT)
            } }
        // http://host:port/...
        Pattern.compile("https?://([\\d.]+)(?::(\\d+))?", Pattern.CASE_INSENSITIVE)
            .matcher(t).let { if (it.find()) {
                return it.group(1) to (it.group(2)?.toIntOrNull() ?: ECConstants.DEFAULT_PORT)
            } }
        // 纯 IP
        Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3})(?::(\\d+))?$")
            .matcher(t).let { if (it.find()) {
                return it.group(1) to (it.group(3)?.toIntOrNull() ?: ECConstants.DEFAULT_PORT)
            } }
        return null
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
