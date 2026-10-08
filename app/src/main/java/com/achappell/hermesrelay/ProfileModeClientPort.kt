package com.achappell.hermesrelay

/**
 * Serves the one [AndroidClientPort] the runtime owns by sending every call to
 * the transport that belongs to the selected Profile's mode (`ANDROID-STD-01`).
 *
 * A Standard Profile talks to Hermes directly; every other Profile keeps the
 * Home bridge path exactly as before. The two transports never share a socket,
 * a credential or a turn: when the selected mode changes, the transport that
 * is no longer selected is closed before the new one connects, so a Standard
 * socket cannot outlive its Profile and Home is never used as a fallback.
 *
 * Home-only surfaces ([AndroidHomeConversations], [AndroidHomeApprovals]) are
 * delegated to the Home transport and stay hidden in Standard mode because the
 * Home transport reports no paired Profile for a Standard selection. The
 * Standard-only [AndroidStandardSession] is delegated to the Standard transport.
 */
internal class ProfileModeClientPort<H, S>(
    private val selectedMode: () -> RelayProfileMode?,
    private val home: H,
    private val standard: S,
) : AndroidClientPort,
    AndroidHomeConversations by home,
    AndroidHomeApprovals by home,
    AndroidStandardSession by standard
    where H : AndroidClientPort, H : AndroidHomeConversations, H : AndroidHomeApprovals,
          S : AndroidClientPort, S : AndroidStandardSession {
    private val lock = Any()
    private var lastWasStandard: Boolean? = null

    private fun isStandard(): Boolean = selectedMode() == RelayProfileMode.Standard

    private fun active(): AndroidClientPort = if (isStandard()) standard else home

    /** Closes the transport that stopped being selected, once, before the new one is used. */
    private fun activate(): AndroidClientPort {
        val nowStandard = isStandard()
        val previous = synchronized(lock) {
            lastWasStandard.also { lastWasStandard = nowStandard }
        }
        if (previous != null && previous != nowStandard) {
            (if (previous) standard else home).endSession()
        }
        return if (nowStandard) standard else home
    }

    override fun snapshot(): AndroidClientSnapshot =
        active().snapshot().copy(mode = selectedMode())

    override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult =
        active().beginTurn(request)

    override fun observeTurn(
        binding: AndroidTurnBinding,
        onEvent: (AndroidNormalizedEvent) -> Unit,
    ): AndroidTurnObservation = active().observeTurn(binding, onEvent)

    override fun observeConnection(
        onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
    ): AndroidTurnObservation {
        val homeObservation = home.observeConnection(onEvent)
        val standardObservation = standard.observeConnection(onEvent)
        return AndroidTurnObservation {
            homeObservation.cancel()
            standardObservation.cancel()
        }
    }

    override fun reconnect(): AndroidReconnectOutcome = activate().reconnect()

    override fun supportsInterrupt(): Boolean = active().supportsInterrupt()

    override fun interruptTurn(binding: AndroidTurnBinding): Boolean =
        active().interruptTurn(binding)

    override fun hasActiveTurn(): Boolean = active().hasActiveTurn()

    override fun prepareForExplicitResend() = active().prepareForExplicitResend()

    override fun endSession() {
        home.endSession()
        standard.endSession()
    }

    override fun close() {
        home.close()
        standard.close()
    }
}
