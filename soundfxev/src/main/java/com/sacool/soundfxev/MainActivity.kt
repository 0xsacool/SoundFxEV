package com.sacool.soundfxev

import android.app.Activity
import android.content.Context
import android.graphics.Color
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

/** Stationary audio lab. No vehicle access, CAN writes, GPS, wake lock or background service. */
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
    private lateinit var status: TextView
    private lateinit var throttleLabel: TextView
    private lateinit var throttle: SeekBar
    private lateinit var toggle: Button
    private lateinit var profileSpinner: Spinner
    private var engine: EngineSound? = null
    private var focus: AudioFocusRequest? = null
    private var profile = profiles[0]
    private var tone = tones[2]
    private var bass = 23
    private var fullRange = true
    private var volume = 7
    private var pedal = 0
    private var rpm = 900.0
    private var lastTick = 0L

    private val tick = object : Runnable {
        override fun run() {
            val sound = engine ?: return
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = if (lastTick == 0L) .05 else ((now - lastTick) / 1e9).coerceIn(0.0, .1)
            lastTick = now
            val target = 900 + (profile.limit - 900) * (pedal / 100.0)
            val rate = if (target > rpm) 4200.0 else 2400.0
            rpm += (target - rpm).coerceIn(-rate * dt, rate * dt)
            val rev = rpm.roundToInt()
            rpmLabel.text = "$rev RPM"
            sound.report(EngineReading(
                rpm = rev, speedKmh = 0, gear = 0, airFill = null,
                engineLoad = pedal, throttle = pedal, atNanos = now
            ))
            handler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setBackgroundColor(Color.rgb(18, 21, 26))
        }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(label("SoundFxEV  |  AUDIO LAB", 27, Color.WHITE))
        col.addView(label("0.1.0 Alpha 2 · ทดสอบเสียงขณะรถจอดเท่านั้น", 15, Color.LTGRAY))
        status = label("READY · ไม่มีการเชื่อมต่อรถ", 15, Color.rgb(120, 220, 170))
        col.addView(status)
        rpmLabel = label("900 RPM", 48, Color.WHITE)
        col.addView(rpmLabel)
        col.addView(label("ENGINE PROFILE", 14, Color.LTGRAY))
        profileSpinner = spinner(profiles.map { it.title }) {
            profile = profiles[it]
            rpm = rpm.coerceAtMost(profile.limit.toDouble())
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

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = Button(this).apply {
            text = "START ENGINE"
            setOnClickListener { if (engine == null) startEngine() else stopEngine() }
        }
        actions.addView(toggle, LinearLayout.LayoutParams(0, dp(64), 1f))
        actions.addView(Button(this).apply {
            text = "ปล่อยคันเร่ง"
            setOnClickListener { throttle.progress = 0 }
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        col.addView(actions)
        col.addView(label("ไม่ใช่ AVAS • ไม่ควบคุมรถ • หยุดเสียงเมื่อสลับแอป", 14, Color.LTGRAY))
        col.addView(label("Engine synthesis: Revv / Ange Yaghi (engine-sim). GPL-3.0 / MIT.", 12, Color.GRAY))
    }

    private fun startEngine() {
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
        lastTick = 0
        engine = EngineSound(applicationContext)
        applySettings()
        toggle.text = "STOP ENGINE"
        status.text = "RUNNING · MANUAL DEMO"
        handler.post(tick)
    }

    private fun applySettings() {
        engine?.apply(EngineSoundSettings(
            enabled = true,
            layout = profile.layout,
            note = tone.note,
            volume = volume,
            crackle = true,
            bass = bass,
            deepTone = tone.deep,
            forceFullRange = fullRange,
            surround = false,
            smoothing = 8,
            startUp = true,
        ))
    }

    private fun stopEngine(message: String = "READY · หยุดเสียงแล้ว") {
        handler.removeCallbacks(tick)
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
        if (::rpmLabel.isInitialized) rpmLabel.text = "900 RPM"
    }

    override fun onPause() {
        stopEngine()
        super.onPause()
    }
    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

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
