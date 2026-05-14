package com.dalong.voicechanger

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Base64

class MainActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var btnConnect: Button
    private lateinit var toggleRecord: ToggleButton
    private lateinit var tvStatus: TextView
    private lateinit var tvOriginalText: TextView
    private lateinit var tvTranslatedText: TextView
    private lateinit var sbVolume: SeekBar
    private lateinit var switchChatous: SwitchMaterial

    private var audioService: AudioProcessingService? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioProcessingService.AudioBinder
            audioService = binder.getService()
            audioService?.statusCallback = { status: String ->
                runOnUiThread { tvStatus.text = status }
            }
            audioService?.textCallback = { original: String, translated: String ->
                runOnUiThread {
                    tvOriginalText.text = "识别: $original"
                    tvTranslatedText.text = "翻译: $translated"
                }
            }
            serviceBound = true
            tvStatus.text = "服务已绑定"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            audioService = null
            tvStatus.text = "服务断开"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        requestPermissions()
        startService()
    }

    private fun initViews() {
        etServerUrl = findViewById(R.id.et_server_url)
        btnConnect = findViewById(R.id.btn_connect)
        toggleRecord = findViewById(R.id.toggle_record)
        tvStatus = findViewById(R.id.tv_status)
        tvOriginalText = findViewById(R.id.tv_original_text)
        tvTranslatedText = findViewById(R.id.tv_translated_text)
        sbVolume = findViewById(R.id.sb_volume)
        switchChatous = findViewById(R.id.switch_chatous)

        // 默认服务器地址（大龙的服务器）
        etServerUrl.setText("101.37.237.237:8765")

        btnConnect.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            audioService?.connectToServer(url)
            tvStatus.text = "正在连接 $url..."
        }

        toggleRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                audioService?.startProcessing()
                tvStatus.text = "🎤 变声中..."
            } else {
                audioService?.stopProcessing()
                tvStatus.text = "⏹ 已停止"
            }
        }

        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                audioService?.setVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        switchChatous.setOnCheckedChangeListener { _, isChecked ->
            audioService?.setSpeakerMode(isChecked)
            if (isChecked) {
                tvStatus.text = "📢 扬声器模式（可配合Chatous使用）"
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun startService() {
        val intent = Intent(this, AudioProcessingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
