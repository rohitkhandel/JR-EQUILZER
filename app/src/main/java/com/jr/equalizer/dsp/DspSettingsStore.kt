package com.jr.equalizer.dsp

import android.content.Context

object DspSettingsStore {
    private const val PREFS = "jr_equalizer_v2_settings"
    private const val KEY_SETTINGS = "active_dsp_settings"

    fun load(context: Context): DspSettings {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SETTINGS, null) ?: return DspSettings()
        return runCatching { DspSettings.fromJsonString(json) }.getOrDefault(DspSettings())
    }

    fun save(context: Context, settings: DspSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SETTINGS, settings.toJsonString())
            .apply()
    }
}
