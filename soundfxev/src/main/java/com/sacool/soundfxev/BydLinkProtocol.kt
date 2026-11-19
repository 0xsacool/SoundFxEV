package com.sacool.soundfxev

/** Fixed, versioned read-only messages. Any other stdout (including PTY echo) is ignored. */
internal object BydLinkProtocol {
    const val READY = "SFXEV1 READY"
    const val INVALID = "SFXEV1 INVALID"
    const val ERROR = "SFXEV1 ERROR"
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
