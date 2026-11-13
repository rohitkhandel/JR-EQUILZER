# JR-EQUILZER

JR-EQUILZER is an original Android audio equalizer and offline audio exporter built with Kotlin and Jetpack Compose.

## Features

- 5–32 band parametric equalizer with editable frequency response curve and band controls.
- RBJ Audio EQ Cookbook biquad filters calculated in Double precision for offline sample processing.
- Bass and treble shelving, stereo width and balance, compressor and limiter controls.
- Local audio file preview and offline WAV (PCM 16-bit) or M4A (AAC) export.
- Export destination: `Music/JR-Equalizer/`.
- Built-in presets and JSON preset import/export.
- Android DynamicsProcessing audio effect for compatible player sessions, plus an optional session-0 output-mix attempt that reports whether the device accepts it.

## Important Android audio limitation

Android apps cannot guarantee system-wide audio processing. Other apps must expose compatible audio-session IDs, and device firmware may reject the output-mix session (session 0). The Global Mode status therefore reports the actual result rather than promising universal support. Direct Volume Control is OEM/privileged functionality and is not represented as a guaranteed capability.

## Build

GitHub Actions builds the debug APK on every push to `main` and on manual workflow dispatch. Open **Actions → Build JR-EQUILZER APK → JR-EQUILZER-debug-apk** to retrieve the build artifact.

The debug application ID is `com.jr.equalizer.debug`. Minimum Android version is Android 10 (API 29); target SDK is 34.
