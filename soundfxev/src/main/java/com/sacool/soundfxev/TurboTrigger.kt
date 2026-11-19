package com.sacool.soundfxev

/** Edge detection uses the driver's pedal, not the momentary transmission torque cut. */
internal class TurboTrigger {
    private var priorPedal = 0
    private var priorGear = 0
    private var priorRpm = 900.0

    fun advance(pedal: Int, gear: Int, rpm: Double): Boolean {
        val loaded = priorPedal >= 35 && priorRpm >= 2200.0
        val lift = pedal <= 15 && priorPedal >= 35
        val upshift = priorGear >= 1 && gear > priorGear
        priorPedal = pedal
        priorGear = gear
        priorRpm = rpm
        return loaded && (lift || upshift)
    }

    fun reset() { priorPedal = 0; priorGear = 0; priorRpm = 900.0 }
}
