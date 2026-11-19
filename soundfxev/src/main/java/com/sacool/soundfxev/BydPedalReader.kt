package com.sacool.soundfxev

import android.content.Context
import java.lang.reflect.Method

/** Direct SDK getters only. No shell, binder transactions, setters, or access bypass.
 * Availability depends on firmware and the app's ordinary permissions.
 */
internal class BydPedalReader private constructor(
    private val device: Any, private val speedGetter: Method, private val pedalGetter: Method
) {
    data class Sample(val speedKmh: Double, val pedalPercent: Int)

    fun read(): Sample? {
        val speed = (speedGetter.invoke(device) as? Number)?.toDouble() ?: return null
        val pedal = (pedalGetter.invoke(device) as? Number)?.toDouble() ?: return null
        if (!speed.isFinite() || speed !in 0.0..250.0 ||
            !pedal.isFinite() || pedal !in 0.0..100.0) return null
        return Sample(speed, pedal.toInt())
    }

    companion object {
        fun open(context: Context): BydPedalReader {
            val cls = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
            val device = try {
                cls.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext ?: context)
            } catch (_: NoSuchMethodException) {
                cls.getMethod("getInstance").invoke(null)
            } ?: error("BYD speed device unavailable")
            return BydPedalReader(device, cls.getMethod("getCurrentSpeed"), cls.getMethod("getAccelerateDeepness"))
        }
    }
}
