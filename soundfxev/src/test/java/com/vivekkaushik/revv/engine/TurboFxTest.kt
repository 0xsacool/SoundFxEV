package com.vivekkaushik.revv.engine

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboFxTest {
    private fun rms(fx: TurboFx, spool: Double, vent: Double, flutter: Boolean = false, frames: Int = 4800): Double {
        var energy = 0.0
        repeat(frames) {
            val x = fx.sample(spool, vent, flutter)
            assertTrue(x.isFinite())
            energy += x * x
        }
        return sqrt(energy / frames)
    }

    @Test fun independentSpoolAndVentLevels() {
        val fx = TurboFx(48000.0)
        fx.setTarget(6000.0, 1.0, true)
        rms(fx, 0.0, 0.0, frames = 48000)
        assertTrue(rms(fx, 0.75, 0.0) > 0.15)
        assertTrue(rms(fx, 0.0, 0.0) == 0.0)
        fx.setTarget(4000.0, 0.0, true)
        fx.vent()
        assertTrue(rms(fx, 0.0, 0.85) > 0.08)
        fx.cancelVent()
        assertTrue(rms(fx, 0.0, 1.0) == 0.0)
    }

    @Test fun auditionAtIdleAndFlutterDecay() {
        val fx = TurboFx(48000.0)
        fx.setTarget(900.0, 0.0, true)
        fx.vent(audition = true)
        assertTrue(rms(fx, 0.0, 1.0, true) > 0.035)
        rms(fx, 0.0, 1.0, true, frames = 48000)
        assertTrue(rms(fx, 0.0, 1.0, true) == 0.0)
        fx.vent(true)
        fx.reset()
        assertTrue(rms(fx, 1.0, 1.0) == 0.0)
    }
}
