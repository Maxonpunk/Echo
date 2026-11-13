package com.example.voicecontrol

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var pixelBackground: PixelBackgroundView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var tvLoadingStatus: TextView
    private var isServiceRunning = false

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "is_service_running") {
            val prefs = getSharedPreferences("voice_control_prefs", MODE_PRIVATE)
            isServiceRunning = prefs.getBoolean("is_service_running", false)
            runOnUiThread { updateUI() }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startVoiceService()
        } else {
            Toast.makeText(this, "Нужны разрешения для работы!", Toast.LENGTH_LONG).show()
        }
    }

    private val showAssReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.voicecontrol.SHOW_ASS") {
                pixelBackground.showWord()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        drawerLayout = findViewById(R.id.drawerLayout)
        pixelBackground = findViewById(R.id.pixelBackground)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus)

        btnToggle.isEnabled = false
        btnToggle.alpha = 0.5f
        tvStatus.text = "[ СИСТЕМА: ЗАГРУЗКА... ]"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))

        loadingOverlay.visibility = View.VISIBLE
        tvLoadingStatus.text = "Проверка моделей..."

        val prefs = getSharedPreferences("voice_control_prefs", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        isServiceRunning = prefs.getBoolean("is_service_running", false)

        val filter = IntentFilter("com.example.voicecontrol.SHOW_ASS")
        ContextCompat.registerReceiver(this, showAssReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnToggle.setOnClickListener {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            }

            if (isServiceRunning) {
                stopVoiceService()
            } else {
                if (checkPermissions()) startVoiceService() else requestPermissions()
            }
        }

        findViewById<TextView>(R.id.tvGithub).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/твой_репозиторий".toUri()))
        }

        val switchAnim = findViewById<SwitchMaterial>(R.id.switchAnimation)
        val isAnimEnabled = prefs.getBoolean("animation_enabled", true)
        switchAnim.isChecked = isAnimEnabled
        pixelBackground.isAnimationEnabled = isAnimEnabled
        switchAnim.setOnCheckedChangeListener { _, isChecked ->
            pixelBackground.isAnimationEnabled = isChecked
            prefs.edit().putBoolean("animation_enabled", isChecked).apply()
        }

        findViewById<Button>(R.id.btnRecordVoice).setOnClickListener {
            Toast.makeText(this, "Запись голоса временно не работает", Toast.LENGTH_SHORT).show()
        }

        fun updateBackgroundZones() {
            pixelBackground.clearRestrictedZones()
            val zones = mutableListOf<RectF>()
            val titleView = findViewById<TextView>(R.id.tvTitle)
            zones.add(getViewRect(titleView))
            zones.add(getViewRect(btnToggle))
            zones.add(getViewRect(tvStatus))
            for (zone in zones) {
                zone.inset(-10f, -10f)
                pixelBackground.addRestrictedZone(zone)
            }
        }

        pixelBackground.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                pixelBackground.viewTreeObserver.removeOnGlobalLayoutListener(this)
                updateBackgroundZones()
            }
        })

        // lifecycleScope автоматически отменит загрузку, если экран повернут
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(filesDir, "model")
                if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() == true) {
                    withContext(Dispatchers.Main) { tvLoadingStatus.text = "Копирование моделей..." }
                    copyAssets("model", modelDir)
                }

                val dpdfnetFile = File(filesDir, "dpdfnet_baseline.onnx")
                if (!dpdfnetFile.exists()) {
                    withContext(Dispatchers.Main) { tvLoadingStatus.text = "Копирование шумодава..." }
                    assets.open("model/dpdfnet_baseline.onnx").use { input ->
                        FileOutputStream(dpdfnetFile).use { output -> input.copyTo(output) }
                    }
                }

                withContext(Dispatchers.Main) { tvLoadingStatus.text = "Загрузка нейросетей..." }
                SherpaSingleton.initialize(this@MainActivity)

                withContext(Dispatchers.Main) {
                    if (SherpaSingleton.isReady()) {
                        btnToggle.isEnabled = true
                        btnToggle.alpha = 1.0f
                        updateUI()
                        updateBackgroundZones()
                        loadingOverlay.visibility = View.GONE
                    } else {
                        tvLoadingStatus.text = "ОШИБКА: модели не загружены"
                        tvLoadingStatus.setTextColor(android.graphics.Color.RED)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvLoadingStatus.text = "ОШИБКА: ${e.message}"
                    tvLoadingStatus.setTextColor(android.graphics.Color.RED)
                }
            }
        }
    }

    private fun copyAssets(path: String, targetDir: File) {
        val assets = applicationContext.assets
        val files = assets.list(path) ?: return
        if (files.isEmpty()) {
            targetDir.parentFile?.mkdirs()
            assets.open(path).use { input ->
                FileOutputStream(targetDir).use { output -> input.copyTo(output) }
            }
        } else {
            targetDir.mkdirs()
            for (asset in files) {
                copyAssets("$path/$asset", File(targetDir, asset))
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissionsLauncher.launch(perms.toTypedArray())
    }

    private fun startVoiceService() {
        val serviceIntent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        isServiceRunning = true
        updateUI()
    }

    private fun stopVoiceService() {
        val serviceIntent = Intent(this, VoiceService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        updateUI()
    }

    private fun updateUI() {
        if (isServiceRunning) {
            btnToggle.text = "DEACTIVATE"
            btnToggle.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            tvStatus.text = "[ STATUS: LISTENING... ]"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            btnToggle.text = "ACTIVATE"
            btnToggle.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            tvStatus.text = "[ STATUS: OFFLINE ]"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun getViewRect(view: View): RectF {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return RectF(location[0].toFloat(), location[1].toFloat(), (location[0] + view.width).toFloat(), (location[1] + view.height).toFloat())
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("voice_control_prefs", MODE_PRIVATE)
        isServiceRunning = prefs.getBoolean("is_service_running", false)
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences("voice_control_prefs", MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        unregisterReceiver(showAssReceiver)
    }
}