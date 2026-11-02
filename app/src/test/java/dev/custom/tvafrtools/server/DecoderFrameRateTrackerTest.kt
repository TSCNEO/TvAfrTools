package dev.custom.tvafrtools.server

import org.junit.Assert.*
import org.junit.Test

class DecoderFrameRateTrackerTest {
    private fun line(at: Double, pid: Int, tid: Int, tag: String, text: String) = "$at $pid $tid I $tag : $text"
    private fun bind(tracker: DecoderFrameRateTracker, pid: Int = 100, at: Double = 1000.0,
                     codec: String = "c2.amlogic.hevc.decoder.secure", instance: Int = 7, worker: Int = 201) {
        tracker.accept(line(at, pid, 101, "CCodec", "allocate($codec)"), pid, at)
        tracker.accept(line(at + .01, 200, 200, "C2VdecComponent", "[$instance##-1] Create C2VdecComponent($codec)"), pid, at + .01)
        tracker.accept(line(at + .02, 200, worker, "C2VdecComponent", "[$instance##-1] OnStart DolbyVision:0"), pid, at + .02)
    }
    private fun report(rate: String, stream: Int = 1, at: Double = 1000.1, actual: Boolean = true, worker: Int = 201) =
        line(at, 200, worker, "VDA", if (actual) "[0##$stream] reportFrameRate set [4000] framerate[$rate]"
            else "[0##$stream] postAndReplyMsg set framerate [$rate] afr_force_on:1")
    private fun heartbeat(stream: Int = 1, count: Int = 50, at: Double = 1010.0) =
        line(at, 200, 202, "VDA", "[0##$stream] ServiceDeviceTask INs=50/60, OUTs=40/$count")

    @Test fun measuredIntegerAndFractionalRatesRemainDistinctWithoutMatchingClientFps() {
        for (rate in listOf("24.000000", "23.976025", "29.970030", "30.000000", "59.940060", "60.000000")) {
            val tracker = DecoderFrameRateTracker()
            bind(tracker)
            assertEquals(rate.toFloat(), tracker.accept(report(rate), 100, 1000.1)!!, .001f)
        }
    }

    @Test fun jellyfinDefault30ThenActual23976NeverPublishes30() {
        val tracker = DecoderFrameRateTracker()
        bind(tracker, codec = "c2.amlogic.dolby-vision.dav1.decoder")
        assertNull(tracker.accept(report("30", actual = false), 100, 1000.1))
        tracker.accept(line(1000.15, 200, 202, "VDA", "[0##1] DestroyOutputBuffers:5132"), 100, 1000.15)
        assertEquals(23.976025f, tracker.accept(report("23.976025", at = 1000.2, worker = 202), 100, 1000.2)!!, .001f)
        assertEquals(23.976025f, tracker.accept(heartbeat(), 100, 1010.0)!!, .001f)
    }

    @Test fun primeBufferReallocationPauseAndCounterResetRetainMeasuredRate() {
        val tracker = DecoderFrameRateTracker()
        bind(tracker)
        tracker.accept(report("24"), 100, 1000.1)
        tracker.accept(heartbeat(), 100, 1010.0)
        tracker.accept(line(1011.0, 200, 202, "VDA", "[0##1] DestroyOutputBuffers:5132"), 100, 1011.0)
        assertEquals(24f, tracker.accept(heartbeat(count = 50, at = 1012.0), 100, 1012.0)!!, 0f)
        assertEquals(24f, tracker.accept(heartbeat(count = 1, at = 1013.0), 100, 1013.0)!!, 0f)
    }

    @Test fun netflixDeclaredRateNeedsAdvancingFramesBeforeItIsPublished() {
        val tracker = DecoderFrameRateTracker()
        bind(tracker, codec = "c2.amlogic.av1.decoder.secure")
        assertNull(tracker.accept(report("23.976025", actual = false), 100, 1000.1))
        assertNull(tracker.accept(heartbeat(count = 1, at = 1001.0), 100, 1001.0))
        assertEquals(23.976025f, tracker.accept(heartbeat(count = 97, at = 1005.0), 100, 1005.0)!!, .001f)
    }

    @Test fun incorrectDefaultCannotBeConfirmedByFramesFromAnotherFamily() {
        val tracker = DecoderFrameRateTracker()
        bind(tracker)
        tracker.accept(report("30", actual = false), 100, 1000.1)
        tracker.accept(heartbeat(count = 1, at = 1001.0), 100, 1001.0)
        assertNull(tracker.accept(heartbeat(count = 97, at = 1005.0), 100, 1005.0))
        assertEquals(24f, tracker.accept(report("24", at = 1005.1), 100, 1005.1)!!, 0f)
    }

    @Test fun staleHistoryCanBindButNeedsFreshActivityToPublish() {
        val tracker = DecoderFrameRateTracker()
        bind(tracker)
        assertNull(tracker.accept(report("23.976025"), 100, 1010.0))
        assertNull(tracker.accept(heartbeat(stream = 2), 100, 1010.0))
        assertEquals(23.976025f, tracker.accept(heartbeat(), 100, 1010.0)!!, .001f)
        assertNull(tracker.accept(heartbeat(count = 80), 100, 1020.0))
    }

    @Test fun anotherAppCannotSupplyForegroundRate() {
        val tracker = DecoderFrameRateTracker()
        bind(tracker)
        assertNull(tracker.accept(report("24"), 300, 1000.1))
        assertNull(tracker.accept(heartbeat(), 300, 1010.0))
    }

    @Test fun ambiguousConcurrentCodecAllocationsRemainUnattributed() {
        val tracker = DecoderFrameRateTracker()
        val codec = "c2.amlogic.hevc.decoder.secure"
        tracker.accept(line(1000.0, 100, 101, "CCodec", "allocate($codec)"), 100, 1000.0)
        tracker.accept(line(1000.0, 300, 301, "CCodec", "allocate($codec)"), 100, 1000.0)
        tracker.accept(line(1000.01, 200, 200, "C2VdecComponent", "[7##-1] Create C2VdecComponent($codec)"), 100, 1000.01)
        tracker.accept(line(1000.02, 200, 201, "C2VdecComponent", "[7##-1] OnStart DolbyVision:0"), 100, 1000.02)
        assertNull(tracker.accept(report("24"), 100, 1000.1))
    }

    @Test fun realStreamDestructionAndClientReleaseInvalidateRates() {
        for (close in listOf(line(1011.0, 200, 202, "VDA", "[0##1] DestroyTask INS=0/0"),
            line(1011.0, 100, 101, "CCodec", "release()"))) {
            val tracker = DecoderFrameRateTracker()
            bind(tracker)
            tracker.accept(report("24"), 100, 1000.1)
            tracker.accept(close, 100, 1011.0)
            assertNull(tracker.accept(heartbeat(at = 1012.0), 100, 1012.0))
        }
    }

    @Test fun differentStreamAndMissingAllocationDoNotReuseFps() {
        val tracker = DecoderFrameRateTracker()
        assertNull(tracker.accept(report("24"), 100, 1000.1))
        bind(tracker)
        tracker.accept(report("24"), 100, 1000.1)
        assertNull(tracker.accept(heartbeat(stream = 2), 100, 1010.0))
    }
}
