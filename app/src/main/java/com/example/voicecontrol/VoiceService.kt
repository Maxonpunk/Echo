package com.example.voicecontrol

import android.Manifest
import android.R
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlin.math.sqrt

class VoiceService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var isEchoModeActive = false
    private var savedVolume = 0
    private var echoTimerJob: Job? = null

    // Защита от дребезга
    private var lastCommandTime = 0L
    private val commandCooldownMs = 1500L

    // Объекты Sherpa
    private var recognizer: OnlineRecognizer? = null
    private var denoiser: OnlineSpeechDenoiser? = null
    private lateinit var stream: OnlineStream

    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(1024)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopServiceCleanly()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, VoiceService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "voice_channel")
            .setContentTitle("Голосовое управление")
            .setContentText("Слушаю команды...")
            .setSmallIcon(R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        if (!isListening) {
            isListening = true
            serviceScope.launch(Dispatchers.IO) {
                initSherpa()
                if (isListening) startAudioRecording()
            }
        }

        getSharedPreferences("voice_control_prefs", MODE_PRIVATE)
            .edit().putBoolean("is_service_running", true).apply()

        return START_STICKY
    }

    private fun initSherpa() {
        SherpaSingleton.initialize(this)
        recognizer = SherpaSingleton.getRecognizer()
        denoiser = SherpaSingleton.getDenoiser()

        if (recognizer == null) {
            Log.e("SHERPA_DEBUG", "Recognizer не загрузился")
            stopServiceCleanly()
            return
        }
        stream = recognizer!!.createStream()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioRecording() {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e("SHERPA_DEBUG", "Ошибка создания AudioRecord: ${e.message}")
            stopServiceCleanly()
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("SHERPA_DEBUG", "AudioRecord не инициализирован")
            stopServiceCleanly()
            return
        }

        audioRecord?.startRecording()
        val buffer = ShortArray(bufferSize)

        while (isListening) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
                // VAD: Вычисляем громкость (RMS). Если тихо — пропускаем обработку.
                var sum = 0.0
                for (i in 0 until read) sum += buffer[i] * buffer[i]
                val rms = sqrt(sum / read)

                if (rms < 500.0) {
                    if (recognizer?.isEndpoint(stream) == true) recognizer?.reset(stream)
                    continue
                }

                val floatBuffer = FloatArray(read)
                for (i in 0 until read) floatBuffer[i] = buffer[i] / 32768.0f

                val denoised = denoiser?.run(floatBuffer, sampleRate)?.samples ?: floatBuffer
                stream.acceptWaveform(denoised, sampleRate)

                while (recognizer?.isReady(stream) == true) {
                    recognizer?.decode(stream)
                }

                val text = recognizer?.getResult(stream)?.text ?: ""
                if (text.isNotBlank()) {
                    Log.d("SHERPA_DEBUG", "Текст: $text")
                    processCommand(text)
                    recognizer?.reset(stream)
                }
            } else {
                Thread.sleep(10)
            }
        }
    }

    private fun processCommand(text: String) {
        val currentTime = System.currentTimeMillis()
        var lowerText = text.lowercase()

        // Защита от дребезга для ВСЕХ команд
        if (currentTime - lastCommandTime < commandCooldownMs) return

        if (lowerText.contains("эхо")) {
            activateEchoMode()
            lowerText = lowerText.replace("эхо", "").trim()
            if (lowerText.isEmpty()) {
                lastCommandTime = currentTime
                return
            }
        }

        when {
            lowerText.contains("след") || lowerText.contains("вперед") || lowerText.contains("вперёд") ||
                    lowerText.contains("дальше") || lowerText.contains("переключ") ||
                    lowerText.contains("скип") || lowerText.contains("пропуст") -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }

            lowerText.contains("пред") || lowerText.contains("назад") ||
                    lowerText.contains("верн") || lowerText.contains("отмот") -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }

            lowerText.contains("пауз") || lowerText.contains("стоп") ||
                    lowerText.contains("останов") || lowerText.contains("замр") ||
                    lowerText.contains("тишин") -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }

            lowerText.contains("игра") || lowerText.contains("плей") ||
                    lowerText.contains("продолж") || lowerText.contains("вруб") -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }

            lowerText.contains("громч") || lowerText.contains("добав") ||
                    lowerText.contains("плюс") || lowerText.contains("увелич") -> {
                changeVolume(true)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }

            lowerText.contains("тиш") || lowerText.contains("убав") ||
                    lowerText.contains("минус") || lowerText.contains("уменьш") -> {
                changeVolume(false)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }

            lowerText.contains("максим") || lowerText.contains("на всю") ||
                    lowerText.contains("полную") -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }

            lowerText.contains("миним") || lowerText.contains("в ноль") ||
                    lowerText.contains("тихо") -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }

            lowerText.contains("жоп") -> {
                val intent = Intent("com.example.voicecontrol.SHOW_ASS").apply { setPackage(packageName) }
                sendBroadcast(intent)
                lastCommandTime = currentTime
                if (isEchoModeActive) deactivateEchoMode()
            }
        }
    }

    private fun activateEchoMode() {
        if (isEchoModeActive) {
            echoTimerJob?.cancel()
        } else {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (savedVolume * 0.8).toInt(), 0)
            isEchoModeActive = true
        }

        echoTimerJob?.cancel()
        echoTimerJob = serviceScope.launch {
            delay(5000)
            deactivateEchoMode()
        }
    }

    private fun deactivateEchoMode() {
        if (!isEchoModeActive) return
        echoTimerJob?.cancel()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
        isEchoModeActive = false
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun changeVolume(increase: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun stopServiceCleanly() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        if (::stream.isInitialized) stream.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSharedPreferences("voice_control_prefs", MODE_PRIVATE)
            .edit().putBoolean("is_service_running", false).apply()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServiceCleanly()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("voice_channel", "Voice Control", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}