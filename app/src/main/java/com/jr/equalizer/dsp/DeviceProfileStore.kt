package com.jr.equalizer.dsp

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class AudioOutputProfile(val key: String, val label: String) {
    PHONE_SPEAKER("phone_speaker", "Phone Speaker"),
    WIRED_HEADSET("wired_headset", "Wired Headset"),
    BLUETOOTH_AUDIO("bluetooth_audio", "Bluetooth Audio"),
    USB_DAC("usb_dac", "USB DAC")
}

/**
 * Per-output EQ snapshots. Route detection is based on public Android output
 * device types; unknown routes safely fall back to the phone-speaker profile.
 */
object DeviceProfileStore {
    private const val PREFS = "jr_equalizer_device_profiles"

    fun detect(context: Context): AudioOutputProfile {
        val manager = context.getSystemService(AudioManager::class.java)
        val outputs = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        return when {
            outputs.any {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            } -> AudioOutputProfile.USB_DAC

            outputs.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } -> AudioOutputProfile.BLUETOOTH_AUDIO

            outputs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            } -> AudioOutputProfile.WIRED_HEADSET

            else -> AudioOutputProfile.PHONE_SPEAKER
        }
    }

    fun load(context: Context, profile: AudioOutputProfile): DspSettings? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(profile.key, null) ?: return null
        return runCatching { DspSettings.fromJsonString(json) }.getOrNull()
    }

    fun save(context: Context, profile: AudioOutputProfile, settings: DspSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(profile.key, settings.toJsonString())
            .apply()
    }
}
