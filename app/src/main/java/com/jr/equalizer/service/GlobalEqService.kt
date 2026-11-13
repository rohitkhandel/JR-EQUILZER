package com.jr.equalizer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jr.equalizer.dsp.DspSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

class GlobalEqService : Service() {
    private lateinit var dspThread: HandlerThread
    private lateinit var dspHandler: Handler
    private lateinit var audioManager: AudioManager
    @Volatile private var active = false
    @Volatile private var settings = DspSettings()

    private var globalDynamics: DynamicsProcessing? = null
    private val playerDynamics = ConcurrentHashMap<Int, DynamicsProcessing>()
    private val playerPackages = HashMap<Int, String?>()
    private val legacyGlobalEffects = ArrayList<android.media.audiofx.AudioEffect>()
    private var visualizer: Visualizer? = null
    private var visualizerSessionId: Int = -1
    private var playbackCallbackRegistered = false

    private val settingsApplyRunnable = Runnable { applySettingsToActiveSessions() }

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
            when (action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (sessionId >= 0 && active) {
                        dspHandler.post {
                            if (sessionId == 0) {
                                attachGlobalOutput()
                                if (visualizer == null) attachVisualizer(0)
                            } else {
                                attachPlayerSession(sessionId, packageName)
                                attachVisualizer(sessionId)
                            }
                        }
                    }
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (sessionId >= 0) dspHandler.post {
                        if (sessionId == 0) releaseGlobalOutput() else releasePlayerSession(sessionId)
                    }
                }
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // Android anonymizes playback configurations for ordinary apps. This callback
            // provides activity/count only; it does not expose the other app's session ID.
            AudioRuntimeStatusRepository.updatePlaybackConfigurationCount(configs.size)
        }
    }

    override fun onCreate() {
        super.onCreate()
        dspThread = HandlerThread("JR-Equalizer-DSP").apply { start() }
        dspHandler = Handler(dspThread.looper)
        audioManager = getSystemService(AudioManager::class.java)
        settings = readSettings()
        registerSessionReceiver()
        registerPlaybackCallback()
        createNotificationChannel()
    }

    private fun registerSessionReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(sessionReceiver, filter, null, dspHandler, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(sessionReceiver, filter, null, dspHandler)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not register the dynamic audio-session receiver", error)
        }
    }

    private fun registerPlaybackCallback() {
        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback, dspHandler)
            playbackCallbackRegistered = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioRuntimeStatusRepository.updatePlaybackConfigurationCount(
                    audioManager.activePlaybackConfigurations.size
                )
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Playback activity callback is unavailable", error)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE -> {
                active = true
                ensureForeground()
                val json = intent.getStringExtra(EXTRA_JSON)
                dspHandler.post {
                    settings = parseSettings(json)
                    persistSettings()
                    attachGlobalOutput()
                    if (hasRecordPermission()) attachVisualizer(0)
                    else AudioRuntimeStatusRepository.updateVisualizerStatus("Grant audio visualization permission in JR-EQUILZER")
                    playerPackages.keys.toList().forEach { session ->
                        attachPlayerSession(session, playerPackages[session])
                    }
                }
            }
            ACTION_DISABLE -> {
                active = false
                dspHandler.post {
                    persistSettings()
                    releaseAllEffects()
                    stopVisualizer()
                    AudioRuntimeStatusRepository.updateGlobalEngine(false, "Global equalization disabled")
                    Handler(Looper.getMainLooper()).post {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_UPDATE -> {
                val json = intent.getStringExtra(EXTRA_JSON)
                dspHandler.post {
                    settings = parseSettings(json)
                    persistSettings()
                    dspHandler.removeCallbacks(settingsApplyRunnable)
                    dspHandler.postDelayed(settingsApplyRunnable, SETTINGS_SAMPLE_MS)
                }
            }
            ACTION_START_VISUALIZER -> dspHandler.post { attachVisualizer(0) }
            ACTION_SESSION_OPEN -> {
                if (active) {
                    ensureForeground()
                    val id = intent.getIntExtra(EXTRA_SESSION, -1)
                    val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                    if (id >= 0) dspHandler.post {
                        if (id == 0) {
                            attachGlobalOutput()
                            if (visualizer == null) attachVisualizer(0)
                        } else {
                            attachPlayerSession(id, pkg)
                            attachVisualizer(id)
                        }
                    }
                }
            }
            ACTION_SESSION_CLOSE -> {
                val id = intent.getIntExtra(EXTRA_SESSION, -1)
                if (id >= 0) dspHandler.post {
                    if (id == 0) releaseGlobalOutput() else releasePlayerSession(id)
                }
            }
            ACTION_MEDIA_APP -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                if (playing) AudioRuntimeStatusRepository.updateDetectedPlayer(pkg)
                else if (AudioRuntimeStatusRepository.state.value.detectedPackage == pkg) {
                    AudioRuntimeStatusRepository.updateDetectedPlayer(null)
                }
            }
        }
        return START_STICKY
    }

    private fun parseSettings(json: String?): DspSettings =
        if (json.isNullOrBlank()) readSettings() else runCatching { DspSettings.fromJsonString(json) }.getOrDefault(settings)

    private fun readSettings(): DspSettings {
        val json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SETTINGS, null)
        return if (json.isNullOrBlank()) DspSettings() else runCatching { DspSettings.fromJsonString(json) }.getOrDefault(DspSettings())
    }

    private fun persistSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, active)
            .putString(KEY_SETTINGS, settings.toJsonString())
            .apply()
    }

    private fun applySettingsToActiveSessions() {
        if (!active) return
        attachGlobalOutput()
        playerPackages.keys.toList().forEach { session ->
            attachPlayerSession(session, playerPackages[session])
        }
    }

    private fun attachGlobalOutput() {
        releaseGlobalOutput()
        try {
            val effect = AudioSessionEffects.create(0, settings)
            check(effect.hasControl()) { "DynamicsProcessing does not own output-mix control." }
            globalDynamics = effect
            AudioRuntimeStatusRepository.updateGlobalEngine(true, "Output mix: DynamicsProcessing accepted")
        } catch (error: Throwable) {
            Log.w(TAG, "Session-0 DynamicsProcessing rejected; trying legacy output effects", error)
            try {
                val fallbackEffects = AudioSessionEffects.createLegacyOutputFallback(0, settings)
                legacyGlobalEffects.addAll(fallbackEffects)
                val accepted = fallbackEffects.isNotEmpty()
                AudioRuntimeStatusRepository.updateGlobalEngine(
                    accepted,
                    if (accepted) "Output mix: legacy fallback accepted (deprecated Android path)"
                    else "Output mix: no supported effect control"
                )
            } catch (fallbackError: Throwable) {
                AudioRuntimeStatusRepository.updateGlobalEngine(
                    false,
                    "Output mix unavailable on this firmware: " + (fallbackError.message ?: "effect not supported")
                )
            }
        }
    }

    private fun attachPlayerSession(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return
        releasePlayerSession(sessionId)
        playerPackages[sessionId] = packageName
        try {
            val effect = AudioSessionEffects.create(sessionId, settings)
            check(effect.hasControl()) { "DynamicsProcessing control was not granted for this player session." }
            playerDynamics[sessionId] = effect
            AudioRuntimeStatusRepository.sessionAttached(sessionId, packageName)
        } catch (error: Throwable) {
            Log.w(TAG, "Player session $sessionId rejected DynamicsProcessing", error)
            AudioRuntimeStatusRepository.sessionDetached(sessionId)
        }
    }

    private fun releasePlayerSession(sessionId: Int) {
        runCatching { playerDynamics.remove(sessionId)?.release() }
        playerPackages.remove(sessionId)
        AudioRuntimeStatusRepository.sessionDetached(sessionId)
        if (visualizerSessionId == sessionId) {
            stopVisualizer()
            if (active && hasRecordPermission()) attachVisualizer(0)
        }
    }

    private fun releaseGlobalOutput() {
        runCatching { globalDynamics?.release() }
        globalDynamics = null
        legacyGlobalEffects.toList().forEach { runCatching { it.release() } }
        legacyGlobalEffects.clear()
    }

    private fun releaseAllEffects() {
        releaseGlobalOutput()
        playerDynamics.keys.toList().forEach(::releasePlayerSession)
    }

    private fun attachVisualizer(sessionId: Int) {
        if (!hasRecordPermission()) {
            AudioRuntimeStatusRepository.updateVisualizerStatus("Audio visualization permission is required")
            return
        }
        stopVisualizer()
        try {
            val candidate = Visualizer(sessionId).apply {
                val range = Visualizer.getCaptureSizeRange()
                val preferredSize = 1024
                captureSize = if (preferredSize in range[0]..range[1]) {
                    preferredSize
                } else {
                    range[1].coerceAtMost(preferredSize).coerceAtLeast(range[0])
                }
                val rate = minOf(30_000, Visualizer.getMaxCaptureRate())
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft != null) {
                            AudioRuntimeStatusRepository.updateSpectrum(
                                toSpectrum(fft, samplingRate, fft.size)
                            )
                        }
                    }
                }, rate, false, true)
                enabled = true
            }
            visualizer = candidate
            visualizerSessionId = sessionId
            AudioRuntimeStatusRepository.updateVisualizerStatus(
                if (sessionId == 0) "Live output-mix spectrum active" else "Live player-session spectrum active"
            )
        } catch (error: Throwable) {
            visualizer = null
            visualizerSessionId = -1
            AudioRuntimeStatusRepository.updateVisualizerStatus(
                "Visualizer unavailable: " + (error.message ?: "device audio policy denied access")
            )
            Log.w(TAG, "Could not attach Visualizer to session $sessionId", error)
        }
    }

    private fun toSpectrum(fft: ByteArray, sampleRateMilliHz: Int, captureSize: Int): FloatArray {
        val result = FloatArray(24)
        val binCount = (captureSize / 2).coerceAtLeast(2)
        val sampleRateHz = sampleRateMilliHz / 1000.0
        val minHz = 30.0
        val maxHz = minOf(18_000.0, sampleRateHz / 2.0)
        for (band in result.indices) {
            val lowHz = minHz * Math.pow(maxHz / minHz, band.toDouble() / result.size)
            val highHz = minHz * Math.pow(maxHz / minHz, (band + 1.0) / result.size)
            val first = (lowHz * captureSize / sampleRateHz).toInt().coerceIn(1, binCount - 1)
            val last = (highHz * captureSize / sampleRateHz).toInt().coerceIn(first + 1, binCount)
            var total = 0.0
            for (bin in first until last) {
                val index = bin * 2
                if (index + 1 < fft.size) {
                    val real = fft[index].toInt()
                    val imaginary = fft[index + 1].toInt()
                    total += sqrt((real * real + imaginary * imaginary).toDouble())
                }
            }
            val average = total / (last - first).coerceAtLeast(1)
            result[band] = ((average / 64.0).coerceIn(0.0, 1.0)).toFloat()
        }
        return result
    }

    private fun stopVisualizer() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        visualizerSessionId = -1
        AudioRuntimeStatusRepository.updateVisualizerStatus("Visualizer stopped")
    }

    private fun hasRecordPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun ensureForeground() {
        Handler(Looper.getMainLooper()).post {
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("JR-EQUILZER is active")
                .setContentText("Player tracking and audio effects are running")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "JR-EQUILZER audio", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionReceiver) }
        if (playbackCallbackRegistered) runCatching {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        }
        if (::dspHandler.isInitialized) {
            dspHandler.removeCallbacksAndMessages(null)
            dspHandler.post {
                releaseAllEffects()
                stopVisualizer()
            }
            dspThread.quitSafely()
        }
        AudioRuntimeStatusRepository.updatePlaybackConfigurationCount(0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ENABLE = "com.jr.equalizer.ENABLE"
        const val ACTION_DISABLE = "com.jr.equalizer.DISABLE"
        const val ACTION_SESSION_OPEN = "com.jr.equalizer.SESSION_OPEN"
        const val ACTION_SESSION_CLOSE = "com.jr.equalizer.SESSION_CLOSE"
        const val ACTION_UPDATE = "com.jr.equalizer.UPDATE"
        const val ACTION_START_VISUALIZER = "com.jr.equalizer.START_VISUALIZER"
        const val ACTION_MEDIA_APP = "com.jr.equalizer.MEDIA_APP"
        const val EXTRA_SESSION = "audio_session_id"
        const val EXTRA_JSON = "settings_json"
        const val EXTRA_PACKAGE = "player_package_name"
        const val EXTRA_PLAYING = "player_is_playing"
        const val PREFS = "jr_equalizer"
        const val KEY_ENABLED = "global_enabled"
        const val KEY_SETTINGS = "dsp_settings"
        const val KEY_GLOBAL_SUPPORTED = "global_output_supported"
        const val KEY_STATUS = "global_status"
        private const val CHANNEL = "jr_equalizer_audio"
        private const val NOTIFICATION_ID = 4021
        private const val SETTINGS_SAMPLE_MS = 16L
        private const val TAG = "JR-EQ"
    }
}
