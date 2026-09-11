package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapStateTest {
    @Test
    fun bootstrap_port_exposes_resource_backed_state() {
        val snapshot = BootstrapClientPort.snapshot()

        assertEquals(BootstrapState.titleRes, snapshot.titleRes)
        assertEquals(BootstrapState.descriptionRes, snapshot.descriptionRes)
        assertEquals(BootstrapState.boundaryRes, snapshot.boundaryRes)
    }
}
