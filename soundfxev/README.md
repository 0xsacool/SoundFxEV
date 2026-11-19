# SoundFxEV 0.1.0 Alpha 8 — GPS Edition

Standalone Android sound lab (Android 9+). Real driving uses GPS speed only, with estimated
load, virtual RPM and a fictional six-speed gearbox. No ADB, vehicle SDK, OBD or network
permission is required. No vehicle control, location recording, uploads, or AVAS changes.

- GPS Sport is the default; GPS Normal is also available.
- Parked-only sound demos: Free rev, Demo Normal and Demo Sport.
- Six engine layouts, exhaust profiles, adjustable volume/Bass, full-range cabin audio,
  optional turbo spool, blow-off, flutter, and pop & bang.
- Alpha 7.2 coast audio retained: engine sound remains audible during deceleration.
- Playback stops when leaving the activity or losing audio focus, and is muted when GPS
  data is missing or stale. Configure while parked and start at low volume.
- Independent application ID: com.sacool.soundfxev. EV TripLog is unchanged.

Build: `./gradlew -p soundfxev testDebugUnitTest assembleDebug`.
Use the SoundFxEV Alpha APK GitHub Actions artifact for the debug build.

## Credits and license

Engine DSP and Android playback files under `src/main/java/com/vivekkaushik/revv/engine`,
impulse assets and upstream tests are derived from Revv at commit
d6ab616dc7dc8c886cec3724178e590d48a2210e (GPL-3.0).
Revv's synthesizer follows Ange Yaghi's engine-sim (MIT).
See the root LICENSE and the bundled GPL-3.0 and MIT-engine-sim notices.
SoundFxEV's derivative code is provided under GPL-3.0.
