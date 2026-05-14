package com.dalong.voicechanger

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val SERVER_URL = "http://101.37.237.237:80"
        private const val MODEL_URL = "$SERVER_URL/vosk-model-small-cn-0.22.zip"
        private const val MODEL_DIR = "vosk-model-small-cn-0.22"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvRecognized: TextView
    private lateinit var tvTranslated: TextView
    private lateinit var btnRecord: ToggleButton
    private lateinit var seekVolume: SeekBar
    private lateinit var switchSpeaker: SwitchMaterial
    private lateinit var progressBar: ProgressBar

    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var recognizedText = ""
    private var volumeLevel = 0.8f
    private var speakerMode = false

    // 用于 SwitchMaterial
    import com.google.android.material.switchmaterial.SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LibVosk.setLogLevel(LogLevel.WARNINGS)
        initViews()
        checkPermissions()
        initModel()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvRecognized = findViewById(R.id.tv_recognized)
        tvTranslated = findViewById(R.id.tv_translated)
        btnRecord = findViewById(R.id.btn_record)
        seekVolume = findViewById(R.id.seek_volume)
        switchSpeaker = findViewById(R.id.switch_speaker)
        progressBar = findViewById(R.id.progress_bar)

        btnRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startRecording()
            } else {
                stopRecording()
            }
        }

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                volumeLevel = p / 100f
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchSpeaker.setOnCheckedChangeListener { _, isChecked ->
            speakerMode = isChecked
            if (isChecked) {
                tvStatus.text = "📢 扬声器模式（配合Chatous等APP使用）"
            }
        }
    }

    private fun checkPermissions() {
        val perms = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
        }
    }

    private fun initModel() {
        tvStatus.text = "⏳ 加载语音模型..."
        val modelPath = File(filesDir, MODEL_DIR)

        if (modelPath.exists()) {
            loadModel(modelPath)
        } else {
            downloadModel(modelPath)
        }
    }

    private fun downloadModel(targetDir: File) {
        tvStatus.text = "⏳ 首次使用，下载语音模型(约40MB)..."
        progressBar.visibility = ProgressBar.VISIBLE

        Thread {
            try {
                val url = URL(MODEL_URL)
                val conn = url.openConnection()
                conn.connectTimeout = 30000
                conn.readTimeout = 60000

                val zipFile = File(cacheDir, "vosk-model.zip")
                val input = conn.getInputStream()
                val output = FileOutputStream(zipFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0
                val totalSize = conn.contentLength

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalSize > 0) {
                        val percent = totalRead * 100 / totalSize
                        runOnUiThread {
                            progressBar.progress = percent
                            tvStatus.text = "⏳ 下载中 $percent%"
                        }
                    }
                }
                output.close()
                input.close()

                // 解压
                runOnUiThread { tvStatus.text = "⏳ 解压模型中..." }
                unzip(zipFile, filesDir)
                zipFile.delete()

                runOnUiThread { loadModel(targetDir) }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 模型下载失败: ${e.message}"
                    progressBar.visibility = ProgressBar.GONE
                }
            }
        }.start()
    }

    private fun unzip(zipFile: File, targetDir: File) {
        val zipInputStream = java.util.zip.ZipInputStream(FileInputStream(zipFile))
        var entry = zipInputStream.nextEntry
        val buffer = ByteArray(8192)
        while (entry != null) {
            val file = File(targetDir, entry.name)
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                val fos = FileOutputStream(file)
                var len: Int
                while (zipInputStream.read(buffer).also { len = it } != -1) {
                    fos.write(buffer, 0, len)
                }
                fos.close()
            }
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }
        zipInputStream.close()
    }

    private fun loadModel(modelDir: File) {
        try {
            voskModel = Model(modelDir.absolutePath)
            runOnUiThread {
                tvStatus.text = "✅ 已就绪，点击开始变声"
                progressBar.visibility = ProgressBar.GONE
                btnRecord.isEnabled = true
            }
        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "❌ 模型加载失败: ${e.message}"
            }
        }
    }

    private fun startRecording() {
        val model = voskModel ?: return
        recognizer = Recognizer(model, SAMPLE_RATE)

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )

        isRecording = true
        audioRecord?.startRecording()
        tvStatus.text = "🎤 录音中...（说中文）"
        tvRecognized.text = ""
        tvTranslated.text = ""

        Thread {
            val buffer = ByteArray(4096)
            val textBuffer = StringBuilder()

            while (isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0 && recognizer != null) {
                    if (recognizer!!.acceptWaveForm(buffer, bytesRead)) {
                        val result = recognizer!!.result
                        val partial = parseJson(result, "text")
                        if (partial.isNotEmpty()) {
                            textBuffer.append(partial).append(" ")
                            runOnUiThread {
                                tvRecognized.text = "你说: $textBuffer"
                            }
                            // 每句话说完自动翻译
                            if (partial.endsWith("。") || partial.endsWith("？") || partial.endsWith("！") || partial.endsWith(".") || partial.endsWith("?")) {
                                val finalText = textBuffer.toString().trim()
                                textBuffer.clear()
                                sendToServer(finalText)
                            }
                        }
                    } else {
                        val partial = recognizer!!.partialResult
                        val text = parseJson(partial, "partial")
                        if (text.isNotEmpty()) {
                            runOnUiThread {
                                tvRecognized.text = "你说: $textBuffer$text"
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        audioRecord = null
        recognizer?.free()
        recognizer = null
        tvStatus.text = "⏹ 已停止"
        btnRecord.isChecked = false
    }

    private fun sendToServer(text: String) {
        Thread {
            try {
                tvStatus.text = "🌐 翻译中..."
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("text", text)
                    .addFormDataPart("target_lang", "id")
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
                            tvStatus.text = "🔊 播放女声..."
                        }

                        // 下载并播放音频
                        if (result.audio_url != null) {
                            playAudio(result.audio_url)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络错误: ${e.message}"
                    tvTranslated.text = "翻译失败，请检查网络"
                }
            }
        }.start()
    }

    private fun playAudio(audioUrl: String) {
        try {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(audioUrl)
            mediaPlayer.setVolume(volumeLevel, volumeLevel)
            mediaPlayer.setOnPreparedListener {
                it.start()
                runOnUiThread { tvStatus.text = "🔊 播放中（扬声器模式可配合Chatous）" }
            }
            mediaPlayer.setOnCompletionListener {
                it.release()
                runOnUiThread {
                    tvStatus.text = if (isRecording) "🎤 继续录音..." else "⏹ 播放完成"
                }
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            runOnUiThread { tvStatus.text = "❌ 播放失败: ${e.message}" }
        }
    }

    private fun parseJson(json: String, key: String): String {
        return try {
            val obj = gson.fromJson(json, Map::class.java)
            obj[key] as? String ?: ""
        } catch (e: Exception) { "" }
    }

    data class TranslateResult(
        val status: String,
        val original_text: String,
        val translated_text: String,
        val audio_url: String?,
        val error: String?
    )

    override fun onDestroy() {
        stopRecording()
        voskModel?.free()
        super.onDestroy()
    }
}
