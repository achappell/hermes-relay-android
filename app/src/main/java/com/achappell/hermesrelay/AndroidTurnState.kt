package com.achappell.hermesrelay

/** Canonical Android presentation phases projected from normalized events. */
internal enum class AndroidTurnPhase {
    Idle,
    Listening,
    Transcribing,
    Thinking,
    Buffering,
    Speaking,
    Complete,
    Unavailable,
    Disconnected,
}

internal enum class AndroidAudioDelivery {
    NotStarted,
    Buffering,
    Speaking,
    Delivered,
    Unavailable,
}

internal data class AndroidTurnState(
    val binding: AndroidTurnBinding? = null,
    val phase: AndroidTurnPhase = AndroidTurnPhase.Idle,
    val responseText: String = "",
    val audio: AndroidAudioDelivery = AndroidAudioDelivery.NotStarted,
    val turnCompleteObserved: Boolean = false,
    val unavailableReason: String? = null,
) {
    val isTerminal: Boolean
        get() = phase in setOf(
            AndroidTurnPhase.Complete,
            AndroidTurnPhase.Unavailable,
            AndroidTurnPhase.Disconnected,
        )

    val isInFlight: Boolean
        get() = binding != null && !isTerminal

    companion object {
        fun awaitingEvents(binding: AndroidTurnBinding): AndroidTurnState =
            AndroidTurnState(binding = binding)
    }
}

/**
 * Adapter-facing normalized events. These names match the existing Hermes
 * session normalization boundary without exposing wire frames to Compose.
 * Audio chunks are represented as lifecycle evidence; PCM never enters UI
 * state or local history.
 */
internal sealed interface AndroidNormalizedEvent {
    val binding: AndroidTurnBinding?

    data class CaptureStarted(
        override val binding: AndroidTurnBinding,
    ) : AndroidNormalizedEvent

    data class TranscriptionStarted(
        override val binding: AndroidTurnBinding,
    ) : AndroidNormalizedEvent

    data class Thinking(
        override val binding: AndroidTurnBinding,
    ) : AndroidNormalizedEvent

    data class ResponseTextDelta(
        override val binding: AndroidTurnBinding,
        val text: String,
    ) : AndroidNormalizedEvent

    data class ResponseTextReplace(
        override val binding: AndroidTurnBinding,
        val text: String,
    ) : AndroidNormalizedEvent

    data class AudioBuffering(
        override val binding: AndroidTurnBinding,
    ) : AndroidNormalizedEvent

    data class AudioStarted(
        override val binding: AndroidTurnBinding,
    ) : AndroidNormalizedEvent

    data class AudioChunkReceived(
        override val binding: AndroidTurnBinding,
    ) : AndroidNormalizedEvent

    data class AudioEnded(
        override val binding: AndroidTurnBinding,
    ) : AndroidNormalizedEvent

    data class AudioFailed(
        override val binding: AndroidTurnBinding,
        val reason: String,
    ) : AndroidNormalizedEvent

    data class TurnCompleted(
        override val binding: AndroidTurnBinding,
        val finalText: String? = null,
    ) : AndroidNormalizedEvent

    data class TurnFailed(
        override val binding: AndroidTurnBinding,
        val reason: String,
    ) : AndroidNormalizedEvent

    data class Disconnected(
        val sessionId: String,
        val reason: String,
    ) : AndroidNormalizedEvent {
        override val binding: AndroidTurnBinding? = null
    }

    data class Unknown(
        override val binding: AndroidTurnBinding,
        val type: String,
    ) : AndroidNormalizedEvent
}

