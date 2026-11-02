package dev.custom.tvafrtools.server

import dev.custom.tvafrtools.afr.FrameRates
import kotlin.math.abs

/** Links app codec allocation -> vendor component -> worker -> stream. No fps equality is required. */
internal class DecoderFrameRateTracker {
    private data class Owner(val pid: Int, val thread: String)
    private data class Client(val owner: Owner, val codec: String, val at: Double)
    private data class Stream(
        val owner: Owner, val fps: Float? = null, val confirmed: Boolean = false,
        val baselineCount: Long? = null, val baselineAt: Double = 0.0
    )
    private val clients = linkedMapOf<String, Client>()
    private val components = linkedMapOf<String, Owner>()
    private val workers = linkedMapOf<String, Owner>()
    private val streams = linkedMapOf<String, Stream>()
    private val header = Regex("""^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+\w\s+([^: ]+)\s*:\s*(.*)$""")
    private val allocate = Regex("""allocate\(([^)]+)\)""")
    private val create = Regex("""Create C2VdecComponent\(([^)]+)\)""")
    private val componentId = Regex("""\[(\d+)##-1\]""")
    private val streamId = Regex("""\[([^]]+##\d+)\]""")
    private val vendorRate = Regex("""(?:reportFrameRate set \[\d+\] framerate|postAndReplyMsg set framerate)\s*\[(\d+(?:\.\d+)?)\]""")
    private val counter = Regex("""OUTs=\d+/(\d+)""")
    private val videoCodec = Regex("""(?i)(?:avc|hevc|av1|vp9|mpeg|dolby|video)""")
    private val closeStream = Regex("""(?:\bDestroyTask\b|\[stop\]|\[release\])""")

    private fun invalidate(owner: Owner) {
        clients.entries.removeAll { it.value.owner == owner }
        components.entries.removeAll { it.value == owner }
        workers.entries.removeAll { it.value == owner }
        streams.entries.removeAll { it.value.owner == owner }
    }

    @Synchronized
    fun activeStreamKeys(pid: Int): Set<String> = streams.filterValues { it.owner.pid == pid }
        .map { (key, stream) -> "$key:${stream.owner.thread}:${stream.fps}" }.toSet()

    @Synchronized
    fun accept(line: String, foregroundPid: Int, nowEpochSeconds: Double): Float? {
        val m = header.find(line) ?: return null
        val at = m.groupValues[1].toDoubleOrNull() ?: return null
        val pid = m.groupValues[2].toIntOrNull() ?: return null
        val tid = m.groupValues[3]
        val tag = m.groupValues[4]
        val message = m.groupValues[5]
        val clientKey = "$pid:$tid"
        if (tag == "CCodec") {
            val codec = allocate.find(message)?.groupValues?.get(1)
            if (codec != null && videoCodec.containsMatchIn(codec)) {
                val owner = Owner(pid, tid)
                invalidate(owner)
                clients[clientKey] = Client(owner, codec, at)
            } else if (Regex("""(?i)(?:release\(|shutdown\()""").containsMatchIn(message)) {
                invalidate(Owner(pid, tid))
            }
        }
        if (tag == "C2VdecComponent") {
            val instance = componentId.find(message)?.groupValues?.get(1)
            val codec = create.find(message)?.groupValues?.get(1)
            if (instance != null && codec != null) {
                val candidates = clients.values.filter { it.codec == codec && at - it.at in 0.0..2.0 }
                if (candidates.size == 1) components["$pid:$instance"] = candidates.single().owner
            }
            if (instance != null && message.contains("OnStart")) {
                components["$pid:$instance"]?.let { workers[clientKey] = it }
            }
        }
        // Keep memory bounded even when old codecs do not emit release logs.
        clients.entries.removeAll { at - it.value.at > 10.0 }
        for (map in listOf(components, workers)) while (map.size > 64) map.remove(map.keys.first())
        if (tag != "VDA") return null
        val id = streamId.find(message)?.groupValues?.get(1) ?: return null
        val key = "$pid:$id"
        if (closeStream.containsMatchIn(message)) {
            streams.remove(key)
            return null
        }
        // DestroyOutputBuffers / resolution changes and seeks do not close the decoder.
        if (key !in streams) workers[clientKey]?.let { streams[key] = Stream(it) }
        while (streams.size > 32) streams.remove(streams.keys.first())
        var stream = streams[key] ?: return null
        val reported = vendorRate.find(message)?.groupValues?.get(1)?.toFloatOrNull()
        if (reported != null && reported in 10f..120f) {
            val actual = message.contains("reportFrameRate")
            if (actual || !stream.confirmed) {
                stream = stream.copy(fps = FrameRates.snapMeasured(reported), confirmed = actual,
                    baselineCount = null, baselineAt = at)
            }
        }
        if (message.contains("ServiceDeviceTask")) {
            val count = counter.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            if (!stream.confirmed) {
                val baseline = stream.baselineCount
                val declared = stream.fps
                if (baseline == null || count < baseline) {
                    stream = stream.copy(baselineCount = count, baselineAt = at)
                } else if (at - stream.baselineAt >= 3.0 && declared != null) {
                    val measured = ((count - baseline) / (at - stream.baselineAt)).toFloat()
                    // Confirm only the rate family; the declared rate supplies fractional precision.
                    if (abs(FrameRates.estimateFamily(measured) - FrameRates.estimateFamily(declared)) < 0.005f &&
                        FrameRates.estimateFamily(measured) > 0f) stream = stream.copy(confirmed = true)
                }
            }
        } else if (reported == null) return null
        streams[key] = stream
        return stream.fps?.takeIf { stream.confirmed && stream.owner.pid == foregroundPid &&
            nowEpochSeconds - at in 0.0..2.0 }
    }
}
