package com.zeeho.cast.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zeeho.cast.R
import com.zeeho.cast.databinding.ActivityCastBinding
import com.zeeho.cast.ec.ECLog

/**
 * 投屏控制页：状态展示 + 断开 + 日志导出
 */
class CastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCastBinding
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCastBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvCastHint.text = getString(R.string.cast_hint)
        binding.tvStatus.text = getString(R.string.cast_status_connecting)

        binding.btnStop.setOnClickListener {
            Toast.makeText(this, "已发送断开请求，投屏服务正在退出…", Toast.LENGTH_SHORT).show()
            stopService(Intent(this, com.zeeho.cast.CastService::class.java))
            finish()
        }

        binding.btnExport.setOnClickListener {
            val path = com.zeeho.cast.CastService.exportLog(this)
            Toast.makeText(this, getString(R.string.log_exported, path), Toast.LENGTH_LONG).show()
            ECLog.i("ui", "日志导出: $path")
        }
    }
}
