package com.sacool.soundfxev

import org.junit.Assert.*
import org.junit.Test

class TurboTriggerTest {
    @Test fun liftAndShiftAreOneShotEvents() {
        val t = TurboTrigger()
        assertFalse(t.advance(100, 1, 6500.0))
        assertTrue(t.advance(100, 2, 4500.0))
        repeat(5) { assertFalse(t.advance(100, 2, 4500.0)) }
        assertTrue(t.advance(0, 2, 4300.0))
        assertFalse(t.advance(0, 2, 4000.0))
        assertFalse(t.advance(0, 1, 5000.0))
    }
    @Test fun idleAndResetDoNotTrigger() {
        val t = TurboTrigger()
        t.advance(100, 0, 900.0)
        assertFalse(t.advance(0, 0, 900.0))
        t.advance(100, 1, 6500.0)
        t.reset()
        assertFalse(t.advance(100, 2, 4500.0))
    }
    @Test fun virtualDriveUpshiftsProduceOneEventEach() {
        val drive = VirtualDrive()
        val t = TurboTrigger()
        var lastGear = 1
        var shifts = 0
        var vents = 0
        repeat(600) {
            val frame = drive.advance(0.05, 100, 8000, VirtualDrive.Mode.SPORT)
            if (frame.gear > lastGear) shifts++
            if (t.advance(100, frame.gear, frame.rpm)) vents++
            lastGear = frame.gear
        }
        assertTrue(shifts >= 3)
        assertEquals(shifts, vents)
    }
}
