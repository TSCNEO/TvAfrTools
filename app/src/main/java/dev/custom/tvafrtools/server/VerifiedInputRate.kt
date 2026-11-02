package dev.custom.tvafrtools.server

/** Authority belongs to a process/codec generation and expires when fresh tracing stops. */
internal data class VerifiedInputRate(
    val pid: Int, val generation: Long, val streams: Set<String>, val fps: Float,
    val capturedAtMs: Long, val samples: Int, val spanUs: Long
) {
    fun isCurrent(foregroundPid: Int, currentGeneration: Long, currentStreams: Set<String>, nowMs: Long): Boolean =
        pid == foregroundPid && generation == currentGeneration && nowMs - capturedAtMs in 0L until 16_000L &&
            (streams.isEmpty() || streams == currentStreams)
}
