package com.jr.equalizer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jr.equalizer.dsp.DspSettings
import java.util.concurrent.ConcurrentHashMap

class GlobalEqService : Service() {
    private val effects = ConcurrentHashMap<Int, DynamicsProcessing>()
    private var settings = DspSettings()
    private var active = false

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        active = prefs.getBoolean(KEY_ENABLED, false)
        settings = runCatching {
            DspSettings.fromJsonString(prefs.getString(KEY_SETTINGS, DspSettings().toJsonString()) ?: "")
        }.getOrDefault(DspSettings())
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "JR-EQUILZER audio", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE -> {
                active = true
                settings = parseSettings(intent)
                save()
                foreground()
                attach(0)
            }
            ACTION_DISABLE -> {
                active = false
                save()
                releaseAll()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_GLOBAL_SUPPORTED, false)
                    .putString(KEY_STATUS, "Disabled")
                    .apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SESSION_OPEN -> {
                if (active) {
                    foreground()
                    val session = intent.getIntExtra(EXTRA_SESSION, -1)
                    if (session > 0) attach(session)
                }
            }
            ACTION_SESSION_CLOSE -> release(intent.getIntExtra(EXTRA_SESSION, -1))
            ACTION_UPDATE -> {
                settings = parseSettings(intent)
                save()
                val sessions = effects.keys.toList()
                releaseAll()
                if (active) sessions.forEach(::attach)
            }
        }
        return START_STICKY
    }

    private fun parseSettings(intent: Intent?): DspSettings {
        val json = intent?.getStringExtra(EXTRA_JSON)
        return if (json.isNullOrBlank()) settings else runCatching {
            DspSettings.fromJsonString(json)
        }.getOrDefault(settings)
    }

    private fun save() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, active)
            .putString(KEY_SETTINGS, settings.toJsonString())
            .apply()
    }

    private fun attach(session: Int) {
        if (Build.VERSION.SDK_INT < 28) {
            reportGlobalFailure("DynamicsProcessing requires Android 9 or newer")
            return
        }
        release(session)
        try {
            val effect = AudioSessionEffects.create(session, settings)
            effects[session] = effect
            if (session == 0) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_GLOBAL_SUPPORTED, true)
                    .putString(KEY_STATUS, "System accepted output mix effect")
                    .apply()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Audio session " + session + " could not be attached", error)
            if (session == 0) reportGlobalFailure("System output mix is blocked by this device firmware")
        }
    }

    private fun reportGlobalFailure(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_GLOBAL_SUPPORTED, false)
            .putString(KEY_STATUS, message)
            .apply()
    }

    private fun release(session: Int) {
        if (session >= 0) runCatching { effects.remove(session)?.release() }
    }

    private fun releaseAll() = effects.keys.toList().forEach(::release)

    private fun foreground() {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("JR-EQUILZER is active")
            .setContentText("Audio-session effects are monitoring compatible players")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ENABLE = "com.jr.equalizer.ENABLE"
        const val ACTION_DISABLE = "com.jr.equalizer.DISABLE"
        const val ACTION_SESSION_OPEN = "com.jr.equalizer.SESSION_OPEN"
        const val ACTION_SESSION_CLOSE = "com.jr.equalizer.SESSION_CLOSE"
        const val ACTION_UPDATE = "com.jr.equalizer.UPDATE"
        const val EXTRA_SESSION = "audio_session_id"
        const val EXTRA_JSON = "settings_json"
        const val PREFS = "jr_equalizer"
        const val KEY_ENABLED = "global_enabled"
        const val KEY_SETTINGS = "dsp_settings"
        const val KEY_GLOBAL_SUPPORTED = "global_output_supported"
        const val KEY_STATUS = "global_status"
        private const val CHANNEL = "jr_equalizer_audio"
        private const val NOTIFICATION_ID = 4021
        private const val TAG = "JR-EQ"
    }
}
