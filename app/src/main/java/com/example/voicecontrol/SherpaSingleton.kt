package com.example.voicecontrol

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

object SherpaSingleton {
    private var recognizer: OnlineRecognizer? = null
    private var denoiser: OnlineSpeechDenoiser? = null
    private var isInitializing = false
    private var isInitialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        if (isInitializing) {
            // Ждем, пока другой поток закончит инициализацию
            while (isInitializing) {
                Thread.sleep(100)
            }
            return
        }
        isInitializing = true
        Log.d("SHERPA_DEBUG", "Начинаем инициализацию Sherpa...")

        try {
            val modelDir = File(context.filesDir, "model")
            val sampleRate = 16000

            // Проверка, что файлы на месте
            if (!modelDir.exists() || !File(modelDir, "encoder.int8.onnx").exists()) {
                Log.e("SHERPA_DEBUG", "Файлы модели не найдены в filesDir!")
                return
            }

            // 1. Загрузка распознавателя
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                        decoder = File(modelDir, "decoder.onnx").absolutePath,
                        joiner = File(modelDir, "joiner.int8.onnx").absolutePath
                    ),
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    debug = false
                ),
                enableEndpoint = true,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 1.2f, 0f),
                    rule3 = EndpointRule(false, 0f, 20f)
                )
            )
            recognizer = OnlineRecognizer(assetManager = null, config = config)
            Log.d("SHERPA_DEBUG", "Recognizer загружен")

            // 2. Загрузка шумодава
            val dpdfnetFile = File(context.filesDir, "dpdfnet_baseline.onnx")
            val denoiserConfig = OnlineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(
                        model = dpdfnetFile.absolutePath
                    )
                )
            )
            denoiser = OnlineSpeechDenoiser(config = denoiserConfig)
            Log.d("SHERPA_DEBUG", "Denoiser загружен")

            isInitialized = true
            Log.d("SHERPA_DEBUG", "Инициализация Sherpa завершена успешно")
        } catch (e: Exception) {
            Log.e("SHERPA_DEBUG", "Ошибка инициализации: ${e.message}")
            e.printStackTrace()
        } finally {
            isInitializing = false
        }
    }

    fun getRecognizer(): OnlineRecognizer? = recognizer
    fun getDenoiser(): OnlineSpeechDenoiser? = denoiser

    fun isReady(): Boolean = isInitialized
}