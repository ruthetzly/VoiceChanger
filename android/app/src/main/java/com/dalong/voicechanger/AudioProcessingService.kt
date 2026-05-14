package com.dalong.voicechanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class AudioProcessingService : Service() {

    companion object {
        private const val TAG = "VoiceChangerService"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_ID = "voice_changer_channel"
        private const val NOTIFICATION_ID = 1
    }

    inner class AudioBinder : android.os.Binder() {
        fun getService(): AudioProcessingService = this@AudioProcessingService
    }

    private val binder = AudioBinder()
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var serverUrl = ""
    private var isProcessing = false
    private var volumeLevel = 0.8f
    private var speakerMode = false // true = 扬声器模式（配合其他APP）
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // 回调
    var statusCallback: ((String) -> Unit)? = null
    var textCallback: ((String, String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun connectToServer(url: String) {
        serverUrl = if (url.startsWith("ws://") || url.startsWith("http://")) url
                    else "ws://$url/api/process_websocket"
        statusCallback?.invoke("服务器地址已设置: $serverUrl")
    }

    fun setVolume(vol: Float) {
        volumeLevel = vol.coerceIn(0f, 1f)
    }

    fun setSpeakerMode(enabled: Boolean) {
        speakerMode = enabled
        initAudioTrack()
    }

    fun startProcessing() {
        if (serverUrl.isEmpty()) {
            statusCallback?.invoke("⚠️ 请先设置服务器地址")
            return
        }
        isProcessing = true
        initAudioRecord()
        initAudioTrack()
        thread { recordingLoop() }
        statusCallback?.invoke("🎤 录音中...(配合Chatous请开启扬声器模式)")
    }

    fun stopProcessing() {
        isProcessing = false
        audioRecord?.apply {
            try { stop() } catch (e: Exception) {}
            release()
        }
        audioRecord = null
        statusCallback?.invoke("⏹ 已停止")
    }

    private fun initAudioRecord() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
            bufferSize * 4
        )
    }

    private fun initAudioTrack() {
        audioTrack?.release()
        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT)
        
        val streamType = if (speakerMode) AudioManager.STREAM_MUSIC 
                         else AudioManager.STREAM_VOICE_CALL
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (speakerMode) AudioAttributes.USAGE_MEDIA 
                              else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 4)
            .build()
    }

    private fun recordingLoop() {
        val record = audioRecord ?: return
        val buffer = ByteArray(4096)
        val audioBuffer = ByteArrayOutputStream()

        try {
            record.startRecording()
            
            while (isProcessing) {
                val bytesRead = record.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    audioBuffer.write(buffer, 0, bytesRead)

                    // 每积累约2秒音频（32000字节），发送一次
                    if (audioBuffer.size() >= 32000) {
                        val audioData = audioBuffer.toByteArray()
                        audioBuffer.reset()
                        sendAudioForProcessing(audioData)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音错误", e)
            statusCallback?.invoke("❌ 录音错误: ${e.message}")
        } finally {
            try { record.stop() } catch (e: Exception) {}
        }
    }

    private fun sendAudioForProcessing(audioData: ByteArray) {
        try {
            // 将PCM转为WAV格式
            val wavData = pcmToWav(audioData)
            
            // 构建multipart请求
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "audio.wav",
                    wavData.toRequestBody("audio/wav".toMediaType()))
                .build()

            // 发送到HTTP API（服务器同时支持HTTP和WS）
            val httpUrl = serverUrl.replace("ws://", "http://")
                .replace("/api/process_websocket", "/api/process")

            val request = Request.Builder()
                .url(httpUrl)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val result = gson.fromJson(body, ProcessResult::class.java)
                    
                    if (result.status == "ok" && result.audio_base64 != null) {
                        // 解码并播放处理后的音频
                        val audioBytes = Base64.decode(result.audio_base64, Base64.DEFAULT)
                        playProcessedAudio(audioBytes)
                        
                        // 更新文字显示
                        textCallback?.invoke(
                            result.original_text ?: "",
                            result.translated_text ?: ""
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "网络请求错误", e)
        }
    }

    private fun playProcessedAudio(mp3Data: ByteArray) {
        try {
            val track = audioTrack ?: return
            if (track.state != AudioTrack.STATE_INITIALIZED) return
            
            track.play()
            
            // 注意：这里接收的是MP3格式（edge-tts输出），需要解码
            // Android支持MediaCodec解码MP3
            // 简化处理：直接播放（实际项目需添加MP3解码器）
            // 对于MVP版本，我们返回MP3，用MediaPlayer解码播放
            
            // 保存到临时文件，用MediaPlayer播放
            val tempFile = File(cacheDir, "output.mp3")
            tempFile.writeBytes(mp3Data)
            
            val mediaPlayer = android.media.MediaPlayer()
            mediaPlayer.setDataSource(tempFile.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.setVolume(volumeLevel, volumeLevel)
            mediaPlayer.start()
        } catch (e: Exception) {
            Log.e(TAG, "播放错误", e)
        }
    }

    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        // WAV header
        baos.write("RIFF".toByteArray())       // ChunkID
        baos.write(intToBytes(fileSize))        // ChunkSize
        baos.write("WAVE".toByteArray())        // Format
        baos.write("fmt ".toByteArray())        // Subchunk1ID
        baos.write(intToBytes(16))              // Subchunk1Size (PCM = 16)
        baos.write(shortToBytes(1))             // AudioFormat (PCM = 1)
        baos.write(shortToBytes(1))             // NumChannels (Mono)
        baos.write(intToBytes(SAMPLE_RATE))     // SampleRate
        baos.write(intToBytes(SAMPLE_RATE * 2)) // ByteRate
        baos.write(shortToBytes(2))             // BlockAlign
        baos.write(shortToBytes(16))            // BitsPerSample
        baos.write("data".toByteArray())        // Subchunk2ID
        baos.write(intToBytes(dataSize))        // Subchunk2Size
        baos.write(pcmData)

        return baos.toByteArray()
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToBytes(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }

    data class ProcessResult(
        val status: String,
        val original_text: String?,
        val translated_text: String?,
        val audio_base64: String?,
        val error: String?
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "变声器服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "大龙变声翻译器后台服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐉 大龙变声翻译器")
            .setContentText("后台运行中...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isProcessing = false
        audioRecord?.release()
        audioTrack?.release()
        super.onDestroy()
    }
}
