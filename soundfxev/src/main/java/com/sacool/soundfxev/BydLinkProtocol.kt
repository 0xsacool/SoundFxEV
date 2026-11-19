package com.sacool.soundfxev

import java.lang.reflect.InvocationTargetException

/** Fixed, versioned read-only messages. Any other stdout (including PTY echo) is ignored. */
internal object BydLinkProtocol {
    const val READY = "SFXEV1 READY"
    const val INVALID = "SFXEV1 INVALID"
    const val ERROR = "SFXEV1 ERROR"
    const val STEP = "SFXEV1 STEP "
    const val DIAG = "SFXEV1 DIAG "

    fun clean(message: String): String = message.replace(Regex("[\\r\\n\\p{Cntrl}]+"), " ").take(480)

    fun describe(t: Throwable): String {
        val cause = if (t is InvocationTargetException) t.targetException ?: t else t
        return clean("${cause.javaClass.simpleName}: ${cause.message.orEmpty()}").take(180)
    }

    fun diagnostic(line: String): String? = when {
        line.startsWith(STEP) -> "ขั้นตอน: " + clean(line.removePrefix(STEP))
        line.startsWith(DIAG) -> clean(line.removePrefix(DIAG))
        else -> null
    }

    fun errorDetail(line: String): String? = when {
        line == ERROR -> "BYD SDK read unavailable"
        line.startsWith("$ERROR ") -> clean(line.removePrefix("$ERROR "))
        else -> null
    }

    data class Sample(val atNanos: Long, val speedKmh: Double, val pedal: Int)
    fun parse(line: String): Sample? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size != 5 || fields[0] != "SFXEV1" || fields[1] != "SAMPLE") return null
        val at = fields[2].toLongOrNull() ?: return null
        val speed = fields[3].toDoubleOrNull() ?: return null
        val pedal = fields[4].toIntOrNull() ?: return null
        if (at <= 0 || !speed.isFinite() || speed !in 0.0..250.0 || pedal !in 0..100) return null
        return Sample(at, speed, pedal)
    }
}
