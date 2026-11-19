package com.sacool.soundfxev

/** Fictional powertrain for parked audio demos; never reads or controls a real car. */
class VirtualDrive {
    enum class Mode { NORMAL, SPORT }
    enum class Shift { NONE, UP, DOWN }
    data class Frame(val speedKmh: Double, val rpm: Double, val gear: Int,
        val throttle: Int, val load: Int, val shift: Shift)
    private val ratios = doubleArrayOf(120.0, 80.0, 60.0, 47.0, 40.0, 32.0)
    private var speed = 0.0
    private var gear = 1
    private var shift = Shift.NONE
    private var timer = 0.0
    private var cooldown = 0.0
    private var rpm = 900.0

    fun reset() {
        speed = 0.0; gear = 1; shift = Shift.NONE; timer = 0.0; cooldown = 0.0; rpm = 900.0
    }

    fun advance(dtSeconds: Double, pedalPercent: Int, redline: Int, mode: Mode): Frame {
        val dt = dtSeconds.coerceIn(0.0, 0.1)
        val pedal = pedalPercent.coerceIn(0, 100)
        val gas = pedal / 100.0
        cooldown = (cooldown - dt).coerceAtLeast(0.0)
        timer = (timer - dt).coerceAtLeast(0.0)
        if (timer == 0.0) shift = Shift.NONE
        val accelerating = if (shift == Shift.UP) 0.0 else gas
        val traction = (if (mode == Mode.SPORT) 36.0 else 29.0) *
            (1.0 - 0.045 * (gear - 1)) * (1.0 - speed / 280.0).coerceAtLeast(0.25)
        val drag = if (pedal < 3) 13.0 + speed * 0.035 else 1.0 + speed * 0.035
        speed = (speed + (traction * accelerating - drag) * dt).coerceIn(0.0, 230.0)
        val current = speed * ratios[gear - 1]
        val up = redline * (if (mode == Mode.SPORT) (0.73 + gas * 0.19) else (0.52 + gas * 0.23))
        val down = if (mode == Mode.SPORT) 2700.0 else 1950.0
        if (cooldown == 0.0 && timer == 0.0) {
            if (gear < 6 && pedal >= 8 && current >= up) {
                gear++
                shift = Shift.UP
                timer = 0.25
                cooldown = 0.65
            } else if (gear > 1 && (current < down || pedal > 85 && current < 3200.0) &&
                speed * ratios[gear - 2] < redline * 0.94) {
                gear--
                shift = Shift.DOWN
                timer = 0.22
                cooldown = 0.65
            }
        }
        val coupled = speed * ratios[gear - 1]
        val slip = if (gear == 1) gas * 950.0 * (1.0 - speed / 18.0).coerceIn(0.0, 1.0) else 0.0
        val blip = if (shift == Shift.DOWN) 350.0 * (timer / 0.22) else 0.0
        val target = (900.0 + slip).coerceAtLeast(coupled + blip).coerceAtMost(redline.toDouble())
        val rate = if (shift != Shift.NONE) 16000.0 else 6500.0
        rpm = (rpm + (target - rpm).coerceIn(-rate * dt, rate * dt)).coerceIn(900.0, redline.toDouble())
        val reportedPedal = when (shift) {
            Shift.UP -> 0
            Shift.DOWN -> maxOf(28, pedal)
            Shift.NONE -> pedal
        }
        val load = if (reportedPedal == 0) 0 else (20 + 80 * reportedPedal / 100).coerceAtMost(100)
        return Frame(speed, rpm, gear, reportedPedal, load, shift)
    }
}
