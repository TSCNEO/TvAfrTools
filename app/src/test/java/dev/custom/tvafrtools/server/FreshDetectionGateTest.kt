package dev.custom.tvafrtools.server

import org.junit.Assert.*
import org.junit.Test

class FreshDetectionGateTest {
    @Test fun resetRejectsAnAlreadyRunningCapture() {
        val gate = FreshDetectionGate()
        val oldCapture = gate.requestId
        val requested = gate.reset()
        assertTrue(gate.pending)
        assertFalse(gate.complete(oldCapture))
        assertTrue(gate.pending)
        assertTrue(gate.complete(requested))
        assertFalse(gate.pending)
    }
    @Test fun overlappingRequestsOnlyAcceptTheLatestFullCapture() {
        val gate = FreshDetectionGate()
        val first = gate.reset()
        val second = gate.reset()
        assertFalse(gate.complete(first))
        assertNotEquals(second, gate.completedId)
        assertTrue(gate.complete(second))
    }
}
