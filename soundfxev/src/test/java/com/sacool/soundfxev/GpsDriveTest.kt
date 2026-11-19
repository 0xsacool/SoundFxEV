package com.sacool.soundfxev

import org.junit.Assert.*
import org.junit.Test

class GpsDriveTest {
    private val sec = 1_000_000_000L

    @Test fun measuredPedalTakesPriorityOverSpeedDerivative() {
        val d = GpsDrive(maxAgeNanos = 800_000_000L)
        val turbo = TurboTrigger()
        val t = sec
        assertTrue(d.acceptSpeed(36.0, t, t, measuredPedal = 80))
        val press = d.frame(t, 0.05, 8000, true)!!
        assertEquals(80, press.estimatedPedal)
        assertEquals(80, press.throttle)
        assertFalse(turbo.advance(press.estimatedPedal, press.gear, press.rpm))
        val next = t + 100_000_000L
        // Constant speed, but the actual pedal is now released.
        assertTrue(d.acceptSpeed(36.0, next, next, measuredPedal = 0))
        val lift = d.frame(next, 0.05, 8000, true)!!
        assertEquals(0, lift.estimatedPedal)
        assertTrue(turbo.advance(lift.estimatedPedal, lift.gear, lift.rpm))
        assertFalse(turbo.advance(lift.estimatedPedal, lift.gear, lift.rpm))
        assertFalse(d.acceptSpeed(36.0, next + 100_000_000L, next + 100_000_000L, measuredPedal = 101))
        assertNull(d.frame(next + 900_000_000L, 0.05, 8000, true))
    }
    @Test fun actualPedalIsVisibleAtStandstill() {
        val d = GpsDrive()
        assertTrue(d.acceptSpeed(0.0, sec, sec, measuredPedal = 42))
        val frame = d.frame(sec, 0.05, 8000, true)!!
        assertEquals(42, frame.estimatedPedal)
        assertEquals(900.0, frame.rpm, 0.01)
        assertEquals(1, frame.gear)
    }

    @Test fun noFixOrExpiredFixIsSilent() {
        val d = GpsDrive()
        assertNull(d.frame(sec, 0.05, 8000, false))
        assertFalse(d.acceptSpeed(80.0, sec, 5 * sec))
        assertFalse(d.acceptSpeed(80.0, 2 * sec, sec))
        assertTrue(d.acceptSpeed(80.0, 5 * sec, 5 * sec))
        assertNotNull(d.frame(5 * sec, 0.05, 8000, false))
        assertNull(d.frame(8 * sec, 0.05, 8000, false))
        assertTrue(d.acceptSpeed(0.0, 9 * sec, 9 * sec))
        val idle = d.frame(9 * sec, 0.05, 8000, false)!!
        assertEquals(900.0, idle.rpm, 0.01)
        assertEquals(0, idle.estimatedPedal)
        assertEquals(1, idle.gear)
    }

    @Test fun badSpeedAndOutOfOrderFixDoNotRefreshClock() {
        val d = GpsDrive()
        assertTrue(d.acceptSpeed(36.0, sec, sec))
        assertFalse(d.acceptSpeed(36.0, sec, 2 * sec))
        assertFalse(d.acceptSpeed(200.0, 2 * sec, 2 * sec))
        assertFalse(d.acceptSpeed(Double.NaN, 2 * sec, 2 * sec))
        assertFalse(d.acceptSpeed(-1.0, 2 * sec, 2 * sec))
        assertNull(d.frame(4 * sec, 0.05, 8000, true))
    }

    @Test fun rollingStartDoesNotInventShiftsOrThrottle() {
        val d = GpsDrive()
        assertTrue(d.acceptSpeed(110.0, sec, sec))
        val frame = d.frame(sec, 0.05, 8000, false)!!
        assertTrue(frame.gear > 1)
        assertEquals(VirtualDrive.Shift.NONE, frame.shift)
        assertEquals(22, frame.estimatedPedal)
    }

    @Test fun gpsAccelerationUpshiftsAndLiftCanReleaseTurbo() {
        val d = GpsDrive()
        val turbo = TurboTrigger()
        var lastGear = 1
        var upshifts = 0
        var vents = 0
        var now = sec
        assertTrue(d.acceptSpeed(0.0, now, now))
        // 1 Hz speed data, 20 Hz audio control loop; 2 m/s² acceleration.
        for (i in 0..19) {
            val kmh = i * 7.2
            if (i != 0) assertTrue(d.acceptSpeed(kmh, now, now))
            repeat(20) {
                val frame = d.frame(now, 0.05, 8000, true)!!
                assertTrue(frame.rpm in 900.0..8000.0)
                assertTrue(frame.gear in 1..6)
                if (frame.gear > lastGear) upshifts++
                if (turbo.advance(frame.estimatedPedal, frame.gear, frame.rpm)) vents++
                lastGear = frame.gear
                now += sec / 20
            }
        }
        assertTrue("upshifts: $upshifts", upshifts >= 2)
        assertTrue("shift vents: $vents", vents >= 2)
        // Speed stops rising: estimated pedal lifts, a single release event.
        assertTrue(d.acceptSpeed(19 * 7.2, now, now))
        val lifted = d.frame(now, 0.05, 8000, true)!!
        assertEquals(0, lifted.estimatedPedal)
        assertTrue(turbo.advance(lifted.estimatedPedal, lifted.gear, lifted.rpm))
        assertFalse(turbo.advance(lifted.estimatedPedal, lifted.gear, lifted.rpm))
        // Braking: no estimated throttle, return to idle in first gear after stopping.
        repeat(20) { i ->
            now += sec
            assertTrue(d.acceptSpeed((19 * 7.2 - (i + 1) * 7.2).coerceAtLeast(0.0), now, now))
            repeat(20) { j ->
                val frame = d.frame(now + j * sec / 20, 0.05, 8000, true)!!
                assertEquals(0, frame.estimatedPedal)
            }
        }
        val stopped = d.frame(now + sec, 0.05, 8000, true)!!
        assertEquals(1, stopped.gear)
        assertEquals(900.0, stopped.rpm, 0.1)
    }
}
