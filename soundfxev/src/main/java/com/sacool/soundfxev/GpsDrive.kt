package com.sacool.soundfxev

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Virtual gearbox estimating load from GPS speed changes.
 * Accepts only fresh monotonic samples; data loss invalidates the frame and resets estimation.
 */
internal class GpsDrive(private val maxAgeNanos: Long = MAX_AGE_NANOS) {
    data class Frame(val speedKmh: Double, val rpm: Double, val gear: Int,
        val estimatedPedal: Int, val throttle: Int, val load: Int, val shift: VirtualDrive.Shift)

    private val ratios = doubleArrayOf(130.0, 86.0, 63.0, 48.0, 39.0, 31.0)
    private var fixAt: Long? = null
    private var fixSpeed = 0.0
    private var speed = 0.0
    private var accel = 0.0
    private var pedal = 0
    private var gear = 1
    private var rpm = 900.0
    private var initialized = false
    private var shift = VirtualDrive.Shift.NONE
    private var shiftLeft = 0.0
    private var cooldown = 0.0

    fun acceptSpeed(kmh: Double, atNanos: Long, nowNanos: Long): Boolean {
        if (!kmh.isFinite() || kmh !in 0.0..250.0 ||
            nowNanos - atNanos !in 0L..maxAgeNanos) return false
        val previous = fixAt
        if (previous != null && atNanos <= previous) return false
        if (previous == null || atNanos - previous > maxAgeNanos) {
            reset()
            speed = kmh
            fixSpeed = kmh
            fixAt = atNanos
            pedal = if (kmh >= 3) 22 else 0
            return true
        }
        val dt = (atNanos - previous) / 1e9
        val raw = (kmh - fixSpeed) / (3.6 * dt)
        if (abs(raw) > 12.0) return false // reject unrealistic jumps / GPS spikes
        val lastAccel = accel
        accel = if (dt >= 0.65) 0.80 * raw + 0.20 * accel else 0.50 * raw + 0.50 * accel
        pedal = when {
            kmh < 3 -> 0
            raw < -0.25 || accel < -0.25 -> 0
            lastAccel > 0.65 && raw < 0.15 -> 0 // abrupt lift into coasting
            accel < 0.18 -> 22 // steady-speed cruising
            else -> (30 + 70 * (accel / 2.7).coerceIn(0.0, 1.0)).roundToInt()
        }
        fixSpeed = kmh
        fixAt = atNanos
        return true
    }

    fun frame(nowNanos: Long, dtSeconds: Double, redline: Int, sport: Boolean): Frame? {
        val at = fixAt ?: return null
        if (nowNanos - at !in 0L..maxAgeNanos) {
            reset()
            return null
        }
        val dt = dtSeconds.coerceIn(0.0, 0.1)
        speed += (fixSpeed - speed) * (1 - exp(-dt / 0.20))
        if (fixSpeed < 1.5 && speed < 3.0) speed = 0.0
        val gas = pedal / 100.0
        val up = redline * (if (sport) (0.61 + 0.30 * gas) else (0.43 + 0.34 * gas))
        val down = if (sport) 2350.0 else 1550.0
        if (!initialized) {
            // Starting while rolling selects the gear silently, with no burst of fake upshifts.
            while (gear < 6 && speed * ratios[gear - 1] >= up) gear++
            rpm = (speed * ratios[gear - 1]).coerceIn(900.0, redline.toDouble())
            initialized = true
        }
        shiftLeft = (shiftLeft - dt).coerceAtLeast(0.0)
        cooldown = (cooldown - dt).coerceAtLeast(0.0)
        if (shiftLeft == 0.0) shift = VirtualDrive.Shift.NONE
        val coupled = speed * ratios[gear - 1]
        if (speed < 2.5) {
            gear = 1
            shift = VirtualDrive.Shift.NONE
            shiftLeft = 0.0
            cooldown = 0.0
        } else if (cooldown == 0.0 && shiftLeft == 0.0) {
            if (gear < 6 && coupled >= up) {
                gear++
                shift = VirtualDrive.Shift.UP
                shiftLeft = 0.25
                cooldown = 0.85
            } else if (gear > 1 && (coupled < down || gas > 0.78 && coupled < 3000) &&
                speed * ratios[gear - 2] < up * 0.90) {
                gear--
                shift = VirtualDrive.Shift.DOWN
                shiftLeft = 0.22
                cooldown = 0.85
            }
        }
        val slip = if (gear == 1 && speed > 2.5) gas * 700 * (1 - speed / 18).coerceIn(0.0, 1.0) else 0.0
        val blip = if (shift == VirtualDrive.Shift.DOWN) 350 * shiftLeft / 0.22 else 0.0
        val target = if (speed < 2.5) 900.0 else
            (speed * ratios[gear - 1] + blip).coerceAtLeast(900.0 + slip).coerceAtMost(redline.toDouble())
        val rate = if (shift != VirtualDrive.Shift.NONE) 14000.0 else 5000.0
        rpm = (rpm + (target - rpm).coerceIn(-rate * dt, rate * dt)).coerceIn(900.0, redline.toDouble())
        val estimated = if (speed < 2.5) 0 else pedal
        val throttle = when (shift) {
            VirtualDrive.Shift.UP -> 0
            VirtualDrive.Shift.DOWN -> maxOf(28, estimated)
            VirtualDrive.Shift.NONE -> estimated
        }
        val load = if (throttle == 0) 0 else 20 + 80 * throttle / 100
        return Frame(speed, rpm, gear, estimated, throttle, load, shift)
    }

    fun reset() {
        fixAt = null
        fixSpeed = 0.0
        speed = 0.0
        accel = 0.0
        pedal = 0
        gear = 1
        rpm = 900.0
        initialized = false
        shift = VirtualDrive.Shift.NONE
        shiftLeft = 0.0
        cooldown = 0.0
    }

    companion object {
        const val MAX_AGE_NANOS = 2_500_000_000L
    }
}
