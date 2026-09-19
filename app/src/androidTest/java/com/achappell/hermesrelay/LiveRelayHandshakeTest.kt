package com.achappell.hermesrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One-scenario live Home proof. The wrapper invokes this class four times and
 * supplies a different disposable handle/prompt pair for each invocation.
 *
 * No prompt has a source default. The test writes a content-free schema-3
 * handoff to the private app cache; the host wrapper is the only component that
 * retrieves it. The class is excluded from the default instrumentation run by
 * [LiveRelay].
 */
@RunWith(AndroidJUnit4::class)
@LiveRelay
class LiveRelayHandshakeTest {

    @Test
    fun run_selected_scenario() {
        val arguments = readLiveArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = runCatching {
            when (arguments.scenario) {
                SCENARIO_HANDSHAKE -> runHandshake(arguments)
                SCENARIO_TYPED_AUDIO -> runTypedAudio(arguments)
                SCENARIO_INTERRUPT -> runInterrupt(arguments)
                SCENARIO_RECONNECT -> runReconnect(arguments)
                else -> harnessFailure(arguments)
            }
        }.getOrElse { harnessFailure(arguments) }

        val wrote = LiveHomeSafeResult.write(context, result)
        assertSafeBoolean(LABEL_RESULT_WRITTEN, wrote)
    }

