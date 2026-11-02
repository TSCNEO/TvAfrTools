package dev.custom.tvafrtools.server

import dev.custom.tvafrtools.afr.FrameRates
import kotlin.math.abs

/** Associates vendor rates with a client process and decoder stream, never with HDMI Hz. */
internal class DecoderFrameRateTracker {
    private data class Client(val pid: Int, val fps: Float, val at: Double)
    private data class Stream(val owner: Int, val fps: Float, val at: Double, val count: Long? = null)
    private val clients = mutableMapOf<String, Client>()
    private val streams = linkedMapOf<String, Stream>()
    private val header = Regex("""^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+\w\s+([^: ]+)\s*:\s*(.*)$""")
    private val clientRate = Regex("""c2 config diff is\s+c2::float coded\.frame-rate\.value\s*=\s*(\d+(?:\.\d+)?)""")
    private val streamId = Regex("""\[([^]]+##\d+)\]""")
    private val vendorRate = Regex("""(?:reportFrameRate set \[\d+\] framerate|postAndReplyMsg set framerate)\s*\[(\d+(?:\.\d+)?)\]""")
    private val counter = Regex("""OUTs=\d+/(\d+)""")

    @Synchronized
    fun accept(line: String, foregroundPid: Int, nowEpochSeconds: Double): Float? {
        val m = header.find(line) ?: return null
        val at = m.groupValues[1].toDoubleOrNull() ?: return null
        val pid = m.groupValues[2].toIntOrNull() ?: return null
        val tid = m.groupValues[3]
        val tag = m.groupValues[4]
        val message = m.groupValues[5]
        val clientKey = "$pid:$tid"
        if (tag == "CCodecConfig") {
            val fps = clientRate.find(message)?.groupValues?.get(1)?.toFloatOrNull()
            if (fps != null && fps in 10f..120f) clients[clientKey] = Client(pid, fps, at)
        }
        // A released/reconfigured client must not retain ownership of an old decoder.
        if (tag == "CCodec" && Regex("""(?i)(?:allocate\(|release\(|shutdown\()""").containsMatchIn(message)) {
            val knownVideoClient = clients.remove(clientKey) != null
            val newVideoCodec = Regex("""(?i)allocate\([^)]*(?:avc|hevc|av1|vp9|mpeg|dolby|video)""").containsMatchIn(message)
            if (knownVideoClient || newVideoCodec) streams.entries.removeAll { it.value.owner == pid }
        }
        clients.entries.removeAll { at - it.value.at > 10.0 }
        if (tag != "VDA") return null
        val id = streamId.find(message)?.groupValues?.get(1) ?: return null
        val key = "$pid:$id"
        if (message.contains("Destroy") || message.contains("[stop]") || message.contains("[release]")) {
            streams.remove(key)
            return null
        }
        val reported = vendorRate.find(message)?.groupValues?.get(1)?.toFloatOrNull()
        if (reported != null && reported in 10f..120f) {
            val owners = clients.values.filter { at - it.at in 0.0..2.0 && abs(it.fps - reported) < 0.005f }
                .map { it.pid }.distinct()
            if (owners.size == 1) {
                streams[key] = Stream(owners.single(), FrameRates.snapMeasured(reported), at)
                while (streams.size > 32) streams.remove(streams.keys.first())
            } else {
                // A later rate report can update an already bound stream, but cannot invent an owner.
                streams[key]?.let { streams[key] = it.copy(fps = FrameRates.snapMeasured(reported), at = at) }
            }
        }
        val stream = streams[key] ?: return null
        if (message.contains("ServiceDeviceTask")) {
            val count = counter.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            if (stream.count != null && count < stream.count) {
                streams.remove(key)
                return null
            }
            // Refresh only while frames advance; paused/stalled streams expire naturally.
            if (stream.count != null && count <= stream.count) return null
            streams[key] = stream.copy(at = at, count = count)
        } else if (reported == null) return null
        val active = streams[key] ?: return null
        return active.fps.takeIf { active.owner == foregroundPid && nowEpochSeconds - at in 0.0..2.0 }
    }
}
