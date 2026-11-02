package dev.custom.tvafrtools.afr

import dev.custom.tvafrtools.server.CodecInputTrace
import org.junit.Assert.*
import org.junit.Test

class PresentationRateEstimatorTest {
    private fun timestamps(numerator: Long, denominator: Long = 1, quantum: Long = 1,
                           phase: Long = 0, count: Int = 260): List<Long> =
        (0 until count).map { i -> 1_000_000_000_000L + ((i * 1_000_000L * denominator / numerator + phase) / quantum) * quantum }

    @Test fun allSupportedRatesRemainDistinctUnderMillisecondQuantization() {
        for ((num, den) in listOf(24000L to 1001L, 24L to 1L, 25L to 1L, 30000L to 1001L,
            30L to 1L, 50L to 1L, 60000L to 1001L, 60L to 1L)) {
            for (phase in listOf(0L, 123L, 500L, 999L)) {
                val evidence = PresentationRateEstimator.estimate(timestamps(num, den, 1000, phase))
                assertEquals("$num/$den phase=$phase: $evidence", PresentationRateEstimator.Status.VERIFIED, evidence.status)
                assertEquals(num.toFloat() / den, evidence.fps!!, .0001f)
            }
        }
    }

    @Test fun shortQuantizedBurstDoesNotPretendToBeExact() {
        val evidence = PresentationRateEstimator.estimate(timestamps(24000, 1001, 1000, count = 17))
        assertEquals(PresentationRateEstimator.Status.INSUFFICIENT, evidence.status)
        assertNull(evidence.fps)
    }

    @Test fun bFrameReorderingAndDuplicateConfigurationEventsDoNotChangeRate() {
        val pts = timestamps(24000, 1001, 1000).chunked(4).flatMap { it.reversed() }
        val evidence = PresentationRateEstimator.estimate(listOf(0L, 0L) + pts + pts.take(10))
        assertEquals(PresentationRateEstimator.Status.VERIFIED, evidence.status)
        assertEquals(24000f / 1001f, evidence.fps!!, .0001f)
    }

    @Test fun droppedInputEventsDoNotTurnTheStreamIntoADifferentRate() {
        val pts = timestamps(24).filterIndexed { index, _ -> index % 20 != 0 }
        val evidence = PresentationRateEstimator.estimate(pts)
        assertEquals(PresentationRateEstimator.Status.VERIFIED, evidence.status)
        assertEquals(24f, evidence.fps!!, 0f)
    }

    @Test fun variableCadenceAndMixedRatesAreNotPublishedAsExact() {
        val pts = timestamps(24, count = 100) + timestamps(25, count = 150).map { it + 4_200_000L }
        assertNull(PresentationRateEstimator.estimate(pts).fps)
    }

    @Test fun capturedMp4AndMkvAreClassifiedWithoutHdmiOrDecoderReportedRates() {
        for ((file, expected) in listOf("24-mp4.trace" to 24f, "23976-mkv.trace" to 24000f / 1001f, "disney-protected-24.trace" to 24f, "jellyfin-declared24-actual23976.trace" to 24000f / 1001f)) {
            val trace = javaClass.getResource("/codec-pts/$file")!!.readText()
            val reading = CodecInputTrace.read(trace, 100).single()
            assertEquals(PresentationRateEstimator.Status.VERIFIED, reading.evidence.status)
            assertEquals(expected, reading.evidence.fps!!, .0001f)
            assertTrue(CodecInputTrace.read(trace, 200).isEmpty())
        }
    }

    @Test fun audioAndDifferentCodecInstancesAreNeverMergedWithVideo() {
        val video = javaClass.getResource("/codec-pts/24-mp4.trace")!!.readText()
        val audio = video.replace("avc.decoder", "aac.decoder")
        val another = video.replace("decoder#1", "decoder#2")
        assertEquals(2, CodecInputTrace.read(video + audio + another, 100).size)
    }

    @Test fun perfettoBinaryPrintPayloadsAreParsedWithoutTreatingOtherFieldsAsTimestamps() {
        val trace = javaClass.getResource("/codec-pts/23976-mkv.trace")!!.readText()
        val bytes = byteArrayOf(0, -1, 8, 10) + trace.toByteArray() + byteArrayOf(0, -2)
        assertEquals(24000f / 1001f, CodecInputTrace.read(bytes, 100).single().evidence.fps!!, .0001f)
    }
    @Test fun newCodecInvalidationUsesTheOwnerAndDoesNotTriggerOnAudio() {
        val line = "1000.123 100 101 D CCodec : allocate(c2.amlogic.hevc.decoder.secure)"
        assertTrue(CodecInputTrace.isNewVideoCodecForPid(line, 100))
        assertFalse(CodecInputTrace.isNewVideoCodecForPid(line, 200))
        assertFalse(CodecInputTrace.isNewVideoCodecForPid(line.replace("hevc", "aac"), 100))
    }

    @Test fun concurrentOrInsufficientCodecsCannotAuthorizeAnAutomaticSwitch() {
        val trace = javaClass.getResource("/codec-pts/24-mp4.trace")!!.readText()
        val readings = CodecInputTrace.read(trace + trace.replace("decoder#1", "decoder#2"), 100)
        assertNull(CodecInputTrace.uniqueVerified(readings))
        assertNotNull(CodecInputTrace.uniqueVerified(CodecInputTrace.read(trace, 100)))
        assertNull(CodecInputTrace.uniqueVerified(emptyList()))
    }

    @Test fun fourSecondBatchesOfTheActualJellyfinFailureAreSufficient() {
        val trace = javaClass.getResource("/codec-pts/jellyfin-declared24-actual23976.trace")!!.readText()
        val points = Regex("""@ts=(\d+)""").findAll(trace).map { it.groupValues[1].toLong() }.distinct().sorted().toList()
        for (window in points.windowed(96, 24)) {
            val reading = PresentationRateEstimator.estimate(window)
            assertEquals(PresentationRateEstimator.Status.VERIFIED, reading.status)
            assertEquals(24000f / 1001f, reading.fps!!, .0001f)
        }
    }

}
