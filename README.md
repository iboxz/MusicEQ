# BoxEQ

Equalizer for the audio of **other apps** (Telegram, Instagram, any app you add), built on
`android.media.audiofx.DynamicsProcessing` (10-band pre-EQ, -12..+12 dB, preamp, limiter) with a legacy
`Equalizer` fallback. Kotlin + Jetpack Compose, minSdk 28, targetSdk 34. English and Persian (RTL).

## Build

Requirements: Android Studio Koala (2024.1.1) or newer, JDK 17 (bundled with Android Studio), Android SDK 34.

1. Open the `BoxEQ` folder in Android Studio and let Gradle sync (it downloads Gradle 8.7 from
   `gradle/wrapper/gradle-wrapper.properties`; accept an AGP upgrade prompt only if you want to).
2. Connect a phone (Developer options -> USB debugging) and press **Run**, or build an APK:
   `gradle assembleDebug` (or `./gradlew assembleDebug` after running `gradle wrapper` once) ->
   `app/build/outputs/apk/debug/app-debug.apk`, then `adb install -r app-debug.apk`.
   `assembleRelease` also produces an installable APK (signed with the debug key, personal use).
3. Unit tests: `gradle testDebugUnitTest` (band/gain mapping, curve maths, preset/profile serialization).

## How sessions are found

1. **Broadcast receiver** - players that follow the Android audio-effect protocol send
   `OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` to BoxEQ (also reachable through the
   `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` intent). BoxEQ attaches/releases an effect per session ID.
2. **Shizuku mode** (optional) - reads active playback configurations (session ID + client UID) from the audio
   service with shell privileges, maps UID -> package, attaches effects to the apps you enabled and re-checks when
   the playback callback fires (plus a 4 s poll). All hidden-API reflection lives in
   `shizuku/HiddenAudioApi.kt`; failures are reported in Settings, never thrown.
3. Global session 0 is never used.

Status per app: **Connected**, **App does not expose audio session**, **Needs Shizuku**
(plus *Waiting for playback* and *Off*).

### Honest limitations
- Telegram and Instagram may not announce their audio session; then only Shizuku mode can reach them, and an app
  that uses its own audio path / hardware offload / DRM may still ignore the effect.
- Session IDs are exposed in `AudioPlaybackConfiguration` from **Android 12 (API 31)**. On Android 9-11 Shizuku
  mode reports that it cannot read session IDs; only broadcast discovery works there.
- The legacy fallback has the device's own band count (usually 5) and no limiter.
- Effects exist only while the BoxEQ process lives; the foreground service keeps it alive. After a reboot open the
  app once. Non-root Shizuku must also be restarted after each reboot.

## Shizuku setup

1. Install Shizuku (Play Store or https://shizuku.rikka.app/download/).
2. Start it: Android 11+ -> Developer options -> Wireless debugging -> *Pair device with pairing code*, then
   follow Shizuku's "Start via Wireless debugging" screen. Or over USB:
   `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`. Rooted phones can start it from the app.
3. In BoxEQ -> Settings turn on **Shizuku mode** and tap **Grant permission**. The card should read
   "Active - watching playback."

## Test checklist

- [ ] Onboarding shows; allow notifications; the "BoxEQ is on" notification appears with three actions.
- [ ] Apps tab lists Telegram and Instagram (if installed) with a status; you can add any other app.
- [ ] **Telegram voice note:** with Shizuku mode on, play a voice note; status becomes *Connected*. Select the
      Telegram app on the Equalizer tab and switch between *Flat* and *Voice Message Clarity*; the voice should
      get thinner in the lows / clearer in the presence range. Toggle the master switch: sound returns to normal.
- [ ] **Instagram reel:** play a reel; status *Connected*. Choose *Bass Boost* - the low end must clearly increase.
      Push the 31-125 Hz bands to +12 dB with the limiter off and on; with it on, distortion must be reduced.
- [ ] Drag a slider: the dB label and curve update instantly and the sound changes within ~0.2 s.
      Long-press a slider: it returns to 0 dB.
- [ ] Edit a built-in preset: a "(custom)" copy is created. Save as preset, restart the app: it is still there.
      Delete it: apps using it fall back to Flat.
- [ ] Notification actions: *Turn off/on*, *Next preset* (cycles all enabled apps), *Stop*.
- [ ] Switch the system language to Persian: strings are Persian and the layout is RTL; the EQ sliders and curve
      still run low-to-high frequency from left to right.
- [ ] Shizuku not running -> apps show *Needs Shizuku*; an app that plays but cannot be attached shows
      *App does not expose audio session*.

## Build without Android Studio (GitHub Actions)

Push this folder to a GitHub repository (the `.github/workflows/build.yml` file is included). Open the
**Actions** tab -> *Build APK* run -> download the `BoxEQ-apk` artifact, unzip it and install `app-debug.apk`.
