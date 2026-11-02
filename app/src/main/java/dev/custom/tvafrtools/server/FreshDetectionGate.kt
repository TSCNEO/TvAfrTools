package dev.custom.tvafrtools.server

/** Captures begun before a reset cannot complete or publish the new request. */
internal class FreshDetectionGate {
    var requestId: Long = 0; private set
    var completedId: Long = 0; private set
    val pending: Boolean get() = completedId < requestId
    fun reset(): Long { requestId++; return requestId }
    fun complete(captureId: Long): Boolean {
        if (captureId != requestId) return false
        completedId = captureId
        return true
    }
}
