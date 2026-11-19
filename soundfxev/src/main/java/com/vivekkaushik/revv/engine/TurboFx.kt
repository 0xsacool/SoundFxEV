package com.vivekkaushik.revv.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Compressor whistle and vented air; intentionally separate from the exhaust DSP. */
internal class TurboFx(private val sampleRate: Double) {
    private val noise = Noise(0x54455242)
    private val high = Biquad.highPass(900.0, sampleRate)
    private val low = Biquad.lowPass(6200.0, sampleRate)
    private val attack = 1 - exp(-1 / (0.17 * sampleRate))
    private val release = 1 - exp(-1 / (0.12 * sampleRate))
    private var target = 0.0
    private var spool = 0.0
    private var pressure = 0.0
    private var age = 1.0
    private var phase = 0.0
    private var flutterPhase = 0.0
    private var ventPhase = 0.0

    fun setTarget(rpm: Double, load: Double, running: Boolean) {
        if (!running) { reset(); return }
        target = ((rpm - 1300.0) / 3000.0).coerceIn(0.0, 1.0) *
            ((load - 0.12) / 0.88).coerceIn(0.0, 1.0)
    }

    /** Called once per explicit shift/lift, after a loaded high-RPM reading; audition can force full pressure. */
    fun vent(audition: Boolean = false) {
        pressure = if (audition) 1.0 else (spool * 1.25).coerceIn(0.40, 1.0)
        age = 0.0
        flutterPhase = 0.0
    }

    fun sample(spoolLevel: Double, ventLevel: Double, flutter: Boolean): Double {
        spool += (target - spool) * (if (target > spool) attack else release)
        val hiss = low.process(high.process(noise.next() * 2.0 - 1.0))
        phase += 2 * PI * (720 + spool * 2400) / sampleRate
        if (phase > 2 * PI) phase -= 2 * PI
        val whistle = spool * (0.34 * sin(phase) + 0.075 * sin(2 * phase) + 0.26 * hiss)
        var air = 0.0
        if (age < 0.65 && pressure > 0.0) {
            val envelope = (1 - exp(-age / 0.003)) * exp(-age / (if (flutter) 0.18 else 0.12))
            flutterPhase += 2 * PI * (18 - 13 * age.coerceAtMost(0.6)) / sampleRate
            val pulse = if (flutter) {
                val p = (1 + sin(flutterPhase)) * 0.5
                0.10 + 0.90 * p * p
            } else 1.0
            ventPhase += 2 * PI * (1400 + 800 * exp(-age / 0.09)) / sampleRate
            if (ventPhase > 2 * PI) ventPhase -= 2 * PI
            air = pressure * envelope * pulse * (1.05 * hiss + 0.18 * sin(ventPhase))
            age += 1 / sampleRate
        }
        return spoolLevel.coerceIn(0.0, 1.0) * whistle + ventLevel.coerceIn(0.0, 1.0) * air
    }

    fun cancelVent() { pressure = 0.0; age = 1.0 }
    fun reset() { target = 0.0; spool = 0.0; cancelVent() }
}
