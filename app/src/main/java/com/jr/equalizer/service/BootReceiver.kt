package com.jr.equalizer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(GlobalEqService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(GlobalEqService.KEY_ENABLED, false)) return
        val start = Intent(context, GlobalEqService::class.java).apply {
            action = GlobalEqService.ACTION_ENABLE
            putExtra(GlobalEqService.EXTRA_JSON, prefs.getString(GlobalEqService.KEY_SETTINGS, null))
        }
        runCatching { ContextCompat.startForegroundService(context, start) }
    }
}
