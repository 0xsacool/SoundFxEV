package com.sacool.soundfxev

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.*
import com.vivekkaushik.revv.engine.EngineLayout
import com.vivekkaushik.revv.engine.EngineReading
import com.vivekkaushik.revv.engine.EngineSound
import com.vivekkaushik.revv.engine.EngineSoundSettings
import com.vivekkaushik.revv.engine.ExhaustNote
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.net.SocketTimeoutException

/** Foreground-only sound lab; GPS speed is read locally, never recorded or sent. */
class MainActivity : Activity() {
    private data class Profile(val title: String, val layout: EngineLayout, val limit: Int)
    private val profiles = listOf(
        Profile("JDM Inline-6", EngineLayout.INLINE_6, 8000),
        Profile("High-rev Inline-4", EngineLayout.INLINE_4, 8500),
        Profile("Boxer-4", EngineLayout.BOXER_4, 7200),
        Profile("V8 Cross-plane", EngineLayout.V8_CROSS_PLANE, 6500),
        Profile("Supercar V10", EngineLayout.V10, 8500),
        Profile("V12", EngineLayout.V12, 8500),
    )
    private data class Tone(val label: String, val note: ExhaustNote, val deep: Boolean = false)
    private val tones = listOf(
        Tone("Stock", ExhaustNote.STOCK),
        Tone("Sport", ExhaustNote.SPORT),
        Tone("Deep Exhaust • JDM", ExhaustNote.SPORT, true),
        Tone("Open", ExhaustNote.OPEN),
        Tone("Straight pipe", ExhaustNote.STRAIGHT_PIPE),
    )
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var rpmLabel: TextView
    private lateinit var gearLabel: TextView
    private lateinit var status: TextView
    private lateinit var throttleLabel: TextView
    private lateinit var throttle: SeekBar
    private lateinit var toggle: Button
    private lateinit var profileSpinner: Spinner
    private lateinit var releaseButton: Button
    private lateinit var linkButton: Button
    private lateinit var diagnosticButton: Button
    private lateinit var diagnosticLabel: TextView
    private var testingLink = false
    private var authSession: BydAdbSession? = null
    private var authTask: Future<*>? = null
    private var bydSession: BydAdbSession? = null
    private var linkAuthorized = false
    private var linkAuthorizing = false
    private var destroyed = false
    private lateinit var testButton: Button
    private lateinit var locationManager: LocationManager
    private val gpsDrive = GpsDrive()
    private val bydDrive = GpsDrive(maxAgeNanos = 800_000_000L)
    private val bydMode: Boolean get() = demoMode >= 5
    private val gpsMode: Boolean get() = demoMode in 3..4
    private val realDrive: GpsDrive get() = if (bydMode) bydDrive else gpsDrive
    private val sourceName: String get() = if (bydMode) "BYD LINK" else "GPS"
    // The worker never blocks UI/audio; a cancelled session cannot publish stale data.
    private val bydExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "BydReadOnly").apply { isDaemon = true } }
    private var bydTask: Future<*>? = null
    @Volatile private var bydGeneration = 0
    private var gpsListening = false
    private var gpsHadFix = false
    private val realMode: Boolean get() = demoMode >= 3
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!gpsListening || !gpsMode || engine == null) return
            if (!location.hasSpeed() || location.isFromMockProvider || !location.hasAccuracy() ||
                location.accuracy > 35f || !location.accuracy.isFinite() ||
                location.hasSpeedAccuracy() && (location.speedAccuracyMetersPerSecond > 2f || !location.speedAccuracyMetersPerSecond.isFinite())) return
            gpsDrive.acceptSpeed(location.speed.toDouble() * 3.6, location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
        }
        override fun onProviderDisabled(provider: String) {
            if (gpsListening) stopEngine("GPS ปิดอยู่ · หยุดเสียง")
        }
        override fun onProviderEnabled(provider: String) = Unit
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    private var engine: EngineSound? = null
    private var focus: AudioFocusRequest? = null
    private var profile = profiles[0]
    private var tone = tones[2]
    private var bass = 23
    private var fullRange = true
    private var volume = 7
    private val drive = VirtualDrive()
    private var demoMode = 2
    private var turboEnabled = true
    private var blowOffEnabled = true
    private var turboVolume = 75
    private var blowOffVolume = 85
    private var turboFlutter = false
    private var popEnabled = false
    private var popVolume = 35
    private val turboTrigger = TurboTrigger()
    private var pedal = 0
    private var rpm = 900.0
    private var lastTick = 0L

    private val tick = object : Runnable {
        override fun run() {
            val sound = engine ?: return
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = if (lastTick == 0L) .05 else ((now - lastTick) / 1e9).coerceIn(0.0, .1)
            lastTick = now
            if (realMode) {
                val frame = realDrive.frame(now, dt, profile.limit, demoMode == 4 || demoMode == 6)
                if (frame == null) {
                    sound.muted = true
                    if (gpsHadFix) {
                        sound.report(EngineReading(0, 0, 0, null, 0, 0, now))
                        turboTrigger.reset()
                    }
                    gpsHadFix = false
                    status.text = "WAIT $sourceName · ไม่มีข้อมูล / ปิดเสียง"
                    rpmLabel.text = "— RPM"
                    gearLabel.text = "$sourceName SPEED — km/h"
                    throttleLabel.text = if (bydMode) "คันเร่งจริง BYD —" else "โหลดโดยประมาณ —"
                } else {
                    gpsHadFix = true
                    rpm = frame.rpm
                    val shift = when (frame.shift) {
                        VirtualDrive.Shift.UP -> " · UPSHIFT"
                        VirtualDrive.Shift.DOWN -> " · DOWNSHIFT"
                        VirtualDrive.Shift.NONE -> ""
                    }
                    status.text = if (bydMode) "BYD LINK LIVE · SPEED + PEDAL" else "GPS LIVE · READ ONLY"
                    rpmLabel.text = "${rpm.roundToInt()} RPM"
                    gearLabel.text = "GEAR ${frame.gear}  |  ${frame.speedKmh.roundToInt()} km/h ($sourceName)$shift"
                    throttleLabel.text = if (bydMode) "คันเร่งจริง BYD  ${frame.estimatedPedal}%" else "โหลดโดยประมาณ  ${frame.estimatedPedal}%"
                    sound.report(EngineReading(rpm.roundToInt(), frame.speedKmh.roundToInt(), frame.gear,
                        null, frame.load, frame.throttle, now))
                    sound.muted = false
                    if (turboTrigger.advance(frame.estimatedPedal, frame.gear, rpm)) sound.requestBlowOff()
                }
            } else if (demoMode == 0) {
                val target = 900 + (profile.limit - 900) * (pedal / 100.0)
                val rate = if (target > rpm) 4200.0 else 2400.0
                rpm += (target - rpm).coerceIn(-rate * dt, rate * dt)
                rpmLabel.text = "${rpm.roundToInt()} RPM"
                gearLabel.text = "N · Free rev"
                sound.report(EngineReading(rpm.roundToInt(), 0, 0, null, pedal, pedal, now))
                if (turboTrigger.advance(pedal, 0, rpm)) sound.requestBlowOff()
            } else {
                val frame = drive.advance(dt, pedal, profile.limit,
                    if (demoMode == 2) VirtualDrive.Mode.SPORT else VirtualDrive.Mode.NORMAL)
                rpm = frame.rpm
                rpmLabel.text = "${rpm.roundToInt()} RPM"
                val shift = when (frame.shift) {
                    VirtualDrive.Shift.UP -> " · UPSHIFT"
                    VirtualDrive.Shift.DOWN -> " · DOWNSHIFT"
                    VirtualDrive.Shift.NONE -> ""
                }
                gearLabel.text = "GEAR ${frame.gear}  |  ${frame.speedKmh.roundToInt()} km/h (จำลอง)$shift"
                sound.report(EngineReading(rpm.roundToInt(), frame.speedKmh.roundToInt(), frame.gear,
                    null, frame.load, frame.throttle, now))
                if (turboTrigger.advance(pedal, frame.gear, rpm)) sound.requestBlowOff()
            }
            handler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 26))
        }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(label("SoundFxEV  |  AUDIO LAB", 27, Color.WHITE))
        col.addView(label("0.1.0 Alpha 7.2 · Coast Audio / BYD Diagnostic", 15, Color.LTGRAY))
        status = label("READY · ไม่มีการเชื่อมต่อรถ", 15, Color.rgb(120, 220, 170))
        col.addView(status)
        rpmLabel = label("900 RPM", 48, Color.WHITE)
        col.addView(rpmLabel)
        gearLabel = label("GEAR 1  |  0 km/h (จำลอง)", 22, Color.rgb(120, 220, 170))
        col.addView(gearLabel)
        col.addView(label("DRIVE MODE · ตั้งค่าขณะรถจอด", 14, Color.LTGRAY))
        col.addView(spinner(listOf("Free rev (N)", "Demo Normal · 6-speed", "Demo Sport · 6-speed", "REAL GPS Normal · 6-speed", "REAL GPS Sport · 6-speed", "BYD LINK Pedal Normal · 6-speed", "BYD LINK Pedal Sport · 6-speed")) {
            if (demoMode != it && (engine != null || testingLink)) stopEngine("READY · เปลี่ยนโหมดแล้ว กรุณา Start ใหม่")
            demoMode = it
            gpsDrive.reset()
            bydDrive.reset()
            drive.reset()
            turboTrigger.reset()
            pedal = 0
            if (::throttle.isInitialized) throttle.progress = 0
            rpm = 900.0
            gearLabel.text = if (realMode) "$sourceName SPEED — km/h" else if (demoMode == 0) "N · Free rev" else "GEAR 1  |  0 km/h (จำลอง)"
            updateModeControls()
            applySettings()
        }.apply { setSelection(demoMode) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = Button(this).apply {
            text = "START ENGINE"
            setOnClickListener { if (engine == null) startEngine() else stopEngine() }
        }
        actions.addView(toggle, LinearLayout.LayoutParams(0, dp(64), 1f))
        releaseButton = Button(this).apply {
            text = "ปล่อยคันเร่ง"
            setOnClickListener { if (!realMode) throttle.progress = 0 }
        }
        actions.addView(releaseButton, LinearLayout.LayoutParams(0, dp(64), 1f))
        col.addView(actions)
        linkButton = Button(this).apply {
            text = "AUTHORIZE VEHICLE DATA LINK (ADB)"
            setOnClickListener { authorizeVehicleLink() }
        }
        col.addView(linkButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        diagnosticButton = Button(this).apply {
            text = "TEST BYD SDK · ไม่เปิดเสียง"
            setOnClickListener { testVehicleLink() }
        }
        col.addView(diagnosticButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        diagnosticLabel = label("BYD DIAGNOSTIC · ยังไม่ได้ทดสอบ", 18, Color.rgb(255, 200, 110))
        diagnosticLabel.setTextIsSelectable(true)
        col.addView(diagnosticLabel)
        col.addView(label("BYD LINK: เปิด Network ADB ที่รถแล้วกด AUTHORIZE ขณะจอดรถ • อนุญาตบนจอรถ • อ่านคันเร่ง/ความเร็วเท่านั้น", 14, Color.LTGRAY))
        col.addView(label("GPS: คำนวณโหลดจากความเร็ว • เกียร์จำลองทั้งคู่", 14, Color.LTGRAY))
        col.addView(label("ENGINE PROFILE", 14, Color.LTGRAY))
        profileSpinner = spinner(profiles.map { it.title }) {
            if (realMode && engine != null && profile != profiles[it]) stopEngine("READY · เปลี่ยนเครื่องยนต์แล้ว กรุณา Start ใหม่")
            profile = profiles[it]
            rpm = rpm.coerceAtMost(profile.limit.toDouble())
            drive.reset()
            turboTrigger.reset()
            applySettings()
        }
        col.addView(profileSpinner)
        col.addView(label("EXHAUST", 14, Color.LTGRAY))
        col.addView(spinner(tones.map { it.label }) {
            tone = tones[it]
            applySettings()
        }.apply { setSelection(tones.indexOf(tone)) })

        throttleLabel = label("คันเร่งจำลอง  0%", 17, Color.WHITE)
        col.addView(throttleLabel)
        throttle = seek(100, 0) {
            pedal = it
            throttleLabel.text = "คันเร่งจำลอง  $it%"
        }
        col.addView(throttle)
        col.addView(label("ระดับเสียงทดลอง (เริ่มเบา)", 15, Color.LTGRAY))
        col.addView(seek(15, volume) { volume = it; applySettings() })
        val bassLabel = label("BASS  $bass / 30", 17, Color.WHITE)
        col.addView(bassLabel)
        col.addView(seek(30, bass) {
            bass = it
            bassLabel.text = "BASS  $it / 30"
            applySettings()
        })
        col.addView(CheckBox(this).apply {
            text = "ลำโพงรถ Full range (ไม่ตัดเบสแบบลำโพงมือถือ)"
            isChecked = fullRange
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> fullRange = checked; applySettings() }
        })

        col.addView(label("TURBO & EXHAUST FX", 18, Color.WHITE))
        col.addView(CheckBox(this).apply {
            text = "Turbo Spool · เสียงหวีดตอนเร่ง"
            isChecked = turboEnabled
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> turboEnabled = checked; applySettings() }
        })
        val spoolLabel = label("Turbo volume  $turboVolume%", 15, Color.LTGRAY)
        col.addView(spoolLabel)
        col.addView(seek(100, turboVolume) { turboVolume = it; spoolLabel.text = "Turbo volume  $it%"; applySettings() })
        col.addView(CheckBox(this).apply {
            text = "Blow-off · ถอนคันเร่ง / สับเกียร์ขึ้น"
            isChecked = blowOffEnabled
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> blowOffEnabled = checked; applySettings() }
        })
        col.addView(spinner(listOf("Blow-off · ฟู่", "Flutter · ตุ๊ดตุ๊ดตุ๊ด")) { turboFlutter = it == 1; applySettings() })
        val ventLabel = label("Blow-off volume  $blowOffVolume%", 15, Color.LTGRAY)
        col.addView(ventLabel)
        col.addView(seek(100, blowOffVolume) { blowOffVolume = it; ventLabel.text = "Blow-off volume  $it%"; applySettings() })
        testButton = Button(this).apply {
            text = "TEST BLOW-OFF / FLUTTER"
            setOnClickListener {
                if (realMode || engine == null || !blowOffEnabled || blowOffVolume == 0) {
                    Toast.makeText(this@MainActivity, "Start Engine และเปิด Blow-off ก่อนทดสอบ", Toast.LENGTH_SHORT).show()
                } else engine?.requestBlowOff(audition = true)
            }
        }
        col.addView(testButton)
        col.addView(CheckBox(this).apply {
            text = "Pop & Bang · เสียงท่อไอเสีย"
            isChecked = popEnabled
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> popEnabled = checked; applySettings() }
        })
        val popLabel = label("Pop volume  $popVolume%", 15, Color.LTGRAY)
        col.addView(popLabel)
        col.addView(seek(100, popVolume) { popVolume = it; popLabel.text = "Pop volume  $it%"; applySettings() })

        updateModeControls()
        col.addView(label("ไม่ใช่ AVAS • ไม่ควบคุมรถ • หยุดเสียงเมื่อสลับแอปหรือข้อมูลขาดหาย • ไม่บันทึกพิกัด", 14, Color.LTGRAY))
        col.addView(label("Engine synthesis: Revv / Ange Yaghi (engine-sim). GPL-3.0 / MIT.", 12, Color.GRAY))
    }

    private fun updateModeControls() {
        if (::throttle.isInitialized) throttle.isEnabled = !realMode
        if (::releaseButton.isInitialized) releaseButton.isEnabled = !realMode
        if (::testButton.isInitialized) testButton.isEnabled = !realMode
        if (::linkButton.isInitialized) {
            linkButton.isEnabled = bydMode && engine == null && !linkAuthorizing && !testingLink
            linkButton.text = when {
                linkAuthorizing -> "WAITING FOR ADB APPROVAL"
                linkAuthorized -> "VEHICLE LINK AUTHORIZED · RECHECK"
                else -> "AUTHORIZE VEHICLE DATA LINK (ADB)"
            }
        }
        if (::diagnosticButton.isInitialized) diagnosticButton.isEnabled = bydMode && engine == null && linkAuthorized && !linkAuthorizing && !testingLink
        if (::throttleLabel.isInitialized) throttleLabel.text = when {
            bydMode -> "คันเร่งจริง BYD —"
            gpsMode -> "โหลดโดยประมาณ —"
            else -> "คันเร่งจำลอง  $pedal%"
        }
    }

    private fun startEngine() {
        if (testingLink) { status.text = "รอผลทดสอบ BYD SDK ก่อน"; return }
        if (bydMode && (!linkAuthorized || linkAuthorizing)) {
            status.text = "กด AUTHORIZE VEHICLE DATA LINK ก่อน"
            return
        }
        if (gpsMode) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), GPS_PERMISSION)
                status.text = "อนุญาตตำแหน่งแม่นยำ แล้วกด Start อีกครั้ง"
                return
            }
            if (!runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                status.text = "GPS ไม่พร้อม · เปิด Location ก่อน"
                return
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) stopEngine("เสียงอื่นมีสิทธิ์ก่อน · หยุดเสียงจำลอง")
            }, handler)
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status.text = "เริ่มเสียงไม่ได้ · Audio focus ไม่พร้อม"
            return
        }
        focus = request
        pedal = 0
        throttle.progress = 0
        rpm = 900.0
        drive.reset()
        gpsDrive.reset()
        bydDrive.reset()
        turboTrigger.reset()
        gpsHadFix = false
        lastTick = 0
        engine = EngineSound(applicationContext).also { it.muted = realMode }
        if (gpsMode) {
            try {
                gpsListening = true
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, gpsListener, Looper.getMainLooper())
            } catch (_: Exception) {
                stopEngine("GPS ใช้งานไม่ได้ · กรุณาตรวจสอบสิทธิ์ Location")
                return
            }
        }
        if (bydMode) startBydReader()
        applySettings()
        toggle.text = "STOP ENGINE"
        status.text = if (realMode) "WAIT $sourceName · รอข้อมูลจริง" else "RUNNING · PARKED SOUND DEMO"
        updateModeControls()
        handler.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GPS_PERMISSION && gpsMode && engine == null) {
            status.text = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                "GPS พร้อม · กด Start เพื่อเริ่ม" else "ต้องอนุญาตตำแหน่งแม่นยำสำหรับ Real GPS"
        }
    }

    /** Explicit user action; this only authenticates to local ADB, no vehicle data is read yet. */
    private fun authorizeVehicleLink() {
        if (!bydMode || engine != null || linkAuthorizing || testingLink || destroyed) return
        linkAuthorizing = true
        linkAuthorized = false
        status.text = "ADB AUTH · อนุญาตการดีบักบนหน้าจอรถ"
        updateModeControls()
        val session = BydAdbSession(applicationContext)
        authSession = session
        authTask = bydExecutor.submit {
            val result = runCatching { session.authorize() }
            handler.post {
                if (destroyed || authSession !== session) return@post
                authSession = null
                authTask = null
                linkAuthorizing = false
                linkAuthorized = result.isSuccess
                if (bydMode && engine == null) {
                    status.text = if (linkAuthorized) "ADB AUTHORIZED · กด START ENGINE"
                    else when (val e = result.exceptionOrNull()) {
                        is SocketTimeoutException -> "ADB รออนุญาตหมดเวลา · กด AUTHORIZE ใหม่"
                        is BydAdbSession.LinkException -> e.message ?: "ADB ไม่พร้อม"
                        else -> "ADB เชื่อมต่อไม่ได้ · ตรวจสอบ Network ADB"
                    }
                }
                updateModeControls()
            }
        }
    }

    /** One diagnostic reading, without audio. Stops automatically after the report. */
    private fun testVehicleLink() {
        if (!bydMode || engine != null || !linkAuthorized || testingLink || destroyed) return
        testingLink = true
        status.text = "BYD DIAGNOSTIC · กำลังตรวจสอบ"
        diagnosticLabel.text = "เริ่ม helper ผ่าน ADB..."
        updateModeControls()
        val generation = ++bydGeneration
        val session = BydAdbSession(applicationContext)
        bydSession = session
        bydTask = bydExecutor.submit {
            val result = runCatching {
                session.stream(diagnosticOnly = true, onStatus = { detail ->
                    handler.post {
                        if (generation == bydGeneration && testingLink) diagnosticLabel.text = detail
                    }
                }) {}
            }
            handler.post {
                if (generation != bydGeneration || !testingLink || destroyed) return@post
                testingLink = false
                bydSession = null
                bydTask = null
                if (result.isSuccess) {
                    status.text = "DIAGNOSTIC COMPLETE · ดูผล SPEED / PEDAL ด้านล่าง"
                } else {
                    status.text = "DIAGNOSTIC FAILED · ดูรายละเอียดด้านล่าง"
                    val e = result.exceptionOrNull()
                    val detail = if (e is SocketTimeoutException) "TIMEOUT" else e?.message ?: "UNKNOWN"
                    val last = diagnosticLabel.text.toString()
                    if (!last.contains(detail)) diagnosticLabel.text = "$last\n$detail"
                }
                updateModeControls()
            }
        }
    }

    private fun startBydReader() {
        val generation = ++bydGeneration
        val session = BydAdbSession(applicationContext)
        bydSession = session
        bydTask = bydExecutor.submit {
            try {
                session.stream(onStatus = { detail ->
                    handler.post {
                        if (generation == bydGeneration && bydMode && engine != null) diagnosticLabel.text = detail
                    }
                }) { sample ->
                    handler.post {
                        if (generation == bydGeneration && bydMode && engine != null)
                            bydDrive.acceptSpeed(sample.speedKmh, sample.atNanos,
                                SystemClock.elapsedRealtimeNanos(), sample.pedal)
                    }
                }
                handler.post {
                    if (generation == bydGeneration && bydMode && engine != null)
                        stopEngine("BYD LINK สิ้นสุด · หยุดเสียง")
                }
            } catch (e: Exception) {
                handler.post {
                    if (generation == bydGeneration && bydMode && engine != null) {
                        val message = when (e) {
                            is SocketTimeoutException -> "BYD LINK ไม่ตอบกลับ · ตรวจสอบ ADB"
                            is BydAdbSession.LinkException -> e.message ?: "BYD LINK ไม่พร้อม"
                            else -> "BYD LINK ขาดการเชื่อมต่อ"
                        }
                        diagnosticLabel.text = "${diagnosticLabel.text}\n$message"
                        stopEngine(message)
                    }
                }
            } finally {
                session.close()
            }
        }
    }

    private fun applySettings() {
        engine?.apply(EngineSoundSettings(
            enabled = true,
            layout = profile.layout,
            note = tone.note,
            volume = volume,
            crackle = popEnabled,
            bass = bass,
            deepTone = tone.deep,
            forceFullRange = fullRange,
            surround = false,
            smoothing = if (demoMode == 0) 8 else 2,
            turboEnabled = turboEnabled,
            blowOffEnabled = blowOffEnabled,
            turboVolume = turboVolume,
            blowOffVolume = blowOffVolume,
            turboFlutter = turboFlutter,
            popVolume = popVolume,
            startUp = !realMode,
        ))
    }

    private fun stopEngine(message: String = "READY · หยุดเสียงแล้ว") {
        handler.removeCallbacks(tick)
        bydGeneration++
        testingLink = false
        bydSession?.close()
        bydSession = null
        bydTask?.cancel(true)
        bydTask = null
        if (gpsListening) {
            gpsListening = false
            runCatching { locationManager.removeUpdates(gpsListener) }
        }
        gpsHadFix = false
        gpsDrive.reset()
        bydDrive.reset()
        engine?.let {
            it.muted = true
            it.release()
        }
        engine = null
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
        if (::toggle.isInitialized) toggle.text = "START ENGINE"
        if (::status.isInitialized) status.text = message
        if (::throttle.isInitialized) throttle.progress = 0
        if (::rpmLabel.isInitialized) rpmLabel.text = if (realMode) "— RPM" else "900 RPM"
        drive.reset()
        turboTrigger.reset()
        if (::gearLabel.isInitialized) gearLabel.text = if (realMode) "$sourceName SPEED — km/h" else if (demoMode == 0) "N · Free rev" else "GEAR 1  |  0 km/h (จำลอง)"
        updateModeControls()
    }

    override fun onPause() {
        stopEngine()
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true
        authSession?.close()
        authSession = null
        authTask?.cancel(true)
        stopEngine()
        bydExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object { const val GPS_PERMISSION = 101 }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).roundToInt()
    private fun label(value: String, size: Int, color: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun seek(maximum: Int, start: Int, change: (Int) -> Unit) = SeekBar(this).apply {
        max = maximum
        progress = start
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = change(value)
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }
    private fun spinner(items: List<String>, choose: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = choose(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
