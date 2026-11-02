package dev.custom.tvafrtools.server

import org.junit.Assert.*
import org.junit.Test

class VerifiedInputRateTest {
    private val rate = VerifiedInputRate(100, 1, setOf("decoder-1"), 24f, 1000, 96, 4000000)

    @Test fun authorityExpiresOnAppCodecGenerationOrLifecycleChanges() {
        assertTrue(rate.isCurrent(100, 1, setOf("decoder-1"), 1001))
        assertFalse(rate.isCurrent(200, 1, setOf("decoder-1"), 1001))
        assertFalse(rate.isCurrent(100, 2, setOf("decoder-1"), 1001))
        assertFalse(rate.isCurrent(100, 1, setOf("decoder-2"), 1001))
        assertFalse(rate.isCurrent(100, 1, emptySet(), 1001))
    }

    @Test fun failedCaptureAndClockRollbackCannotKeepAuthorityIndefinitely() {
        assertTrue(rate.isCurrent(100, 1, rate.streams, 16999))
        assertFalse(rate.isCurrent(100, 1, rate.streams, 17000))
        assertFalse(rate.isCurrent(100, 1, rate.streams, 999))
    }

    @Test fun inputTraceCanVerifyWithoutNominalDecoderLogMetadata() {
        val noMetadata = rate.copy(streams = emptySet())
        assertTrue(noMetadata.isCurrent(100, 1, emptySet(), 1001))
        assertFalse(noMetadata.isCurrent(100, 2, emptySet(), 1001))
    }
}
