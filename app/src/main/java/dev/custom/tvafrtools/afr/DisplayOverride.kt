package dev.custom.tvafrtools.afr

/** Invalidates asynchronous work whenever the user changes display control. */
internal class DisplayOverride {
    @Volatile var manual = false
        private set
    @Volatile var generation = 0L
        private set

    fun select(manual: Boolean): Long {
        this.manual = manual
        generation += 1
        return generation
    }

    fun accepts(request: Long) = request == generation
}