/** Pure projection of normalized session events into Android presentation state. */
internal object AndroidTurnStateReducer {
    fun reduce(
        state: AndroidTurnState,
        event: AndroidNormalizedEvent,
    ): AndroidTurnState {
        if (event is AndroidNormalizedEvent.Disconnected) {
            if (state.binding?.sessionId != event.sessionId || state.isTerminal) {
                return state
            }
            return state.copy(
                phase = AndroidTurnPhase.Disconnected,
                audio = AndroidAudioDelivery.Unavailable,
                unavailableReason = event.reason,
            )
        }

        if (state.binding == null || event.binding != state.binding || state.isTerminal) {
            return state
        }

        return when (event) {
            is AndroidNormalizedEvent.CaptureStarted -> advance(
                state,
                AndroidTurnPhase.Listening,
            )

            is AndroidNormalizedEvent.TranscriptionStarted -> advance(
                state,
                AndroidTurnPhase.Transcribing,
            )

            is AndroidNormalizedEvent.Thinking -> advance(
                state,
                AndroidTurnPhase.Thinking,
            )

            is AndroidNormalizedEvent.ResponseTextDelta -> {
                if (event.text.isEmpty()) {
                    state
                } else {
                    advance(
                        state.copy(responseText = state.responseText + event.text),
                        AndroidTurnPhase.Thinking,
                    )
                }
            }

            is AndroidNormalizedEvent.ResponseTextReplace -> advance(
                state.copy(responseText = event.text),
                AndroidTurnPhase.Thinking,
            )

            is AndroidNormalizedEvent.AudioBuffering -> advance(
                state.copy(audio = AndroidAudioDelivery.Buffering),
                AndroidTurnPhase.Buffering,
            )

            is AndroidNormalizedEvent.AudioStarted -> advance(
                state.copy(audio = AndroidAudioDelivery.Speaking),
                AndroidTurnPhase.Speaking,
            )

            is AndroidNormalizedEvent.AudioChunkReceived -> state

            is AndroidNormalizedEvent.AudioEnded -> finishAudio(state)

            is AndroidNormalizedEvent.AudioFailed -> state.copy(
                phase = AndroidTurnPhase.Unavailable,
                audio = AndroidAudioDelivery.Unavailable,
                unavailableReason = event.reason,
            )

            is AndroidNormalizedEvent.TurnCompleted -> finishTurn(
                state.copy(
                    responseText = event.finalText ?: state.responseText,
                    turnCompleteObserved = true,
                ),
            )

            is AndroidNormalizedEvent.TurnFailed -> state.copy(
                phase = AndroidTurnPhase.Unavailable,
                audio = AndroidAudioDelivery.Unavailable,
                unavailableReason = event.reason,
                turnCompleteObserved = true,
            )

            is AndroidNormalizedEvent.Unknown -> state
            is AndroidNormalizedEvent.Disconnected -> state
        }
    }

    private fun finishAudio(state: AndroidTurnState): AndroidTurnState {
        if (state.audio !in setOf(
                AndroidAudioDelivery.Buffering,
                AndroidAudioDelivery.Speaking,
            )
        ) {
            return state
        }

        val delivered = state.copy(audio = AndroidAudioDelivery.Delivered)
        return if (state.turnCompleteObserved) {
            delivered.copy(phase = AndroidTurnPhase.Complete)
        } else {
            delivered
        }
    }

    private fun finishTurn(state: AndroidTurnState): AndroidTurnState {
        return when (state.audio) {
            AndroidAudioDelivery.Delivered -> state.copy(phase = AndroidTurnPhase.Complete)
            AndroidAudioDelivery.Buffering,
            AndroidAudioDelivery.Speaking -> state

            AndroidAudioDelivery.NotStarted -> state.copy(
                phase = AndroidTurnPhase.Unavailable,
                audio = AndroidAudioDelivery.Unavailable,
                unavailableReason = "Response audio was not delivered.",
            )

            AndroidAudioDelivery.Unavailable -> state.copy(phase = AndroidTurnPhase.Unavailable)
        }
    }

    private fun advance(
        state: AndroidTurnState,
        target: AndroidTurnPhase,
    ): AndroidTurnState {
        if (target.rank < state.phase.rank) {
            return state
        }
        return state.copy(phase = target)
    }

    private val AndroidTurnPhase.rank: Int
        get() = when (this) {
            AndroidTurnPhase.Idle -> 0
            AndroidTurnPhase.Listening -> 1
            AndroidTurnPhase.Transcribing -> 2
            AndroidTurnPhase.Thinking -> 3
            AndroidTurnPhase.Buffering -> 4
            AndroidTurnPhase.Speaking -> 5
            AndroidTurnPhase.Complete,
            AndroidTurnPhase.Unavailable,
            AndroidTurnPhase.Disconnected -> Int.MAX_VALUE
        }
}
