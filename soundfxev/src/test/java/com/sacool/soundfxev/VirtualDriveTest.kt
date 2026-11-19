package com.sacool.soundfxev

import org.junit.Assert.*
import org.junit.Test

class VirtualDriveTest {
    @Test fun sportAutomaticallyUpshiftsAndCutsRpm() {
        val drive = VirtualDrive()
        var peakGear = 1
        var cuts = 0
        var previous = drive.advance(0.05, 0, 8000, VirtualDrive.Mode.SPORT)
        repeat(1200) {
            val frame = drive.advance(0.05, 100, 8000, VirtualDrive.Mode.SPORT)
            peakGear = maxOf(peakGear, frame.gear)
            if (frame.shift == VirtualDrive.Shift.UP) {
                assertEquals(0, frame.throttle)
                if (frame.rpm < previous.rpm) cuts++
            }
            assertTrue(frame.rpm in 900.0..8000.0)
            previous = frame
        }
        assertEquals(6, peakGear)
        assertTrue(cuts > 0)
    }
    @Test fun liftingOffDownshiftsAndStops() {
        val drive = VirtualDrive()
        repeat(700) { drive.advance(0.05, 100, 8000, VirtualDrive.Mode.NORMAL) }
        var frame = drive.advance(0.05, 0, 8000, VirtualDrive.Mode.NORMAL)
        var down = false
        repeat(700) {
            frame = drive.advance(0.05, 0, 8000, VirtualDrive.Mode.NORMAL)
            if (frame.shift == VirtualDrive.Shift.DOWN) down = true
            assertTrue(frame.gear in 1..6)
            assertTrue(frame.speedKmh >= 0.0)
        }
        assertTrue(down)
        assertEquals(1, frame.gear)
        assertEquals(0.0, frame.speedKmh, 0.01)
        assertEquals(900.0, frame.rpm, 0.01)
    }
}
