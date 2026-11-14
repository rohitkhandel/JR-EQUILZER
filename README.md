# JR-EQUILZER v4.1.0

JR-EQUILZER is a private-use Android audio mastering console built with Kotlin and Jetpack Compose.

## v4.1.0 session recovery

- Restore the v3.0.0 live DynamicsProcessing configuration shape: 32 Pre-EQ bands, 6 MBC bands and 6 Post-EQ bands. The v4 Monster Bass / Crystal Treble curve is fitted onto those six accepted Post-EQ slices rather than expanding the native Post-EQ count to 16.
- Keep the original dynamic `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` / close receiver, `AudioManager.AudioPlaybackCallback`, `NotificationListenerService` media-session detection, Session-0 output handling and player-session attachment path.
- Register `AudioSessionReceiver` in the manifest as a static backup so session open/close broadcasts can wake the foreground service when the app process has been reclaimed.
- Restore the saved enabled state when `GlobalEqService` is recreated. `MainActivity.onCreate()` and `onResume()` issue the foreground `ACTION_ENABLE` command when the user's saved preference is enabled.
- Add a Force Hook / Reconnect action. It applies the current settings to active effects and reattaches Session-0 plus session IDs already known to the service.
- If the primary DynamicsProcessing effect is rejected for a player session, try each legacy session effect independently. One unsupported Equalizer, BassBoost, Virtualizer or LoudnessEnhancer no longer prevents the other supported effects from attaching.
- The status banner reports the detected playing app, active session ID list, per-session engine, Session-0 engine and notification-access state.

## v4.0.0 Cyber-Studio features retained

- Slider-free Cyber-Studio console with tactile, haptic step buttons and rotary-style readouts.
- 8-zone Acoustic Matrix with per-zone dB display, LED meters, Cut / Flat / Warm / Boost / Extreme taps, and log-frequency interpolation across all 32 Pre-EQ bands.
- Seven one-tap sound signatures: Extreme Basshead, Crystal Harman, Studio Vocals, Dolby 3D Stage, Raw Loudness, Latin Urban / Pop and Custom Matrix.
- Monster Bass / Crystal Treble processing curves, Turbo Loudness and offline audio rendering.
- JSON preset import/export and per-output-device settings.

## Android audio notes

- Android and OEM audio-effect implementations vary. BassBoost, LoudnessEnhancer and DynamicsProcessing may be restricted or behave differently on a particular output route.
- A DSP limiter protects the signal at its own stage; it cannot guarantee a fixed acoustic loudness or protect every downstream hardware path.
- The live Android effect chain and the offline software renderer share the tone model, but they are different processing implementations and are not guaranteed to be bit-identical.
- DVC / Turbo is an application-level control. It does not bypass Android's system mixer or change the phone's physical volume limit.
- CI validation does not replace device testing. The v4.1.0 build still needs to be installed and verified on the Moto G82 target.

## Build and release

GitHub Actions runs DSP unit tests and creates a debug APK and an R8-optimized release APK when changes are pushed to `main`. Release signing uses the project's configured debug signing key.
Minimum Android version is Android 10 (API 29); target SDK is 34.
