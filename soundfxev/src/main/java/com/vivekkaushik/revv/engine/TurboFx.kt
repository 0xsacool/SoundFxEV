package com.vivekkaushik.revv.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Procedural spool and pressure-release sound, mixed ahead of the output limiter. */
internal class TurboFx(private val sampleRate: Double) {
    private val noise = Noise(0x54455242)
    private val high = Biquad.highPass(850.0, sampleRate)
    private val low = Biquad.lowPass(3600.0, sampleRate)
    private val attack = 1 - exp(-1 / (0.20 * sampleRate))
    private val release = 1 - exp(-1 / (0.085 * sampleRate))
    private val blowDecay = exp(-1 / (0.14 * sampleRate))
    private var target = 0.0
    private var spool = 0.0
    private var blow = 0.0
    private var phase = 0.0
    private var armed = false

    fun setTarget(rpm: Double, load: Double, running: Boolean) {
        target = if (running) ((rpm - 1500.0) / 3300.0).coerceIn(0.0, 1.0) *
            ((load - 0.15) / 0.85).coerceIn(0.0, 1.0) else 0.0
        if (!running) { reset(); return }
        if (spool > 0.30 && load > 0.50) armed = true
        if (armed && load < 0.20) {
            blow = (spool * 1.4).coerceAtMost(1.0)
            armed = false
        }
    }

    fun sample(): Double {
        spool += (target - spool) * (if (target > spool) attack else release)
        val hiss = low.process(high.process(noise.next() * 2.0 - 1.0))
        phase += 2 * PI * (650 + spool * 1700) / sampleRate
        if (phase > 2 * PI) phase -= 2 * PI
        val out = spool * (0.035 * sin(phase) + 0.05 * hiss) + blow * 0.30 * hiss
        blow *= blowDecay
        return out
    }

    fun reset() { target = 0.0; spool = 0.0; blow = 0.0; armed = false }
}
