package dev.custom.tvafrtools.afr

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRatesTest {
    @Test fun exactAndFractionalMeasurementsRemainDistinctRegardlessOfPreference() {
        for (rate in listOf(24f, 24000f / 1001f, 30f, 30000f / 1001f, 60f, 60000f / 1001f)) {
            for (preference in listOf(false, true)) {
                assertEquals(rate, FrameRates.target(rate, false, preference), 0.0001f)
            }
        }
    }

    @Test fun microsecondPtsRoundingDoesNotTurn24Into23976() {
        assertEquals(24f, FrameRates.snapMeasured(1_000_000f / 41667f), 0.0001f)
        assertEquals(24000f / 1001f, FrameRates.snapMeasured(1_000_000f / 41708f), 0.0001f)
        assertEquals(30f, FrameRates.snapMeasured(1_000_000f / 33333f), 0.0001f)
        assertEquals(30000f / 1001f, FrameRates.snapMeasured(1_000_000f / 33367f), 0.0001f)
        assertEquals(60f, FrameRates.snapMeasured(1_000_000f / 16667f), 0.0001f)
        assertEquals(60000f / 1001f, FrameRates.snapMeasured(1_000_000f / 16683f), 0.0001f)
    }

    @Test fun coarseCountersReportOnlyTheRateFamily() {
        for (raw in listOf(23.8f, 23.976f, 24f, 24.2f)) {
            assertEquals(24f, FrameRates.estimateFamily(raw), 0f)
        }
        assertEquals(30f, FrameRates.estimateFamily(29.97f), 0f)
        assertEquals(60f, FrameRates.estimateFamily(59.94f), 0f)
    }

    @Test fun fractionalPreferenceOnlyAffectsEstimates() {
        for (rate in listOf(24f, 30f, 60f)) {
            assertEquals(rate, FrameRates.target(rate, true, false), 0.0001f)
            assertEquals(rate * 1000f / 1001f, FrameRates.target(rate, true, true), 0.0001f)
        }
        for (rate in listOf(25f, 50f)) {
            assertEquals(rate, FrameRates.target(rate, true, true), 0f)
        }
    }

    @Test fun unsupportedAndInvalidMeasurementsDoNotBecomeCinemaRates() {
        assertEquals(0f, FrameRates.snapMeasured(Float.NaN), 0f)
        assertEquals(0f, FrameRates.snapMeasured(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, FrameRates.snapMeasured(-24f), 0f)
        assertEquals(0f, FrameRates.estimateFamily(0f), 0f)
        assertEquals(0f, FrameRates.estimateFamily(120f), 0f)
        assertEquals(120f, FrameRates.snapMeasured(120f), 0f)
    }
}
