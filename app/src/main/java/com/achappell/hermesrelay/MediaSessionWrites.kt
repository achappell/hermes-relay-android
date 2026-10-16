package com.achappell.hermesrelay

/** Process-owned accounting; never holds its monitor across a native call or dispatch. */
internal class MediaSessionWrites {
    internal class Response(internal var baseline: Long?) {
        internal var result: Long? = null
    }

    private var successfulWrites = 0L
    private var mainThreadWrites = 0L
    private var background = false
    private var backgroundBaseline: Long? = null
    private var response = Response(null)

    @Synchronized
    fun current(): Response = response

    /** Replies admitted within one background stay share its entry baseline. */
    @Synchronized
    fun begin(): Response = Response(if (background) backgroundBaseline else null).also { response = it }

    /** Called before entry-triggered service work; already-queued native writes still count. */
    @Synchronized
    fun backgroundEntered() {
        background = true
        backgroundBaseline = successfulWrites
        if (response.result == null) response.baseline = successfulWrites
    }

    /** Returning foreground preserves the last entry's window until audio ends or re-entry. */
    @Synchronized
    fun foregroundEntered() {
        background = false
    }

    /** Unconditional: only successful returns of MediaSession.setPlaybackState call this. */
    @Synchronized
    fun written(onMainThread: Boolean) {
        successfulWrites++
        if (onMainThread) mainThreadWrites++
    }

    /** Read-only native-write evidence, also available to instrumented service consumers. */
    @Synchronized
    fun snapshot(): Pair<Long, Long> = successfulWrites to mainThreadWrites
    /** Audio end excludes later terminal service work. No background window means zero scoped writes. */
    @Synchronized
    fun end(owner: Response): Long = owner.result ?: (
        owner.baseline?.let { successfulWrites - it } ?: 0L
    ).also { owner.result = it }
}
