package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRuntimeHoldersTest {
    private val binding = AndroidTurnBinding("profile", "connection", "turn")

    @Test
    fun turn_events_keep_partial_text_and_settle_only_once_until_next_turn() {
        val holder = TurnHolder()
        val port = HolderPort()
        val initiation = AndroidInitiationController(port).initiate(AndroidTurnInput.Typed("hello"))
        holder.update { it.copy(initiationState = initiation) }
        holder.present(AndroidTurnState.awaitingEvents(binding))
        val initial = holder.state.value
        val main = QueuedMain()
        main.post { holder.onEvent(AndroidNormalizedEvent.ResponseTextDelta(binding, "Partial")) }
        main.post { holder.onEvent(AndroidNormalizedEvent.TurnInterrupted(binding, "user")) }
        assertEquals("", holder.state.value.turnState.responseText)
        main.drain()
        assertEquals("", initial.turnState.responseText)
        assertEquals("Partial", holder.state.value.turnState.responseText)
        assertEquals(AndroidTurnPhase.Interrupted, holder.state.value.turnState.phase)
        assertTrue(holder.claimSettlement())
        assertFalse(holder.claimSettlement())
        holder.onEvent(AndroidNormalizedEvent.AudioStarted(binding))
        assertFalse(holder.claimSettlement())
        holder.present(AndroidTurnState.awaitingEvents(binding))
        holder.onEvent(AndroidNormalizedEvent.TurnInterrupted(binding, "user"))
        assertTrue(holder.claimSettlement())
        holder.recordAcceptedPrompt("hello", clearTypedPrompt = true)
        assertEquals(1, holder.state.value.composerClearRevision)
        assertEquals(1, holder.state.value.historyRevision)
    }

    @Test
    fun text_completion_does_not_settle_before_pending_audio_drains() {
        val holder = TurnHolder()
        val initiation = AndroidInitiationController(HolderPort()).initiate(AndroidTurnInput.Typed("hello"))
        holder.update { it.copy(initiationState = initiation) }
        holder.present(AndroidTurnState.awaitingEvents(binding))
        val main = QueuedMain()
        main.post { holder.onEvent(AndroidNormalizedEvent.TextCompleted(binding, "Hello")) }
        main.drain()
        assertEquals(AndroidTurnPhase.Buffering, holder.state.value.turnState.phase)
        assertFalse(holder.claimSettlement())
        main.post { holder.onEvent(AndroidNormalizedEvent.AudioStarted(binding)) }
        main.drain()
        assertEquals(AndroidTurnPhase.Speaking, holder.state.value.turnState.phase)
        assertFalse(holder.claimSettlement())
        main.post { holder.onEvent(AndroidNormalizedEvent.AudioEnded(binding)) }
        main.drain()
        assertEquals(AndroidTurnPhase.Complete, holder.state.value.turnState.phase)
        assertEquals("Hello", holder.state.value.turnState.responseText)
        assertTrue(holder.claimSettlement())
        assertFalse(holder.claimSettlement())
    }

    @Test
    fun capture_reopens_only_a_terminal_hands_free_turn_and_cancel_clears_transcript() {
        val holder = CaptureHolder()
        val speech = FakeSpeechInput()
        val main = QueuedMain()
        val reopened = mutableListOf<Boolean>()
        var turn = AndroidTurnState(phase = AndroidTurnPhase.Interrupted)
        holder.update { it.copy(handsFree = true) }
        val controller = AndroidCaptureController(
            speech = speech,
            timers = ManualVoiceTimers(),
            initiation = AndroidInitiationController(HolderPort()),
            isConnected = { true },
            isAuthorized = { true },
            onStateChange = { changed -> main.post { reopened += holder.onCaptureChanged(changed, turn) } },
        )
        controller.beginCapture()
        main.drain()
        assertEquals(AndroidCaptureState.Starting, holder.state.value.captureState)
        assertTrue(reopened.last())
        turn = AndroidTurnState.awaitingEvents(binding)
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Partial("unfinished"))
        main.drain()
        assertEquals(AndroidCaptureState.Transcribing("unfinished"), holder.state.value.captureState)
        assertFalse(reopened.last())
        controller.cancelCapture()
        main.drain()
        assertEquals(AndroidCaptureState.Idle, holder.state.value.captureState)
        holder.update { it.copy(handsFree = false) }
        assertFalse(holder.onCaptureChanged(AndroidCaptureState.Starting, AndroidTurnState(phase = AndroidTurnPhase.Interrupted)))
    }

    @Test
    fun recovery_does_not_replay_and_explicit_resend_has_one_busy_interval() {
        val holder = RecoveryHolder()
        val port = HolderPort()
        val main = QueuedMain()
        val controller = AndroidRecoveryController(port, policy = ReconnectPolicy.unpaced(1)) { changed ->
            main.post { holder.update { it.copy(recoveryState = changed) } }
        }
        val request = AndroidTurnRequest(port.profile, AndroidTurnInput.Typed("uncertain"))
        controller.transportLost("lost", AndroidUnconfirmedTurn(binding, request))
        controller.recover()
        main.drain()
        assertEquals(AndroidConnectionState.Connected, holder.state.value.recoveryState.connection)
        assertTrue(holder.state.value.recoveryState.hasUnconfirmedTurn)
        assertEquals(0, port.requests.size)
        assertTrue(holder.state.value.canDisconnect)
        assertTrue(holder.beginResend())
        assertFalse(holder.beginResend())
        val busy = holder.state.value
        main.post { holder.completeResend(controller.resendUnconfirmedTurn()) }
        assertTrue(holder.state.value.resendInFlight)
        main.drain()
        assertTrue(busy.resendInFlight)
        assertFalse(holder.state.value.resendInFlight)
        assertEquals(AndroidResendResult.Sent(binding), holder.state.value.resendResult)
        assertEquals(listOf(request), port.requests)
        holder.update { it.copy(userDisconnected = true) }
        assertFalse(holder.state.value.canDisconnect)
    }

    private class QueuedMain {
        private val actions = ArrayDeque<() -> Unit>()
        fun post(action: () -> Unit) { actions.addLast(action) }
        fun drain() { while (actions.isNotEmpty()) actions.removeFirst().invoke() }
    }

    private inner class HolderPort : AndroidClientPort {
        val profile = AndroidProfile("profile", "Profile")
        val requests = mutableListOf<AndroidTurnRequest>()
        override fun snapshot() = AndroidClientSnapshot(
            titleRes = 0,
            descriptionRes = 0,
            boundaryRes = 0,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )
        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            requests += request
            return AndroidInitiationResult.Accepted(binding)
        }
        override fun reconnect(): AndroidReconnectOutcome = AndroidReconnectOutcome.Connected("connection")
    }
}
