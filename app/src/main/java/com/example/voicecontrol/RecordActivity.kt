package com.example.voicecontrol

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecordActivity : AppCompatActivity() {

    private var isRecording = false
    private var recordingJob: Job? = null
    private lateinit var btnStartRecord: Button

    private val sampleTexts = arrayOf(
        "Я сижу и смотрю в чужое небо из чужого окна, и не вижу ни одной знакомой звезды. Я ходил по всем дорогам и туда, и сюда, обернулся — и не смог разглядеть следы.",
        "Город из окна автомобиля нехотя включал свои огни. Робко с неба капли моросили, дворники, ворча, возили их по стеклу. И каждый мой день — как одна такая капля.",
        "Деньги на моей даме, открой рот. Мечты о Ferrari, мне не нужен пит-стоп. Размеренный трэп, я курю эти листья, затем я сделал рэп и забираю числа."
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        btnStartRecord = findViewById(R.id.btnStartRecord)
        val tvTextToRead = findViewById<TextView>(R.id.tvTextToRead)

        tvTextToRead.text = sampleTexts.random()

        btnStartRecord.setOnClickListener {
            if (!isRecording) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        isRecording = true
        btnStartRecord.text = "ИДЕТ ЗАПИСЬ (10 СЕК)..."
        btnStartRecord.isEnabled = false

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val durationSec = 10
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RecordActivity, "Ошибка микрофона", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@launch
            }

            audioRecord.startRecording()
            val totalSamples = sampleRate * durationSec
            val allSamples = ShortArray(totalSamples)
            var offset = 0
            val buffer = ShortArray(bufferSize)

            while (offset < totalSamples && isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val toCopy = minOf(read, totalSamples - offset)
                    System.arraycopy(buffer, 0, allSamples, offset, toCopy)
                    offset += toCopy
                }
            }

            audioRecord.stop()
            audioRecord.release()

            val file = File(filesDir, "voice_sample.wav")
            saveWavFile(file, allSamples, sampleRate)

            withContext(Dispatchers.Main) {
                if (isActive) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun saveWavFile(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val totalSize = dataSize + 36

        // Собираем WAV-заголовок через ByteBuffer для компактности
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // PCM
            putShort(1) // Mono
            putShort(1) // Channels
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray())
            putInt(dataSize)
        }.array()

        FileOutputStream(file).use { out ->
            out.write(header)
            val byteBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asShortBuffer().put(samples)
            out.write(byteBuffer.array())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Если пользователь закрыл экран во время записи, останавливаем поток
        recordingJob?.cancel()
    }
}