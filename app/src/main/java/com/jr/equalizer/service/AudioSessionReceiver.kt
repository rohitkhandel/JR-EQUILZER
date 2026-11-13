package com.jr.equalizer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log
import androidx.core.content.ContextCompat

class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isOpen = action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
        val isClose = action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        if (!isOpen && !isClose) return
        val prefs = context.getSharedPreferences(GlobalEqService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(GlobalEqService.KEY_ENABLED, false)) return
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (id <= 0) return
        val serviceIntent = Intent(context, GlobalEqService::class.java).apply {
            this.action = if (isOpen) GlobalEqService.ACTION_SESSION_OPEN else GlobalEqService.ACTION_SESSION_CLOSE
            putExtra(GlobalEqService.EXTRA_SESSION, id)
        }
        try {
            if (isOpen) ContextCompat.startForegroundService(context, serviceIntent)
            else context.startService(serviceIntent)
        } catch (error: IllegalStateException) {
            Log.w("JR-AudioSession", "Session update could not reach service", error)
        }
    }
}