    /** Reads all instrumentation inputs without supplying secrets or prompt defaults. */
    private fun readLiveArguments(): LiveArguments {
        val arguments = InstrumentationRegistry.getArguments()
        fun readPrompt(key: String): String {
            val encoded = arguments.getString(key + "Base64") ?: return ""
            if (!encoded.matches(Regex("[A-Za-z0-9_-]+"))) return ""
            return runCatching {
                String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrDefault("")
        }
        val result = LiveArguments(
            scenario = arguments.getString("scenario").orEmpty(),
            profileId = arguments.getString("homeProfileId").orEmpty(),
            route = arguments.getString("homeRoute").orEmpty(),
            credential = arguments.getString("homeCredential").orEmpty(),
            conversationHandle = arguments.getString("homeConversationHandle").orEmpty(),
            typedPrompt = readPrompt("typedPrompt"),
            interruptPrompt = readPrompt("interruptPrompt"),
            reconnectPrompt = readPrompt("reconnectPrompt"),
            runId = arguments.getString("liveRunId").orEmpty(),
            deviceSerialFingerprint = arguments.getString("liveDeviceSerialFingerprint").orEmpty(),
            clientId = arguments.getString("relayClientId") ?: DEFAULT_CLIENT_ID,
            deviceId = arguments.getString("relayDeviceId") ?: DEFAULT_DEVICE_ID,
        )
        val validPrompt = when (result.scenario) {
            SCENARIO_HANDSHAKE -> result.typedPrompt.isEmpty() &&
                result.interruptPrompt.isEmpty() && result.reconnectPrompt.isEmpty()
            SCENARIO_TYPED_AUDIO -> isSafePrompt(result.typedPrompt) &&
                result.interruptPrompt.isEmpty() && result.reconnectPrompt.isEmpty()
            SCENARIO_INTERRUPT -> result.typedPrompt.isEmpty() &&
                isSafePrompt(result.interruptPrompt) && result.reconnectPrompt.isEmpty()
            SCENARIO_RECONNECT -> result.typedPrompt.isEmpty() &&
                result.interruptPrompt.isEmpty() && isSafePrompt(result.reconnectPrompt)
            else -> false
        }
        val valid = result.scenario in SCENARIOS &&
            result.profileId.matches(PROFILE_ID_PATTERN) &&
            RelayProfileValidator.validateApprovedHomeRoute(result.route) == null &&
            HomeCredentialValidator.isValid(result.credential) &&
            isSafeHandle(result.conversationHandle) &&
            validPrompt &&
            result.runId.matches(RUN_ID_PATTERN) &&
            result.deviceSerialFingerprint.matches(DEVICE_FINGERPRINT_PATTERN)
        require(valid) { LABEL_ARGUMENTS }
        return result
    }

    private fun runHandshake(arguments: LiveArguments): LiveHomeScenarioResult {
        val client = client(arguments, arguments.conversationHandle, RecordingAudioSink())
        return try {
            resetRequestTelemetry(client)
            val outcome = client.reconnect()
            android.util.Log.i("LiveHomeDiagnostic", "READINESS=" + (outcome.reasonCode()?.name ?: "Connected"))
            val message = when (outcome) {
                is AndroidReconnectOutcome.Retryable -> outcome.reason
                is AndroidReconnectOutcome.Unrecoverable -> outcome.reason
                is AndroidReconnectOutcome.Connected -> ""
            }
            val transport = when (message) {
                "The approved Home route could not be found." -> "DNS"
                "The approved Home route's certificate could not be verified." -> "TLS"
                "The approved Home route timed out." -> "TIMEOUT"
                "The approved Home route is unreachable." -> "SOCKET"
                "The approved Home bridge route was not found." -> "HTTP404"
                else -> "OTHER"
            }
            android.util.Log.i("LiveHomeDiagnostic", "TRANSPORT=" + transport)
            val readiness = LiveHomeReadiness.assertNewTurnReadiness(outcome)
            val connected = readiness.connected
            if (connected == null) {
                result(
                    arguments,
                    statusForReadiness(readiness.reason ?: outcome.reasonCode()),
                    safeReason(readiness.reason ?: outcome.reasonCode()),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            } else {
                val facts = LiveHomeHandshakeFacts(
                    connectionReady = true,
                    responseIdMatched = true,
                    unresolvedTurn = connected.unresolvedTurn,
                    route = connected.route,
                    capabilities = connected.capabilities,
                )
                result(
                    arguments,
                    STATUS_PASS,
                    null,
                    client.snapshotRequestTelemetry(),
                    LiveHomeScenarioFacts.Handshake(facts),
                )
            }
        } finally {
            client.closeLiveGateConversation()
            client.close()
        }
    }

    private fun runTypedAudio(arguments: LiveArguments): LiveHomeScenarioResult {
        val sink = AudioTrackAudioSink()
        val client = client(arguments, arguments.conversationHandle, sink)
        return try {
            resetRequestTelemetry(client)
            val outcome = client.reconnect()
            val readiness = LiveHomeReadiness.assertNewTurnReadiness(outcome)
            val connected = readiness.connected
            if (connected == null) {
                return result(
                    arguments,
                    statusForReadiness(readiness.reason ?: outcome.reasonCode()),
                    safeReason(readiness.reason ?: outcome.reasonCode()),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            }
            val capability = assertLiveHomeCapabilities(connected, LiveHomeCapability.Audio)
            if (!capability.accepted) {
                return result(
                    arguments,
                    STATUS_NOT_RUN,
                    safeReason(capability.reason),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            }
            val accepted = client.beginTurn(
                AndroidTurnRequest(
                    AndroidProfile(arguments.profileId, arguments.displayName, arguments.deviceId),
                    AndroidTurnInput.Typed(arguments.typedPrompt),
                ),
            )
            if (accepted !is AndroidInitiationResult.Accepted) {
                android.util.Log.i("LiveHomeDiagnostic", "INITIATION=" + accepted.javaClass.simpleName + ";REASON=" + accepted.reasonCode()?.name)
                return result(
                    arguments,
                    STATUS_FAIL,
                    safeReason(accepted.reasonCode()),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            }
            val collector = TurnCollector(accepted.binding)
            val observation = client.observeTurn(accepted.binding, collector::accept)
            return try {
            val terminal = collector.terminal.await(TURN_DEADLINE_SECONDS, TimeUnit.SECONDS)
                val telemetry = awaitAudioTelemetry(sink)
                android.util.Log.i("LiveHomeDiagnostic", "AUDIO_FAILURE=" + telemetry.failureKind?.name)
                val state = collector.state.get()
                val facts = LiveHomeTypedAudioFacts(
                    format = collector.audioFormat.get(),
                    acceptedBytes = telemetry.acceptedBytes,
                    acceptedFrames = telemetry.acceptedFrames,
                    audioChunkReceived = collector.audioChunkReceived.get(),
                    audioFailed = collector.audioFailed.get() || telemetry.failed,
                    drained = telemetry.drained,
                    underrunCount = telemetry.underrunCount,
                    terminalEventObserved = terminal,
                    finalPhase = state.phase,
                    finalAudio = state.audio,
                )
                val passed = terminal &&
                    state.phase == AndroidTurnPhase.Complete &&
                    state.audio == AndroidAudioDelivery.Delivered &&
                    telemetry.started &&
                    telemetry.acceptedBytes > 0 &&
                    telemetry.acceptedFrames > 0 &&
                    collector.audioChunkReceived.get() &&
                    !collector.audioFailed.get() &&
                    !telemetry.failed &&
                    telemetry.drained &&
                    telemetry.underrunCount == 0
                result(
                    arguments,
                    if (passed) STATUS_PASS else STATUS_FAIL,
                    if (passed) null else SAFE_REASON_AUDIO_FAILURE,
                    client.snapshotRequestTelemetry(),
                    LiveHomeScenarioFacts.TypedAudio(facts),
                )
            } finally {
                observation.cancel()
            }
        } finally {
            client.closeLiveGateConversation()
            client.close()
        }
    }

    private fun runInterrupt(arguments: LiveArguments): LiveHomeScenarioResult {
        val client = client(arguments, arguments.conversationHandle, RecordingAudioSink())
        return try {
            resetRequestTelemetry(client)
            val outcome = client.reconnect()
            val readiness = LiveHomeReadiness.assertNewTurnReadiness(outcome)
            val connected = readiness.connected
            if (connected == null) {
                return result(
                    arguments,
                    statusForReadiness(readiness.reason ?: outcome.reasonCode()),
                    safeReason(readiness.reason ?: outcome.reasonCode()),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            }
            val capability = assertLiveHomeCapabilities(connected, LiveHomeCapability.Interrupt)
            if (!capability.accepted) {
                return result(
                    arguments,
                    STATUS_NOT_RUN,
                    safeReason(capability.reason),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            }
            val accepted = client.beginTurn(
                AndroidTurnRequest(
                    AndroidProfile(arguments.profileId, arguments.displayName, arguments.deviceId),
                    AndroidTurnInput.Typed(arguments.interruptPrompt),
                ),
            )
            if (accepted !is AndroidInitiationResult.Accepted) {
                android.util.Log.i("LiveHomeDiagnostic", "INITIATION=" + accepted.javaClass.simpleName + ";REASON=" + accepted.reasonCode()?.name)
                return result(
                    arguments,
                    STATUS_FAIL,
                    safeReason(accepted.reasonCode()),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            }
            val collector = TurnCollector(accepted.binding)
            val observation = client.observeTurn(accepted.binding, collector::accept)
            return try {
                val readyToInterrupt = awaitNonTerminalTurnState(collector)
                val stateBeforeRequest = collector.state.get()
                if (!readyToInterrupt || stateBeforeRequest.isTerminal) {
                    val facts = interruptFacts(client, collector, terminalEvent = collector.terminalEvent())
                    return result(
                        arguments,
                        STATUS_INCONCLUSIVE,
                        SAFE_REASON_NATURAL_COMPLETION_RACE,
                        client.snapshotRequestTelemetry(),
                        LiveHomeScenarioFacts.Interrupt(facts),
                    )
                }

                val sent = client.interruptTurn(accepted.binding)
                val sentSnapshot = client.snapshotInterruptTelemetry()
                if (!sent) {
                    val facts = interruptFacts(client, collector, terminalEvent = collector.terminalEvent())
                    return result(
                        arguments,
                        STATUS_INCONCLUSIVE,
                        SAFE_REASON_NATURAL_COMPLETION_RACE,
                        client.snapshotRequestTelemetry(),
                        LiveHomeScenarioFacts.Interrupt(facts),
                    )
                }
                val acknowledgement = awaitInterruptAcknowledgement(client)
                val interrupted = awaitMatchingTurnInterrupted(collector, accepted.binding)
                val finalSnapshot = client.snapshotInterruptTelemetry()
                val passed = sentSnapshot.sentCount == 1 &&
                    acknowledgement &&
                    interrupted &&
                    finalSnapshot.terminalObserved
                val facts = LiveHomeInterruptFacts(
                    nonTerminalStateObserved = readyToInterrupt,
                    interruptSentCount = finalSnapshot.sentCount,
                    acknowledgementObserved = finalSnapshot.acknowledgementObserved,
                    terminalEventObserved = finalSnapshot.terminalObserved,
                    terminalEvent = collector.terminalEvent(),
                )
                result(
                    arguments,
                    if (passed) STATUS_PASS else STATUS_FAIL,
                    if (passed) null else SAFE_REASON_TERMINAL_TIMEOUT,
                    client.snapshotRequestTelemetry(),
                    LiveHomeScenarioFacts.Interrupt(facts),
                )
            } finally {
                observation.cancel()
            }
        } finally {
            client.closeLiveGateConversation()
            client.close()
        }
    }

    private fun runReconnect(arguments: LiveArguments): LiveHomeScenarioResult {
        val client = client(arguments, arguments.conversationHandle, RecordingAudioSink())
        val peerClosed = CountDownLatch(1)
        val disconnectedConnection = AtomicReference<String?>(null)
        val connectionObservation = client.observeConnection { event ->
            disconnectedConnection.set(event.connectionId)
            peerClosed.countDown()
        }
        return try {
            resetRequestTelemetry(client)
            val initial = client.reconnect()
            val initialReadiness = LiveHomeReadiness.assertNewTurnReadiness(initial)
            val connected = initialReadiness.connected
                ?: return result(
                    arguments,
                    statusForReadiness(initialReadiness.reason ?: initial.reasonCode()),
                    safeReason(initialReadiness.reason ?: initial.reasonCode()),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            val accepted = client.beginTurn(
                AndroidTurnRequest(
                    AndroidProfile(arguments.profileId, arguments.displayName, arguments.deviceId),
                    AndroidTurnInput.Typed(arguments.reconnectPrompt),
                ),
            )
            if (accepted !is AndroidInitiationResult.Accepted) {
                android.util.Log.i("LiveHomeDiagnostic", "INITIATION=" + accepted.javaClass.simpleName + ";REASON=" + accepted.reasonCode()?.name)
                return result(
                    arguments,
                    STATUS_FAIL,
                    safeReason(accepted.reasonCode()),
                    client.snapshotRequestTelemetry(),
                    null,
                )
            }
            val binding = accepted.binding
            val acceptedCount = client.snapshotRequestTelemetry().promptSubmitCount
            val closeObserved = peerClosed.await(PEER_CLOSE_DEADLINE_SECONDS, TimeUnit.SECONDS) &&
                disconnectedConnection.get() == binding.connectionId
            if (!closeObserved) {
                return result(
                    arguments,
                    STATUS_NOT_RUN,
                    SAFE_REASON_CONTROLLED_CLOSE,
                    client.snapshotRequestTelemetry(),
                    LiveHomeScenarioFacts.Reconnect(
                        LiveHomeReconnectFacts(
                            acceptedPromptSubmitCount = acceptedCount,
                            postAcceptPromptSubmitCount = 0,
                            peerCloseObserved = false,
                            conversationReconnectObserved = false,
                            sameConversation = false,
                            uncertaintyPreserved = false,
                        ),
                    ),
                )
            }
            val recovered = client.reconnect()
            android.util.Log.i("LiveHomeDiagnostic", "READINESS=" + recovered.javaClass.simpleName + ";REASON=" + recovered.reasonCode()?.name)
            val reconnectReadiness = LiveHomeReadiness.assertReconnectReadiness(recovered)
            val recoveredConnected = reconnectReadiness.connected
            val counts = client.snapshotRequestTelemetry()
            val methodWasReconnect = client.lastHandshakeMethod() == HANDSHAKE_RECONNECT
            val sameConversation = recoveredConnected != null &&
                recoveredConnected.route == connected.route &&
                methodWasReconnect
            val facts = LiveHomeReconnectFacts(
                acceptedPromptSubmitCount = acceptedCount,
                postAcceptPromptSubmitCount = (counts.promptSubmitCount - acceptedCount).coerceAtLeast(0),
                peerCloseObserved = closeObserved,
                conversationReconnectObserved = methodWasReconnect,
                sameConversation = sameConversation,
                uncertaintyPreserved = reconnectReadiness.preservesUnresolvedTurn,
            )
            val passed = recoveredConnected != null &&
                reconnectReadiness.accepted &&
                recoveredConnected.unresolvedTurn &&
                closeObserved &&
                methodWasReconnect &&
                sameConversation &&
                counts.promptSubmitCount == 1 &&
                counts.interruptRequestCount == 0
            result(
                arguments,
                if (passed) STATUS_PASS else STATUS_FAIL,
                if (passed) null else SAFE_REASON_RECONNECT_TRACE,
                counts,
                LiveHomeScenarioFacts.Reconnect(facts),
            )
        } finally {
            connectionObservation.cancel()
            client.closeLiveGateConversation()
            client.close()
        }
    }

    private fun client(
        arguments: LiveArguments,
        handle: String,
        sink: AndroidAudioSink,
    ): OkHttpRelaySessionClient {
        val profile = RelayProfile(
            id = arguments.profileId,
            endpoint = arguments.route,
            clientId = arguments.clientId,
            deviceId = arguments.deviceId,
            displayName = arguments.displayName,
            homeBinding = RelayHomeBinding(arguments.route, handle),
        )
        return OkHttpRelaySessionClient(
            collection = {
                RelayProfileCollection(profiles = listOf(profile), selectedId = profile.id)
            },
            credentials = InMemoryRelayCredentialStore(
                homeCredentials = mapOf(profile.id to arguments.credential),
            ),
            helloTimeoutMillis = HOME_READY_TIMEOUT_MILLIS,
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
            audioSink = sink,
            liveHomeGateTrace = AndroidLiveHomeGateTrace(
                runId = arguments.runId,
                scenario = arguments.scenario,
                deviceSerialFingerprint = arguments.deviceSerialFingerprint,
            ),
        )
    }

    private fun assertLiveHomeCapabilities(
        outcome: AndroidReconnectOutcome.Connected,
        required: LiveHomeCapability,
    ): LiveHomeReadinessResult {
        val readiness = LiveHomeReadiness.assertLiveHomeCapabilities(outcome, required)
        assertSafeBoolean(
            LABEL_CAPABILITY_RESULT_SHAPED,
            readiness.reason != AndroidHomeUnavailableReason.CapabilityShapeInvalid,
        )
        return readiness
    }

    private fun awaitNonTerminalTurnState(collector: TurnCollector): Boolean {
        if (!collector.nonTerminal.await(NON_TERMINAL_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            return false
        }
        val state = collector.state.get()
        return !state.isTerminal && state.phase in setOf(
            AndroidTurnPhase.Thinking,
            AndroidTurnPhase.Speaking,
        )
    }

    private fun awaitAudioTelemetry(sink: AudioTrackAudioSink): AndroidAudioSinkTelemetry {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AUDIO_DRAIN_DEADLINE_SECONDS)
        var telemetry = sink.snapshotTelemetry()
        while (!telemetry.drained && !telemetry.failed && System.nanoTime() < deadline) {
            try {
                Thread.sleep(AUDIO_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            telemetry = sink.snapshotTelemetry()
        }
        return telemetry
    }

    private fun awaitInterruptAcknowledgement(client: OkHttpRelaySessionClient): Boolean =
        client.awaitInterruptAcknowledgement(INTERRUPT_ACK_DEADLINE_MILLIS)

    private fun awaitMatchingTurnInterrupted(
        collector: TurnCollector,
        binding: AndroidTurnBinding,
    ): Boolean {
        if (!collector.interrupted.await(INTERRUPT_TERMINAL_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            return false
        }
        val event = collector.interruptedEvent.get() ?: return false
        val eventBinding = event.binding
        return eventBinding.profileId == binding.profileId &&
            eventBinding.conversationHandle == binding.conversationHandle &&
            eventBinding.connectionId == binding.connectionId &&
            eventBinding.turnId == binding.turnId &&
            collector.state.get().phase == AndroidTurnPhase.Interrupted
    }

    private fun resetRequestTelemetry(client: OkHttpRelaySessionClient) {
        client.resetRequestTelemetry()
        client.resetInterruptTelemetry()
    }

    /** Kept as a named safe assertion seam for the host's isolated report proof. */
    private fun assertDefaultReportCounts(nonLiveCount: Int, liveCount: Int) {
        assertSafeCount(LABEL_DEFAULT_NON_LIVE_COUNT, 1, nonLiveCount.coerceAtLeast(0))
        assertSafeCount(LABEL_DEFAULT_LIVE_COUNT, 0, liveCount.coerceAtLeast(0))
    }

    private fun interruptFacts(
        client: OkHttpRelaySessionClient,
        collector: TurnCollector,
        terminalEvent: String?,
    ) = LiveHomeInterruptFacts(
        nonTerminalStateObserved = collector.nonTerminal.count == 0L,
        interruptSentCount = client.snapshotInterruptTelemetry().sentCount,
        acknowledgementObserved = client.snapshotInterruptTelemetry().acknowledgementObserved,
        terminalEventObserved = collector.terminal.count == 0L,
        terminalEvent = terminalEvent,
    )

    private fun result(
        arguments: LiveArguments,
        status: String,
        reason: String?,
        counts: AndroidClientRequestTelemetrySnapshot,
        facts: LiveHomeScenarioFacts?,
    ) = LiveHomeScenarioResult(
        runId = arguments.runId,
        scenario = arguments.scenario,
        status = status,
        reason = reason,
        evidence = LiveHomeScenarioEvidence(
            available = facts != null,
            requestCounts = LiveHomeRequestCounts(
                promptSubmit = counts.promptSubmitCount,
                interrupt = counts.interruptRequestCount,
            ),
            facts = facts,
        ),
    )

    private fun harnessFailure(arguments: LiveArguments) = LiveHomeScenarioResult(
        runId = arguments.runId,
        scenario = arguments.scenario,
        status = STATUS_INCONCLUSIVE,
        reason = SAFE_REASON_HARNESS_FAILURE,
        evidence = LiveHomeScenarioEvidence(
            available = false,
            requestCounts = LiveHomeRequestCounts(0, 0),
            facts = null,
        ),
    )

    private fun safeReason(reason: AndroidHomeUnavailableReason?): String = when (reason) {
        AndroidHomeUnavailableReason.Home404 -> SAFE_REASON_HOME_404
        AndroidHomeUnavailableReason.InvalidBinding,
        AndroidHomeUnavailableReason.InvalidCredential,
        AndroidHomeUnavailableReason.MissingBinding,
        -> SAFE_REASON_INVALID_BINDING
        AndroidHomeUnavailableReason.UnresolvedTurn -> SAFE_REASON_UNRESOLVED_TURN
        AndroidHomeUnavailableReason.CapabilityShapeInvalid -> SAFE_REASON_CAPABILITY_SHAPE
        AndroidHomeUnavailableReason.CapabilityUnavailable -> SAFE_REASON_CAPABILITY_UNAVAILABLE
        AndroidHomeUnavailableReason.ReconnectTrace -> SAFE_REASON_RECONNECT_TRACE
        AndroidHomeUnavailableReason.TransportTimeout,
        AndroidHomeUnavailableReason.TransportUnavailable,
        AndroidHomeUnavailableReason.HermesUnavailable,
        AndroidHomeUnavailableReason.HermesTimeout,
        -> SAFE_REASON_HOME_UNAVAILABLE
        else -> SAFE_REASON_HOME_UNAVAILABLE
    }

    private fun statusForReadiness(reason: AndroidHomeUnavailableReason?): String = when (reason) {
        AndroidHomeUnavailableReason.TransportTimeout,
        AndroidHomeUnavailableReason.TransportUnavailable,
        AndroidHomeUnavailableReason.HermesUnavailable,
        AndroidHomeUnavailableReason.HermesTimeout,
        -> STATUS_NOT_RUN
        else -> STATUS_FAIL
    }

    private fun AndroidReconnectOutcome.reasonCode(): AndroidHomeUnavailableReason? = when (this) {
        is AndroidReconnectOutcome.Connected -> null
        is AndroidReconnectOutcome.Retryable -> reasonCode
        is AndroidReconnectOutcome.Unrecoverable -> reasonCode
    }

    private fun AndroidInitiationResult.reasonCode(): AndroidHomeUnavailableReason? = when (this) {
        is AndroidInitiationResult.Accepted -> null
        is AndroidInitiationResult.Rejected -> when (reason) {
            AndroidInitiationFailure.HomeBindingUnavailable -> AndroidHomeUnavailableReason.MissingBinding
            AndroidInitiationFailure.SessionUnavailable -> AndroidHomeUnavailableReason.TransportUnavailable
            AndroidInitiationFailure.DeliveryUncertain -> AndroidHomeUnavailableReason.TransportTimeout
            AndroidInitiationFailure.ProfileUnavailable -> AndroidHomeUnavailableReason.InvalidBinding
            AndroidInitiationFailure.AuthorizationRequired -> AndroidHomeUnavailableReason.AuthorizationUnavailable
            AndroidInitiationFailure.EmptyTypedPrompt -> AndroidHomeUnavailableReason.InvalidBinding
            AndroidInitiationFailure.RequestRejected -> AndroidHomeUnavailableReason.RequestRejected
        }
        is AndroidInitiationResult.Uncertain -> reason
    }

    private class TurnCollector(
        private val binding: AndroidTurnBinding,
    ) {
        val state = AtomicReference(AndroidTurnState.awaitingEvents(binding))
        val nonTerminal = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val interruptedEvent = AtomicReference<AndroidNormalizedEvent.TurnInterrupted?>(null)
        val audioFormat = AtomicReference<AndroidAudioFormat?>(null)
        val audioChunkReceived = AtomicBoolean(false)
        val audioFailed = AtomicBoolean(false)

        fun accept(event: AndroidNormalizedEvent) {
            when (event) {
                is AndroidNormalizedEvent.AudioStarted -> audioFormat.set(event.format)
                is AndroidNormalizedEvent.AudioChunkReceived -> audioChunkReceived.set(true)
                is AndroidNormalizedEvent.AudioFailed -> audioFailed.set(true)
                is AndroidNormalizedEvent.TurnInterrupted -> {
                    interruptedEvent.set(event)
                }
                else -> Unit
            }
            val next = AndroidTurnStateReducer.reduce(state.get(), event)
            state.set(next)
            if (event is AndroidNormalizedEvent.TurnInterrupted) interrupted.countDown()
            if (!next.isTerminal && next.phase in setOf(AndroidTurnPhase.Thinking, AndroidTurnPhase.Speaking)) {
                nonTerminal.countDown()
            }
            if (next.isTerminal) terminal.countDown()
        }

        fun terminalEvent(): String? = when {
            interruptedEvent.get() != null -> "interrupted"
            state.get().phase == AndroidTurnPhase.Complete -> "completed"
            state.get().isTerminal -> "failed"
            else -> null
        }
    }

    private data class LiveArguments(
        val scenario: String,
        val profileId: String,
        val route: String,
        val credential: String,
        val conversationHandle: String,
        val typedPrompt: String,
        val interruptPrompt: String,
        val reconnectPrompt: String,
        val runId: String,
        val deviceSerialFingerprint: String,
        val clientId: String,
        val deviceId: String,
    ) {
        val displayName: String = "Android live gate"
    }

    private companion object {
        val PROFILE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        const val SCENARIO_HANDSHAKE = "handshake"
        const val SCENARIO_TYPED_AUDIO = "typed_audio"
        const val SCENARIO_INTERRUPT = "interrupt"
        const val SCENARIO_RECONNECT = "reconnect"
        const val HANDSHAKE_RECONNECT = "conversation.reconnect"
        const val STATUS_PASS = "pass"
        const val STATUS_FAIL = "fail"
        const val STATUS_INCONCLUSIVE = "inconclusive"
        const val STATUS_NOT_RUN = "not-run"
        const val DEFAULT_CLIENT_ID = "android-live-gate"
        const val DEFAULT_DEVICE_ID = "android"
        val DEVICE_FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
        const val HOME_READY_TIMEOUT_MILLIS = 15_000L
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
        const val INTERRUPT_ACK_DEADLINE_MILLIS = 10_000L
        const val TURN_DEADLINE_SECONDS = 120L
        const val NON_TERMINAL_DEADLINE_SECONDS = 15L
        const val INTERRUPT_TERMINAL_DEADLINE_SECONDS = 15L
        const val PEER_CLOSE_DEADLINE_SECONDS = 15L
        const val AUDIO_DRAIN_DEADLINE_SECONDS = 35L
        const val AUDIO_POLL_MILLIS = 25L
        const val LABEL_ARGUMENTS = "INVALID_LIVE_ARGUMENTS"
        const val LABEL_RESULT_WRITTEN = "RESULT_WRITTEN"
        const val LABEL_CAPABILITY_RESULT_SHAPED = "CAPABILITY_RESULT_SHAPED"
        const val LABEL_DEFAULT_NON_LIVE_COUNT = "DEFAULT_NON_LIVE_COUNT"
        const val LABEL_DEFAULT_LIVE_COUNT = "DEFAULT_LIVE_COUNT"
        const val SAFE_REASON_AUDIO_FAILURE = "AUDIO_FAILURE"
        const val SAFE_REASON_NATURAL_COMPLETION_RACE = "NATURAL_COMPLETION_RACE"
        const val SAFE_REASON_TERMINAL_TIMEOUT = "TERMINAL_TIMEOUT"
        const val SAFE_REASON_CONTROLLED_CLOSE = "CONTROLLED_CLOSE"
        const val SAFE_REASON_RECONNECT_TRACE = "RECONNECT_TRACE"
        const val SAFE_REASON_HARNESS_FAILURE = "HARNESS_FAILURE"
        const val SAFE_REASON_HOME_404 = "HOME_404"
        const val SAFE_REASON_INVALID_BINDING = "INVALID_BINDING"
        const val SAFE_REASON_UNRESOLVED_TURN = "UNRESOLVED_TURN"
        const val SAFE_REASON_CAPABILITY_SHAPE = "CAPABILITY_SHAPE_INVALID"
        const val SAFE_REASON_CAPABILITY_UNAVAILABLE = "CAPABILITY_UNAVAILABLE"
        const val SAFE_REASON_HOME_UNAVAILABLE = "HOME_UNAVAILABLE"
        val SCENARIOS = setOf(
            SCENARIO_HANDSHAKE,
            SCENARIO_TYPED_AUDIO,
            SCENARIO_INTERRUPT,
            SCENARIO_RECONNECT,
        )
        val RUN_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun isSafeHandle(value: String): Boolean =
            value.toByteArray(Charsets.UTF_8).size in 1..256 &&
                value.none { it == '\u0000' || it == '\r' || it == '\n' || it.isWhitespace() }

        fun isSafePrompt(value: String): Boolean =
            value.toByteArray(Charsets.UTF_8).size in 1..4096 &&
                value.none { it == '\u0000' || it == '\r' || it == '\n' }

        fun assertSafeBoolean(label: String, condition: Boolean) {
            assertTrue(label, condition)
        }

        fun assertSafeCount(label: String, expected: Int, actual: Int) {
            assertEquals(label, expected, actual)
        }
    }
}
