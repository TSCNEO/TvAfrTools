package dev.custom.tvafrtools.afr

import kotlin.math.abs
import kotlin.math.roundToLong

/** Fits source media timestamps to a CFR model; never uses wall time or HDMI presentation time. */
internal object PresentationRateEstimator {
    enum class Status { INSUFFICIENT, AMBIGUOUS, VERIFIED, VARIABLE }
    data class Evidence(
        val status: Status, val fps: Float? = null, val samples: Int = 0,
        val spanUs: Long = 0, val quantizationUs: Long = 1, val candidates: List<Float> = emptyList()
    )
    private data class Rate(val numerator: Long, val denominator: Long = 1) {
        val fps: Float get() = numerator.toFloat() / denominator
        val periodUs: Double get() = 1_000_000.0 * denominator / numerator
    }
    private val rates = listOf(Rate(24000, 1001), Rate(24), Rate(25), Rate(30000, 1001),
        Rate(30), Rate(50), Rate(60000, 1001), Rate(60))
    private const val MIN_SAMPLES = 40
    private const val MIN_SPAN_US = 3_000_000L

    fun estimate(timestampsUs: Collection<Long>): Evidence {
        val sorted = timestampsUs.asSequence().filter { it >= 0 }.distinct().sorted().toList()
        // Codec configuration/EOS timestamps and ExoPlayer's media offset must not share a fit.
        val clusters = mutableListOf<MutableList<Long>>()
        for (pts in sorted) {
            if (clusters.isEmpty() || pts - clusters.last().last() > 2_000_000L) clusters.add(mutableListOf())
            clusters.last().add(pts)
        }
        val pts = clusters.maxWithOrNull(compareBy<List<Long>> { it.size }.thenBy { it.last() })
            ?.takeLast(4096).orEmpty()
        if (pts.size < 2) return Evidence(Status.INSUFFICIENT, samples = pts.size)
        val span = pts.last() - pts.first()
        var quantum = 0L
        for (i in 1 until pts.size) quantum = gcd(quantum, pts[i] - pts[i - 1])
        quantum = quantum.coerceIn(1L, 1000L)
        if (pts.size < MIN_SAMPLES || span < MIN_SPAN_US)
            return Evidence(Status.INSUFFICIENT, samples = pts.size, spanUs = span, quantizationUs = quantum)
        val matches = rates.filter { fits(pts, it.periodUs, quantum) }.map { it.fps }
        val status = when (matches.size) { 0 -> Status.VARIABLE; 1 -> Status.VERIFIED; else -> Status.AMBIGUOUS }
        return Evidence(status, matches.singleOrNull(), pts.size, span, quantum, matches)
    }

    private fun fits(pts: List<Long>, period: Double, quantum: Long): Boolean {
        var frame = 0L
        var minimum = 0.0
        var maximum = 0.0
        var adjacent = 0
        for (i in 1 until pts.size) {
            val gap = pts[i] - pts[i - 1]
            val steps = (gap / period).roundToLong()
            if (steps < 1 || gap > 1_000_000L) return false
            if (steps == 1L) adjacent++
            frame += steps
            val residual = (pts[i] - pts.first()).toDouble() - frame * period
            minimum = minOf(minimum, residual)
            maximum = maxOf(maximum, residual)
            if (maximum - minimum > quantum + 2.0) return false
        }
        // Prevent a sparse stream fitting a multiple of its actual cadence.
        return adjacent.toDouble() / (pts.size - 1) >= 0.8
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = abs(a); var y = abs(b)
        while (y != 0L) { val remainder = x % y; x = y; y = remainder }
        return x
    }
}
