package dev.custom.tvafrtools.afr

import kotlin.math.abs

/** Rate classification is independent of user preferences and display mode selection. */
internal object FrameRates {
    private val standards = floatArrayOf(24000f / 1001f, 24f, 25f, 30000f / 1001f, 30f, 50f, 60000f / 1001f, 60f)

    fun snapMeasured(fps: Float): Float {
        if (!fps.isFinite() || fps <= 0f) return 0f
        val nearest = standards.minByOrNull { abs(it - fps) } ?: return fps
        return if (abs(nearest - fps) <= 0.6f) nearest else fps
    }

    // Short wall-clock counters cannot resolve the 0.1% difference. Keep only the family.
    fun estimateFamily(fps: Float): Float = when (fps) {
        in 22.8f..24.35f -> 24f
        in 24.45f..25.8f -> 25f
        in 29.2f..30.6f -> 30f
        in 49f..51f -> 50f
        in 58.5f..61f -> 60f
        else -> 0f
    }

}
