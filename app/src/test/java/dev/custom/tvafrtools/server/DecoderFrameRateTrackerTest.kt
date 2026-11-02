package dev.custom.tvafrtools.server

import org.junit.Assert.*
import org.junit.Test

class DecoderFrameRateTrackerTest {
    private fun client(rate: String, pid: Int = 100, tid: Int = 101, at: String = "1000.000") =
        "$at $pid $tid D CCodecConfig: c2 config diff is   c2::float coded.frame-rate.value = $rate"
    private fun report(rate: String, stream: Int = 1, at: String = "1000.100") =
        "$at 200 201 I VDA : [0##$stream]\"postAndReplyMsg set framerate [$rate] afr_force_on:1\""
    private fun heartbeat(stream: Int = 1, count: Int = 50, at: String = "1010.000") =
        "$at 200 201 I VDA : [0##$stream]\"ServiceDeviceTask INs=50/60, OUTs=40/$count\""

    @Test fun netflixIntegerAndFractionalRatesRemainDistinct() {
        for (rate in listOf("24.000000", "23.976025", "29.970030", "30.000000", "59.940060", "60.000000")) {
            val tracker = DecoderFrameRateTracker()
            assertNull(tracker.accept(client(rate), 100, 1000.0))
            assertEquals(rate.toFloat(), tracker.accept(report(rate), 100, 1000.1)!!, 0.001f)
        }
    }

    @Test fun reportFrameRateFormatAndHeartbeatKeepDisneyRateAlive() {
        val tracker = DecoderFrameRateTracker()
        tracker.accept(client("24"), 100, 1000.0)
        val line = "1000.100 200 201 I VDA : [0##1]\"reportFrameRate set [4000] framerate[24.000000]\""
        assertEquals(24f, tracker.accept(line, 100, 1000.1)!!, 0f)
        assertEquals(24f, tracker.accept(heartbeat(), 100, 1010.0)!!, 0f)
        assertEquals(24f, tracker.accept(heartbeat(count = 74, at = "1011.000"), 100, 1011.0)!!, 0f)
        assertNull(tracker.accept(heartbeat(count = 74, at = "1012.000"), 100, 1012.0))
    }

    @Test fun staleReportsCanBindButCannotPublishWithoutFreshMatchingActivity() {
        val tracker = DecoderFrameRateTracker()
        tracker.accept(client("23.976"), 100, 1010.0)
        assertNull(tracker.accept(report("23.976025"), 100, 1010.0))
        assertNull(tracker.accept(heartbeat(stream = 2), 100, 1010.0))
        assertEquals(23.976025f, tracker.accept(heartbeat(), 100, 1010.0)!!, 0.001f)
        assertNull(tracker.accept(heartbeat(count = 80), 100, 1020.0))
    }

    @Test fun anotherProcessOrAmbiguousOwnershipCannotSupplyForegroundFps() {
        val tracker = DecoderFrameRateTracker()
        tracker.accept(client("24"), 100, 1000.0)
        assertNull(tracker.accept(report("24"), 300, 1000.1))
        assertNull(tracker.accept(heartbeat(), 300, 1010.0))
        val ambiguous = DecoderFrameRateTracker()
        ambiguous.accept(client("24"), 100, 1000.0)
        ambiguous.accept(client("24", pid = 300), 100, 1000.0)
        assertNull(ambiguous.accept(report("24"), 100, 1000.1))
    }

    @Test fun codecDefault30IsIgnoredAndOldStreamCannotSupplyNewVideo() {
        val tracker = DecoderFrameRateTracker()
        assertNull(tracker.accept("1000.000 100 101 D CCodecConfig:   c2::float coded.frame-rate.value = 30", 100, 1000.0))
        assertNull(tracker.accept(report("30"), 100, 1000.1))
        tracker.accept(client("24"), 100, 1000.0)
        tracker.accept(report("24"), 100, 1000.1)
        assertNull(tracker.accept(heartbeat(stream = 2), 100, 1010.0))
        tracker.accept("1010.000 200 201 I VDA : [0##1]\"DestroyTask\"", 100, 1010.0)
        assertNull(tracker.accept(heartbeat(count = 90, at = "1011.000"), 100, 1011.0))
    }

    @Test fun reportWithoutClientMetadataRemainsUnattributed() {
        val tracker = DecoderFrameRateTracker()
        assertNull(tracker.accept(report("23.976025"), 100, 1000.1))
        assertNull(tracker.accept(heartbeat(), 100, 1010.0))
    }

    @Test fun counterResetAndClientReleaseInvalidateCachedRates() {
        val tracker = DecoderFrameRateTracker()
        tracker.accept(client("24"), 100, 1000.0)
        tracker.accept(report("24"), 100, 1000.1)
        tracker.accept(heartbeat(), 100, 1010.0)
        assertNull(tracker.accept(heartbeat(count = 1, at = "1011.000"), 100, 1011.0))
        assertNull(tracker.accept(heartbeat(count = 90, at = "1012.000"), 100, 1012.0))
        tracker.accept(client("24", at = "1020.000"), 100, 1020.0)
        tracker.accept(report("24", at = "1020.100"), 100, 1020.1)
        tracker.accept("1021.000 100 101 D CCodec: release()", 100, 1021.0)
        assertNull(tracker.accept(heartbeat(count = 100, at = "1022.000"), 100, 1022.0))
    }
}
