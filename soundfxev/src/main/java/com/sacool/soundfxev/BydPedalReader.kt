package com.sacool.soundfxev

import android.content.Context
import java.lang.reflect.Method

/** Direct SDK getters only. Resolve and report speed and pedal separately. */
internal class BydPedalReader private constructor(
    private val device: Any, private val speedGetter: Result<Method>, private val pedalGetter: Result<Method>
) {
    data class Field(val value: Double?, val detail: String, val failed: Boolean)
    data class Reading(val speed: Field, val pedal: Field) {
        val report get() = "SPEED: ${speed.detail} | PEDAL: ${pedal.detail}"
        val fatal get() = speed.failed || pedal.failed
    }

    private fun field(getter: Result<Method>, name: String, range: ClosedFloatingPointRange<Double>): Field {
        return try {
            val method = getter.getOrThrow()
            val raw = method.invoke(device)
            val value = (raw as? Number)?.toDouble()
            if (value == null || !value.isFinite() || value !in range)
                Field(null, "INVALID $name", false)
            else Field(value, "OK $value", false)
        } catch (t: Throwable) {
            Field(null, "FAIL $name: ${BydLinkProtocol.describe(t)}", true)
        }
    }

    fun read(step: (String) -> Unit): Reading {
        step("READ_SPEED")
        val speed = field(speedGetter, "getCurrentSpeed", 0.0..250.0)
        step("READ_PEDAL")
        val pedal = field(pedalGetter, "getAccelerateDeepness", 0.0..100.0)
        return Reading(speed, pedal)
    }

    companion object {
        fun open(context: Context, step: (String) -> Unit): BydPedalReader {
            step("DEVICE_CLASS")
            val cls = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
            step("DEVICE_INSTANCE")
            val device = try {
                cls.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext ?: context)
            } catch (_: NoSuchMethodException) {
                cls.getMethod("getInstance").invoke(null)
            } ?: error("BYD speed device unavailable")
            step("RESOLVE_GETTERS")
            return BydPedalReader(device,
                runCatching { cls.getMethod("getCurrentSpeed") },
                runCatching { cls.getMethod("getAccelerateDeepness") })
        }
    }
}
