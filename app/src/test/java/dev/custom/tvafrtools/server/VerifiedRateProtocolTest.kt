package dev.custom.tvafrtools.server

import org.junit.Assert.*
import org.junit.Test

class VerifiedRateProtocolTest {
    @Test fun legacyDecoder24AndEstimatedCountersCannotAuthorizeAutomaticAfr() {
        for (source in listOf("TUNNEL_DECODER", "TUNNEL_VDA_ESTIMATED", "TUNNEL_PTS", "SURFACE_FLINGER", "SYSFS:x")) {
            assertFalse(ServerClient.parseSysInfo("OK FPS=24.000 SRC=$source TEMP=60 HDMI=N/D").fpsVerified)
            assertFalse(ServerClient.parseSysInfo("OK FPS=24.000 SRC=$source VERIFIED=1 SAMPLES=96 SPAN_US=4000000").fpsVerified)
        }
    }

    @Test fun verifiedInputPtsRequireEnoughSamplesAndDuration() {
        assertTrue(ServerClient.parseSysInfo("OK FPS=23.976 SRC=VIDEO_INPUT_PTS VERIFIED=1 SAMPLES=96 SPAN_US=4000000").fpsVerified)
        for (suffix in listOf("", "VERIFIED=1", "VERIFIED=1 SAMPLES=17 SPAN_US=700000", "VERIFIED=0 SAMPLES=96 SPAN_US=4000000")) {
            assertFalse(ServerClient.parseSysInfo("OK FPS=24.000 SRC=VIDEO_INPUT_PTS $suffix").fpsVerified)
        }
    }

    @Test fun malformedAndOfflineMessagesAreNotAuthoritative() {
        for (response in listOf("", "ERR", "OK FPS=NaN SRC=VIDEO_INPUT_PTS VERIFIED=1 SAMPLES=96 SPAN_US=4000000"))
            assertFalse(ServerClient.parseSysInfo(response).fpsVerified)
    }
}
