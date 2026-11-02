package dev.custom.tvafrtools.server

import dev.custom.tvafrtools.afr.PresentationRateEstimator

/** Parses native CCodec input PTS from atrace text or Perfetto print payloads. */
internal object CodecInputTrace {
    data class Stream(val pid: Int, val codec: String)
    data class Reading(val stream: Stream, val evidence: PresentationRateEstimator.Evidence)
    private val event = Regex("""B\|(\d+)\|CCodecBufferChannel::queue\(([^@\r\n)]+)@ts=(-?\d+)\)""")
    private val video = Regex("""(?i)(?:avc|hevc|h264|h265|av1|vp8|vp9|mpeg|dolby|video)""")

    private val allocation = Regex("""^\s*\d+\.\d+\s+(\d+)\s+\d+\s+\w\s+CCodec\s*:\s*allocate\(([^)]+)\)""")

    fun isNewVideoCodecForPid(line: String, pid: Int): Boolean {
        val match = allocation.find(line) ?: return false
        return match.groupValues[1].toIntOrNull() == pid && video.containsMatchIn(match.groupValues[2]) &&
            match.groupValues[2].contains("decoder")
    }

    fun uniqueVerified(readings: List<Reading>): Reading? = readings.singleOrNull()?.takeIf {
        it.evidence.status == PresentationRateEstimator.Status.VERIFIED
    }

    fun read(bytes: ByteArray, foregroundPid: Int): List<Reading> = read(bytes.toString(Charsets.ISO_8859_1), foregroundPid)

    fun read(trace: String, foregroundPid: Int): List<Reading> {
        val samples = linkedMapOf<Stream, MutableList<Long>>()
        for (match in event.findAll(trace)) {
            val pid = match.groupValues[1].toIntOrNull() ?: continue
            val codec = match.groupValues[2]
            if (pid != foregroundPid || !video.containsMatchIn(codec) || !codec.contains("decoder")) continue
            val pts = match.groupValues[3].toLongOrNull() ?: continue
            val stream = Stream(pid, codec)
            val points = samples.getOrPut(stream) { mutableListOf() }
            if (points.size < 8192) points.add(pts)
        }
        return samples.map { (stream, pts) -> Reading(stream, PresentationRateEstimator.estimate(pts)) }
    }
}
