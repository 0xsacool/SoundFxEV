package com.sacool.soundfxev

import org.junit.Assert.*
import org.junit.Test

class BydLinkProtocolTest {
    @Test fun acceptsOnlyValidReadings() {
        val sample = BydLinkProtocol.parse("SFXEV1 SAMPLE 1230000000 63.5 46")!!
        assertEquals(1230000000L, sample.atNanos)
        assertEquals(63.5, sample.speedKmh, 0.001)
        assertEquals(46, sample.pedal)
        assertNotNull(BydLinkProtocol.parse("SFXEV1 SAMPLE 3 0.0 0\r"))
        for (bad in listOf("R", "SFXEV1 READY", "SFXEV1 INVALID", "SFXEV1 SAMPLE 5 10 101",
            "SFXEV1 SAMPLE 5 NaN 50", "SFXEV1 SAMPLE 5 -1 50", "SFXEV1 SAMPLE 0 20 50",
            "SFXEV1 SAMPLE 5 20 -1", "SFXEV1 SAMPLE 5 251 50", "SFXEV1 SAMPLE 5 20 50 extra"))
            assertNull(bad, BydLinkProtocol.parse(bad))
    }
}
