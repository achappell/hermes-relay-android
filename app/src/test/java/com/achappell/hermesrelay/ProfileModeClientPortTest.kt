package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ANDROID-STD-01`: the one runtime port sends each call to the transport that
 * belongs to the selected Profile's mode, and the two never share a socket.
 */
class ProfileModeClientPortTest {
    private var mode: RelayProfileMode? = RelayProfileMode.HomeBridge
    private val home = FakeHome()
    private val standard = FakeStandard()
    private val port = ProfileModeClientPort({ mode }, home, standard)

    private val request = AndroidTurnRequest(AndroidProfile("p", "P"), AndroidTurnInput.Typed("hi"))

    @Test
    fun home_and_legacy_profiles_use_the_home_transport() {
        listOf(RelayProfileMode.HomeBridge, RelayProfileMode.Legacy, null).forEach { selected ->
            mode = selected
            home.calls.clear()
            standard.calls.clear()

            port.beginTurn(request)

            assertEquals(listOf("beginTurn"), home.calls)
            assertTrue("Standard was touched for $selected", standard.calls.isEmpty())
        }
    }

    @Test
    fun a_standard_profile_uses_only_the_standard_transport_and_never_falls_back_to_home() {
        mode = RelayProfileMode.Standard

        port.beginTurn(request)
        port.supportsInterrupt()
        port.hasActiveTurn()
        port.prepareForExplicitResend()

        assertEquals(
            listOf("beginTurn", "supportsInterrupt", "hasActiveTurn", "prepareForExplicitResend"),
            standard.calls,
        )
        assertTrue("Home was touched for a Standard Profile", home.calls.isEmpty())
    }

    @Test
    fun a_failed_standard_reconnect_is_never_retried_through_home() {
        mode = RelayProfileMode.Standard
        standard.reconnectOutcome =
            AndroidReconnectOutcome.Unrecoverable("down", AndroidHomeUnavailableReason.TransportUnavailable)

        val outcome = port.reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
        assertTrue(home.calls.isEmpty())
    }

    @Test
    fun the_snapshot_reports_the_selected_mode() {
        mode = RelayProfileMode.Standard
        assertEquals(RelayProfileMode.Standard, port.snapshot().mode)

        mode = RelayProfileMode.Legacy
        assertEquals(RelayProfileMode.Legacy, port.snapshot().mode)

        mode = null
        assertNull(port.snapshot().mode)
    }

    @Test
    fun changing_mode_ends_the_transport_that_stopped_being_selected_before_connecting() {
        mode = RelayProfileMode.HomeBridge
        port.reconnect()
        home.calls.clear()
        standard.calls.clear()

        mode = RelayProfileMode.Standard
        port.reconnect()

        assertEquals("Home session ended before Standard connects", listOf("endSession"), home.calls)
        assertEquals(listOf("reconnect"), standard.calls)

        home.calls.clear()
        standard.calls.clear()
        port.reconnect()
        assertTrue("no repeat close while the mode is unchanged", home.calls.isEmpty())

        mode = RelayProfileMode.HomeBridge
        port.reconnect()
        assertEquals(listOf("endSession"), standard.calls.filter { it == "endSession" })
        assertTrue(home.calls.contains("reconnect"))
    }

    @Test
    fun the_first_reconnect_closes_nothing() {
        mode = RelayProfileMode.Standard

        port.reconnect()

        assertTrue(home.calls.isEmpty())
        assertEquals(listOf("reconnect"), standard.calls)
    }

    @Test
    fun connection_loss_from_either_transport_reaches_the_runtime() {
        val events = mutableListOf<String>()
        val observation = port.observeConnection { events += it.reason }

        home.lose("home")
        standard.lose("standard")
        assertEquals(listOf("home", "standard"), events)

        observation.cancel()
        home.lose("again")
        standard.lose("again")
        assertEquals("cancelling stops both", 2, events.size)
    }

    @Test
    fun turn_observation_follows_the_selected_transport() {
        mode = RelayProfileMode.Standard
        val binding = AndroidTurnBinding("p", "h", "c", "t")

        port.observeTurn(binding) {}

        assertEquals(listOf("observeTurn"), standard.calls)
        assertTrue(home.calls.isEmpty())
    }

    @Test
    fun deliberate_end_and_teardown_reach_both_transports() {
        port.endSession()
        assertEquals(listOf("endSession"), home.calls)
        assertEquals(listOf("endSession"), standard.calls)

        home.calls.clear()
        standard.calls.clear()
        port.close()
        assertEquals(listOf("close"), home.calls)
        assertEquals(listOf("close"), standard.calls)
    }

    @Test
    fun home_only_surfaces_come_from_home_and_standard_only_surfaces_from_standard() {
        mode = RelayProfileMode.Standard

        assertFalse("a Standard Profile is never a paired Home", port.selectedIsPaired())
        assertEquals(AndroidNewConversationResult.Created, port.newConversation())
        assertEquals(1, standard.newConversations)
        assertTrue(port.hasUncertainTurn())
        assertSame(standard.finishingObservation, port.observeFinishing {})
    }

    private class FakeStandard : AndroidClientPort, AndroidStandardSession {
        val calls = mutableListOf<String>()
        var newConversations = 0
        var reconnectOutcome: AndroidReconnectOutcome = AndroidReconnectOutcome.Connected("c")
        val finishingObservation = AndroidTurnObservation {}
        private var connectionListener: ((AndroidNormalizedEvent.Disconnected) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(0, 0, 0)

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            calls += "beginTurn"
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
        }

        override fun reconnect(): AndroidReconnectOutcome {
            calls += "reconnect"
            return reconnectOutcome
        }

        override fun supportsInterrupt(): Boolean {
            calls += "supportsInterrupt"
            return true
        }

        override fun hasActiveTurn(): Boolean {
            calls += "hasActiveTurn"
            return false
        }

        override fun prepareForExplicitResend() {
            calls += "prepareForExplicitResend"
        }

        override fun endSession() {
            calls += "endSession"
        }

        override fun close() {
            calls += "close"
        }

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            calls += "observeTurn"
            return AndroidTurnObservation {}
        }

        override fun observeConnection(
            onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
        ): AndroidTurnObservation {
            connectionListener = onEvent
            return AndroidTurnObservation { connectionListener = null }
        }

        override fun newConversation(): AndroidNewConversationResult {
            newConversations += 1
            return AndroidNewConversationResult.Created
        }

        override fun isFinishingPreviousResponse(): Boolean = false

        override fun hasUncertainTurn(): Boolean = true

        override fun observeFinishing(onChange: (Boolean) -> Unit): AndroidTurnObservation =
            finishingObservation

        fun lose(reason: String) {
            connectionListener?.invoke(AndroidNormalizedEvent.Disconnected("c", reason))
        }
    }

    private class FakeHome :
        AndroidClientPort,
        AndroidHomeConversations,
        AndroidHomeApprovals {
        val calls = mutableListOf<String>()
        private var connectionListener: ((AndroidNormalizedEvent.Disconnected) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(0, 0, 0)

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            calls += "beginTurn"
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
        }

        override fun reconnect(): AndroidReconnectOutcome {
            calls += "reconnect"
            return AndroidReconnectOutcome.Connected("h")
        }

        override fun endSession() {
            calls += "endSession"
        }

        override fun close() {
            calls += "close"
        }

        override fun observeConnection(
            onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
        ): AndroidTurnObservation {
            connectionListener = onEvent
            return AndroidTurnObservation { connectionListener = null }
        }

        override fun selectedIsPaired(): Boolean = false

        override fun selectedPairingId(): String? = null

        override fun listConversations(): HomeClientClaimProvider.Sessions = unused()

        override fun supportsOpenClaims(): Boolean = false

        override fun listOpenClaims(pairingId: String?): HomeClientClaimProvider.OpenClaims = unused()

        override fun enrichOpenClaimTitles(
            pairingId: String,
            claims: List<HomeClientClaim>,
        ): List<HomeClientClaim> = unused()

        override fun closeAndListOpenClaims(
            pairingId: String,
            claimRefs: List<String>,
        ): HomeClientClaimProvider.CloseAndList = unused()

        override fun currentClaimRef(): String? = null

        override fun currentConversationRef(): String? = null

        override fun requestConversation(intent: HomeConversationIntent) = unused()

        override fun takeConversationNotice(): HomeClaimedConversation? = null

        override fun learnCurrentConversation(): String? = null

        override fun canRenameConversation(): Boolean = false

        override fun renameConversation(title: String): Boolean = false

        override fun approvals(): HomeClientClaimProvider.Approvals = unused()

        override fun decideGrant(targetGrantId: String, action: HomeGrantAction): HomeGrantActionResult =
            unused()

        fun lose(reason: String) {
            connectionListener?.invoke(AndroidNormalizedEvent.Disconnected("h", reason))
        }

        private fun <T> unused(): T = error("not used by this test")
    }
}
