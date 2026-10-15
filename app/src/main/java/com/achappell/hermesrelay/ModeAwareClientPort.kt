package com.achappell.hermesrelay

/** Routes only the explicitly selected mode; failures never change the selection. */
internal class ModeAwareClientPort(
    private val collection: () -> RelayProfileCollection,
    internal val home: AndroidClientPort,
    private val standard: AndroidClientPort,
) : AndroidClientPort {
    override fun connectionMode(): RelayConnectionMode =
        collection().selected?.mode ?: RelayConnectionMode.Legacy

    private fun selected(): AndroidClientPort =
        if (connectionMode() == RelayConnectionMode.Standard) standard else home

    override fun snapshot() = selected().snapshot()
    override fun beginTurn(request: AndroidTurnRequest) = selected().beginTurn(request)
    override fun observeTurn(binding: AndroidTurnBinding, onEvent: (AndroidNormalizedEvent) -> Unit) =
        selected().observeTurn(binding, onEvent)

    override fun observeConnection(onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit): AndroidTurnObservation {
        val homeObserver = home.observeConnection { if (selected() === home) onEvent(it) }
        val standardObserver = standard.observeConnection { if (selected() === standard) onEvent(it) }
        return AndroidTurnObservation {
            homeObserver.cancel()
            standardObserver.cancel()
        }
    }

    override fun reconnect() = selected().reconnect()
    override fun reconnect(attemptTimeoutMillis: Long) = selected().reconnect(attemptTimeoutMillis)
    override fun supportsInterrupt() = selected().supportsInterrupt()
    override fun interruptTurn(binding: AndroidTurnBinding) = selected().interruptTurn(binding)
    override fun hasActiveTurn() = selected().hasActiveTurn()
    override fun hasUncertainTurn() = selected().hasUncertainTurn()
    override fun newConversation() = selected().newConversation()
    override fun pauseOutput() = selected().pauseOutput()
    override fun resumeOutput() = selected().resumeOutput()
    override fun setOutputDucked(ducked: Boolean) = selected().setOutputDucked(ducked)
    override fun playbackLead() = selected().playbackLead()
    override fun playbackLeadStream() = selected().playbackLeadStream()
    override fun prepareForExplicitResend() = selected().prepareForExplicitResend()
    override fun endSession() = selected().endSession()
    override fun hasLiveTransport() = selected().hasLiveTransport()
    override fun closeForLifecycle() = selected().closeForLifecycle()
    override fun closeAfterInterrupt() = selected().closeAfterInterrupt()
    override fun close() {
        home.close()
        standard.close()
    }
    fun configurationChanged() {
        home.endSession()
        standard.endSession()
    }
}

internal fun AndroidClientPort.homeConversations(): AndroidHomeConversations? =
    if (connectionMode() == RelayConnectionMode.HomeBridge) {
        ((this as? ModeAwareClientPort)?.home ?: this) as? AndroidHomeConversations
    } else null

internal fun AndroidClientPort.homeApprovals(): AndroidHomeApprovals? =
    if (connectionMode() == RelayConnectionMode.HomeBridge) {
        ((this as? ModeAwareClientPort)?.home ?: this) as? AndroidHomeApprovals
    } else null
