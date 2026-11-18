# SoundFxEV 0.1.0 Alpha

Separate experimental Android sound lab for stationary testing on BYD DiLink Android 9+.
Does not replace the launcher, does not read or write vehicle data, and does not modify
the AVAS or TripLog. No background playback. Audio stops when leaving the activity or
losing audio focus. Start at low volume; operate only when parked.

- Manual throttle and synthetic RPM, six engine layouts, exhaust selector and volume control.
- Independent application ID: com.sacool.soundfxev.
- Next phase: validate read-only BYD telemetry rates and lifecycle; virtual gears; driver inputs.
  Do not enable real-car mode until stationary audio and fault handling pass device tests.

Build from repository root: `./gradlew -p soundfxev testDebugUnitTest assembleDebug`.
Use the SoundFxEV Alpha APK GitHub Actions artifact for the debug build.

## Credits and license

Engine DSP and Android playback files under `src/main/java/com/vivekkaushik/revv/engine`,
impulse assets and upstream tests are copied intact from Revv at commit
d6ab616dc7dc8c886cec3724178e590d48a2210e (GPL-3.0).
Revv's synthesizer follows Ange Yaghi's engine-sim (MIT).
See the root LICENSE and the bundled GPL-3.0 and MIT-engine-sim notices.
SoundFxEV's derivative code is provided under GPL-3.0.
