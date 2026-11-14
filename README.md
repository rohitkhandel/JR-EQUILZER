# JR-EQUILZER v4.0.0

JR-EQUILZER is a private-use Android audio mastering console built with Kotlin and Jetpack Compose.

## v4.0.0 features

- Slider-free Cyber-Studio console with tactile, haptic step buttons and rotary-style readouts.
- 8-zone Acoustic Matrix with per-zone dB display, LED meters, Cut / Flat / Warm / Boost / Extreme taps, and log-frequency interpolation across all 32 Pre-EQ bands.
- Seven one-tap sound signatures: Extreme Basshead, Crystal Harman, Studio Vocals, Dolby 3D Stage, Raw Loudness, Latin Urban / Pop and Custom Matrix.
- Multi-slice 16-band live Post-EQ tone mapping for the bass and treble enhancement curves.
- BassBoost strength mapped to Android's 0–1000 range and optional LoudnessEnhancer turbo target mapped to 0–1500 mB.
- Fixed live and offline brickwall limiter target of −0.2 dBFS, 20:1 ratio, 1 ms attack, 60 ms release and 0 dB post-gain.
- Offline WAV (PCM 16-bit) and M4A (AAC) rendering with the same 8-zone, bass/treble and vocal-presence settings.
- JSON preset import/export and per-output-device settings.

## Player connection

The YouTube / YouTube Music working connection code from v3.0.0 is deliberately preserved. Detection services, NotificationListenerService, AudioPlaybackCallback and session attachment lifecycle are not part of the v4.0.0 edit set.

## Android audio notes

- Android and OEM audio-effect implementations vary. BassBoost, LoudnessEnhancer and DynamicsProcessing may be restricted or behave differently on a particular output route.
- A DSP limiter protects the signal at its own stage; it cannot guarantee a fixed acoustic loudness or protect every downstream hardware path.
- The live Android effect chain and the offline software renderer share the requested tone model, but they are different processing implementations and are not guaranteed to be bit-identical.
- DVC in this app is an application-level Turbo control. It does not bypass Android's system mixer or change the phone's physical volume limit.
- The v4.0.0 build must still be installed and auditioned on the target Moto G82 to confirm the actual device's response.

## Build and release

GitHub Actions runs the DSP unit tests and creates a debug APK and an R8-optimized release APK when changes are pushed to `main`. Release signing uses the project's configured debug signing key.
Minimum Android version is Android 10 (API 29); target SDK is 34.
