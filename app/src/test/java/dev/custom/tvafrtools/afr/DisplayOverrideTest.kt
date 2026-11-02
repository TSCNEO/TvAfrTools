package dev.custom.tvafrtools.afr

import org.junit.Assert.*
import org.junit.Test

class DisplayOverrideTest {
    @Test fun manualSelectionRejectsAnOlderAutoDetection() {
        val state = DisplayOverride()
        val detection = state.select(false)
        val manual = state.select(true)
        assertTrue(state.manual)
        assertFalse(state.accepts(detection))
        assertTrue(state.accepts(manual))
    }
    @Test fun aNewManualSelectionRejectsQueuedChangesAndRestores() {
        val state = DisplayOverride()
        val restore = state.select(false)
        val first = state.select(true)
        val second = state.select(true)
        assertFalse(state.accepts(restore))
        assertFalse(state.accepts(first))
        assertTrue(state.accepts(second))
    }
    @Test fun explicitAutoReleasesManualControl() {
        val state = DisplayOverride()
        val manual = state.select(true)
        val auto = state.select(false)
        assertFalse(state.manual)
        assertFalse(state.accepts(manual))
        assertTrue(state.accepts(auto))
    }
}
