package com.achappell.hermesrelay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapStateTest {
    @Test
    fun bootstrap_state_does_not_claim_unimplemented_capabilities() {
        assertTrue(BootstrapState.title.contains("bootstrap", ignoreCase = true))
        assertTrue(BootstrapState.description.contains("not connected"))
        assertTrue(BootstrapState.boundary.contains("independently verified slices"))
        assertFalse(BootstrapState.description.contains("ready", ignoreCase = true))
    }
}
