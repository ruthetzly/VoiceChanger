package com.dalong.voicechanger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SERVER_URL = "http://101.37.237.237:80"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvRecognized: TextView
    private lateinit var tvTranslated: TextView
    private lateinit var btnRecord: ToggleButton
    private lateinit var seekVolume: SeekBar
    private lateinit var switchSpeaker: SwitchMaterial

    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isListening = false
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var volumeLevel = 0.8f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkPermissions()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        tvStatus.text = "✅ 就绪，点击开始"
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvRecognized = findViewById(R.id.tv_recognized)
        tvTranslated = findViewById(R.id.tv_translated)
        btnRecord = findViewById(R.id.btn_record)
        seekVolume = findViewById(R.id.seek_volume)
        switchSpeaker = findViewById(R.id.switch_speaker)

        btnRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startListening() else stopListening()
        }

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                volumeLevel = p / 100f
                mediaPlayer?.setVolume(volumeLevel, volumeLevel)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchSpeaker.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) tvStatus.text = "📢 外放模式（配合Chatous）"
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun startListening() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvStatus.text = "🎤 请说话..."
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    tvRecognized.text = "你说: $text"
                    translateAndSpeak(text)
                }
                if (btnRecord.isChecked) startListening() // 连续识别
            }
            override fun onError(error: Int) {
                tvStatus.text = "⚠️ 识别错误 ($error)"
                btnRecord.isChecked = false
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说中文...")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        tvStatus.text = "⏹ 已停止"
    }

    private fun translateAndSpeak(text: String) {
        tvTranslated.text = "翻译中..."
        tvStatus.text = "🌐 连接服务器..."

        Thread {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("text", text)
                    .build()

                val request = Request.Builder()
                    .url("$SERVER_URL/api/translate_tts")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val result = gson.fromJson(response.body?.string(), TranslateResult::class.java)
                        runOnUiThread {
                            tvTranslated.text = "🇮🇩 ${result.translated_text}"
                            tvStatus.text = "🔊 播放中..."
                        }
                        if (result.audio_url != null) {
                            playAudio(result.audio_url)
                        }
                    } else {
                        runOnUiThread {
                            tvTranslated.text = "❌ 服务器错误"
                            tvStatus.text = "❌ 连接失败，请检查网络"
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络错误"
                    tvTranslated.text = "请检查代理/网络连接"
                }
            }
        }.start()
    }

    private fun playAudio(url: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(url)
                setVolume(volumeLevel, volumeLevel)
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    runOnUiThread { tvStatus.text = "🎤 可继续说..." }
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            runOnUiThread { tvStatus.text = "❌ 播放失败" }
        }
    }

    data class TranslateResult(
        val status: String,
        val original_text: String,
        val translated_text: String,
        val audio_url: String?,
        val error: String?
    )

    override fun onDestroy() {
        speechRecognizer?.destroy()
        mediaPlayer?.release()
        super.onDestroy()
    }
}
