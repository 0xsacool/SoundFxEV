package com.sacool.soundfxev

import org.junit.Assert.*
import org.junit.Test

class BydLinkProtocolTest {
    @Test fun diagnosticsReportStageAndUnwrapActualException() {
        val cause = java.lang.reflect.InvocationTargetException(SecurityException("permission denied"))
        assertEquals("SecurityException: permission denied", BydLinkProtocol.describe(cause))
        assertEquals("ขั้นตอน: CONTEXT_SYSTEM_MAIN", BydLinkProtocol.diagnostic("SFXEV1 STEP CONTEXT_SYSTEM_MAIN"))
        assertEquals("SPEED: OK 0.0 | PEDAL: FAIL NoSuchMethodException",
            BydLinkProtocol.diagnostic("SFXEV1 DIAG SPEED: OK 0.0 | PEDAL: FAIL NoSuchMethodException"))
        assertEquals("DEVICE_INSTANCE: SecurityException: refused",
            BydLinkProtocol.errorDetail("SFXEV1 ERROR DEVICE_INSTANCE: SecurityException: refused"))
        assertNull(BydLinkProtocol.parse("SFXEV1 DIAG SPEED: OK 0.0 | PEDAL: FAIL"))
        assertNull(BydLinkProtocol.diagnostic("R"))
    }

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
