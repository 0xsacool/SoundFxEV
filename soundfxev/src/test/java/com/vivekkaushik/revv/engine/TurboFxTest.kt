package com.vivekkaushik.revv.engine

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboFxTest {
    @Test fun pressureReleaseIsAudibleAndFinite() {
        val fx = TurboFx(48000.0)
        repeat(480) {
            fx.setTarget(6000.0, 1.0, true)
            repeat(100) { assertTrue(fx.sample().isFinite()) }
        }
        fx.setTarget(4000.0, 0.0, true)
        var peak = 0.0
        repeat(4800) { val value = fx.sample(); assertTrue(value.isFinite()); peak = maxOf(peak, abs(value)) }
        assertTrue(peak > 0.03)
        fx.reset()
        repeat(100) { assertTrue(abs(fx.sample()) < 0.001) }
    }
}
