package com.achappell.hermesrelay

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Shared fixture for the `ANDROID-HOME-11` real-window matrix
 * (`DoorwayRealWindowMatrixTest`, `DoorwaySplitWindowMatrixTest`): the
 * deterministic Home behind the real `MainActivity`, and reachability helpers
 * that scroll each doorway region the way it is actually used.
 *
 * The fixture never opens a microphone, never plays audio and never reaches
 * Home: it answers `AndroidClientPort` and the two Home management interfaces
 * with in-memory values.
 */
internal object DoorwayMatrix {

    /** A window the OS actually granted, described for failure messages. */
    data class Display(
        val label: String,
        val width: Float,
        val height: Float,
    )

    /** One executed cell: the real window, font scale, IME and orientation. */
    data class Cell(
        val display: Display,
        val fontScale: Float,
        val imeUp: Boolean,
        val landscape: Boolean,
    ) {
        override fun toString() = "${display.label} font=${fontScale} " +
            "ime=${if (imeUp) "up" else "off"} " +
            "orientation=${if (landscape) "landscape" else "portrait"}"
    }

    /**
     * A deliberately long Profile name, so the header wraps where the real
     * product meets a wrapped Profile name.
     */
    const val LongProfileName: String =
        "Alexandra-Cassandra Montgomery-Worthington of the Northern Territories"
}

/**
 * In-memory Home behind the matrix. Values are set before composition and read
 * by the screen exactly where the real adapter's snapshot is read.
 */
internal class LayoutMatrixPort(
    val profileName: String = DoorwayMatrix.LongProfileName,
    @Volatile var authorization: AndroidAuthorizationState = AndroidAuthorizationState.Verified,
    private val interruptSupported: Boolean = true,
    /** Hold the fixture down from the start, so the client stays disconnected. */
    holdDown: Boolean = false,
) : AndroidClientPort, AndroidHomeConversations, AndroidHomeApprovals {

    val requests = mutableListOf<AndroidTurnRequest>()
    var lastBinding: AndroidTurnBinding? = null
        private set

    @Volatile
    var uncertain: Boolean = false

    /** How many times the screen asked this port to reconnect. */
    @Volatile
    var connectAttempts: Int = 0
        private set

    /** True while the screen holds a connected outcome from this port. */
    @Volatile
    var connected: Boolean = false

    /**
     * When set, a reconnect is answered as a transient failure instead of a
     * connection, so the screen keeps offering Recover. The product reconnects
     * on start whenever it can, so a row that must see Recover has to hold the
     * client disconnected deliberately rather than race that behaviour.
     */
    @Volatile
    var failReconnect: Boolean = holdDown

    @Volatile
    private var listener: ((AndroidNormalizedEvent) -> Unit)? = null

    @Volatile
    private var connectionListener: ((AndroidNormalizedEvent.Disconnected) -> Unit)? = null

    val hasObserver: Boolean get() = listener != null

    override fun snapshot() = AndroidClientSnapshot(
        titleRes = BootstrapState.titleRes,
        descriptionRes = BootstrapState.descriptionRes,
        boundaryRes = BootstrapState.boundaryRes,
        selectedProfile = AndroidProfile("matrix", profileName),
        authorizationState = authorization,
    )

    override fun hasUncertainTurn(): Boolean = uncertain

    override fun supportsInterrupt(): Boolean = interruptSupported

    override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
        requests += request
        val binding = AndroidTurnBinding("matrix", "matrix-session", "matrix-turn-${requests.size}")
        lastBinding = binding
        return AndroidInitiationResult.Accepted(binding)
    }

    override fun reconnect(): AndroidReconnectOutcome {
        connectAttempts += 1
        if (failReconnect) {
            connected = false
            // Unrecoverable, not Retryable: a terminal failure settles the
            // client in a steady disconnected state instead of running the
            // recovery ladder, which would disable and move the Recover control.
            return AndroidReconnectOutcome.Unrecoverable(
                "The matrix fixture holds this connection down.",
                AndroidHomeUnavailableReason.TransportUnavailable,
            )
        }
        if (authorization != AndroidAuthorizationState.Verified) {
            connected = false
            return AndroidReconnectOutcome.Unrecoverable(
                "The Profile is not authorized.",
                AndroidHomeUnavailableReason.AuthorizationUnavailable,
            )
        }
        connected = true
        return AndroidReconnectOutcome.Connected(
            "matrix-session",
            capabilities = AndroidHomeCapabilities(interrupt = interruptSupported, audio = false),
        )
    }

    override fun observeTurn(
        binding: AndroidTurnBinding,
        onEvent: (AndroidNormalizedEvent) -> Unit,
    ): AndroidTurnObservation {
        listener = onEvent
        return AndroidTurnObservation { if (listener === onEvent) listener = null }
    }

    override fun observeConnection(
        onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
    ): AndroidTurnObservation {
        connectionListener = onEvent
        return AndroidTurnObservation { if (connectionListener === onEvent) connectionListener = null }
    }

    /** Drives the normalized events the screen renders. */
    fun emit(event: AndroidNormalizedEvent) {
        listener?.invoke(event)
        if (event is AndroidNormalizedEvent.Disconnected) connectionListener?.invoke(event)
    }

    override fun selectedIsPaired() = true
    override fun selectedPairingId() = "matrix-pairing"

    override fun listConversations() = HomeClientClaimProvider.Sessions.Listed(
        listOf(HomeClientSession("matrix-resume", "Matrix conversation", 0.0, 2, false)),
    )

    override fun supportsOpenClaims() = false
    override fun listOpenClaims(pairingId: String?) = HomeClientClaimProvider.OpenClaims.Unsupported
    override fun enrichOpenClaimTitles(pairingId: String, claims: List<HomeClientClaim>) = claims
    override fun closeAndListOpenClaims(pairingId: String, claimRefs: List<String>) =
        HomeClientClaimProvider.CloseAndList(
            HomeClientClaimProvider.CloseClaims.Unsupported,
            HomeClientClaimProvider.OpenClaims.Unsupported,
        )

    override fun currentClaimRef(): String? = null
    override fun currentConversationRef() = "matrix-session"
    override fun requestConversation(intent: HomeConversationIntent) = Unit
    override fun takeConversationNotice(): HomeClaimedConversation? = null
    override fun learnCurrentConversation() = currentConversationRef()
    override fun canRenameConversation() = false
    override fun renameConversation(title: String) = false

    override fun approvals(): HomeClientClaimProvider.Approvals =
        HomeClientClaimProvider.Approvals.Loaded(emptyList(), emptyList())

    override fun decideGrant(targetGrantId: String, action: HomeGrantAction) = HomeGrantActionResult.Done
}

// ---------------------------------------------------------------------------
// Row assertions
// ---------------------------------------------------------------------------

internal fun ComposeTestRule.bounds(tag: String): DpRect =
    onNodeWithTag(tag).getUnclippedBoundsInRoot()

/** The outer scroller's scroll range; null when the layout does not scroll as a whole. */
internal fun ComposeContentTestRule.outerScrollMax(): Float? = outerScrollRange()

private fun ComposeContentTestRule.outerScrollRange(): Float? = try {
    outerScroller().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        .maxValue()
} catch (_: Throwable) {
    null
}

/**
 * Waits until the three regions stop moving. Platform inset animations (the IME
 * appearing, the navigation bar changing) are not Compose idleness, so a
 * geometry assertion taken during one reads two different frames.
 */
internal fun ComposeTestRule.settleGeometry() {
    val reads = awaitStableGeometry()
    check(reads == null) { "the doorway geometry never settled; last reads: $reads" }
}

/**
 * Waits until three consecutive reads of the region bounds and outer scroll
 * position agree. Returns null once they do, or the last reads on timeout.
 */
internal fun ComposeTestRule.awaitStableGeometry(timeoutMillis: Long = GeometrySettleTimeoutMillis): String? {
    val reads = ArrayDeque<String>()
    var stableReads = 0
    val settled = runCatching {
        waitUntil(timeoutMillis) {
            val current = RegionTags.joinToString("/") { safeBounds(it) } + " outer=" + outerScrollPosition()
            if (current == reads.lastOrNull()) stableReads += 1 else stableReads = 0
            reads.addLast(current)
            if (reads.size > 4) reads.removeFirst()
            stableReads >= GeometryStableReads
        }
    }.isSuccess
    return if (settled) null else reads.joinToString(" | ")
}

internal fun ComposeTestRule.outerScrollPosition(): String = runCatching {
    outerScroller().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value().toString()
}.getOrElse { "none" }

private val RegionTags = listOf(
    "android_doorway_header",
    "android_conversation_rail",
    "android_action_surface",
)

internal fun ComposeTestRule.safeBounds(tag: String): String =
    runCatching { bounds(tag).toString() }.getOrElse { "absent" }

/** A fingerprint of the scroll positions, so a stuck search can stop early. */
private fun ComposeContentTestRule.scrollState(container: String): String {
    val inner = runCatching {
        val config = onNodeWithTag(container).fetchSemanticsNode().config
        config[SemanticsProperties.VerticalScrollAxisRange].value()
    }.getOrElse { -1f }
    val outer = runCatching {
        val config = outerScroller().fetchSemanticsNode().config
        config[SemanticsProperties.VerticalScrollAxisRange].value()
    }.getOrElse { -1f }
    val window = runCatching { bounds(container).top.value }.getOrElse { -1f }
    return "$inner/$outer/$window"
}

/**
 * The header, the rail and the composer are laid out in that order and never
 * overlap. The layout scrolls as a whole exactly when the three regions do not
 * fit, so: no whole-layout scroll means the regions must sit fully inside the
 * window, and a whole-layout scroll means they must not.
 */
internal fun ComposeContentTestRule.assertBarsDoNotOverlap(cell: DoorwayMatrix.Cell) {
    val header = bounds("android_doorway_header")
    val rail = bounds("android_conversation_rail")
    val actions = bounds("android_action_surface")
    val viewport = windowRect()
    check(rail.top.value >= header.bottom.value - 0.5f) {
        "$cell: the rail must start below the header, saw rail=$rail header=$header"
    }
    check(rail.bottom.value <= actions.top.value + 0.5f) {
        "$cell: the composer must start below the rail, saw rail=$rail actions=$actions"
    }
    check(rail.bottom.value - rail.top.value > 0f) {
        "$cell: the rail must keep a readable height, saw $rail"
    }
    // The outer scroller is the single source of truth for "does the whole
    // layout scroll": comparing unclipped region bounds to the window cannot
    // see the safe-drawing padding the scroller works inside.
    val scroller = checkNotNull(runCatching { rectOf(outerScroller()) }.getOrNull()) {
        "$cell: the doorway must expose one outer scroller"
    }
    val range = outerScrollRange() ?: 0f
    val visibleHeight = heightOf(scroller)
    val contentHeight = actions.bottom.value - header.top.value
    if (range <= 1f) {
        check(contentHeight <= visibleHeight + 0.5f) {
            "$cell: a non-scrolling layout must fit its own viewport, saw content=${contentHeight}dp " +
                "visible=${visibleHeight}dp"
        }
        check(header.top.value >= scroller.top.value - 0.5f) {
            "$cell: a fitting layout must not scroll the header away, saw header=$header scroller=$scroller"
        }
        check(actions.bottom.value <= scroller.bottom.value + 0.5f) {
            "$cell: a fitting layout must not scroll the composer away, saw actions=$actions scroller=$scroller"
        }
        // Filling the viewport: the header sits at the top and the composer at
        // the bottom of the doorway, so the rail's slot spans everything
        // between them and no gap can strand the newest line mid-screen. (The
        // tagged rail list itself may be shorter than its slot when its
        // content is.)
        check(abs(header.top.value - scroller.top.value) <= 0.5f && abs(actions.bottom.value - scroller.bottom.value) <= 0.5f) {
            "$cell: a fitting layout must fill its viewport, saw header=$header actions=$actions scroller=$scroller"
        }
        check(rail.top.value <= header.bottom.value + 0.5f) {
            "$cell: a fitting layout's rail must start right below the header, saw header=$header rail=$rail"
        }
    } else {
        check(contentHeight > visibleHeight + 0.5f) {
            "$cell: the whole layout scrolls only when the regions exceed it, saw content=${contentHeight}dp " +
                "visible=${visibleHeight}dp viewport=$viewport"
        }
    }
}

private fun ComposeContentTestRule.rectOf(node: SemanticsNodeInteraction): DpRect =
    node.getUnclippedBoundsInRoot()

private fun heightOf(rect: DpRect) = rect.bottom.value - rect.top.value

/**
 * The part of the window the doorway is visible in: the root that holds the
 * doorway (a dropdown or sheet popup adds a second root), cut to the doorway's
 * own scroller, which ends above a real keyboard. A missing root or scroller
 * fails the cell rather than widening the window.
 */
private fun ComposeContentTestRule.windowRect(): DpRect {
    val root = checkNotNull(
        runCatching {
            onAllNodes(isRoot())
                .filter(hasAnyDescendant(hasTestTag("android_doorway_header")))
                .onFirst()
                .getUnclippedBoundsInRoot()
        }.getOrNull(),
    ) { "the window root holding the doorway is missing" }
    val viewport = checkNotNull(runCatching { rectOf(outerScroller()) }.getOrNull()) {
        "the doorway's outer scroller is missing"
    }
    return DpRect(
        maxOf(root.left, viewport.left),
        maxOf(root.top, viewport.top),
        minOf(root.right, viewport.right),
        minOf(root.bottom, viewport.bottom),
    )
}

/** Whether the node is visible inside its container within the visible window. */
private fun ComposeContentTestRule.visibleInWindow(
    container: String,
    node: () -> SemanticsNodeInteraction,
): Boolean {
    val target = runCatching { rectOf(node()) }.getOrNull() ?: return false
    if (!target.top.value.isFinite() || !target.bottom.value.isFinite()) return false
    val box = runCatching { bounds(container) }.getOrNull() ?: return false
    val window = windowRect()
    val top = maxOf(box.top.value, window.top.value) - 0.5f
    val bottom = minOf(box.bottom.value, window.bottom.value) + 0.5f
    if (bottom <= top) return false
    // A node taller than the visible region is reachable if a readable part of
    // it is visible; the matrix asserts the far end separately.
    if (heightOf(target) > bottom - top) {
        return minOf(target.bottom.value, bottom) - maxOf(target.top.value, top) >= MinimumVisiblePx
    }
    // A margin keeps the node clear of the region edge, so a later scroll of the
    // other container cannot leave the click target half outside its region.
    return target.top.value >= top - 0.5f + RevealMarginDp &&
        target.bottom.value <= bottom + 0.5f - RevealMarginDp
}

/**
 * The outer doorway scroller: the only scrollable that contains the header as a
 * descendant (the rail and the composer's column are siblings of it).
 */
private fun ComposeTestRule.outerScroller(): SemanticsNodeInteraction =
    onAllNodes(hasScrollAction())
        .filter(hasAnyDescendant(hasTestTag("android_doorway_header")))
        .onFirst()

/** Scrolls the outer doorway scroller by [deltaDp] (positive reveals lower content). */
private fun ComposeContentTestRule.scrollOuter(deltaDp: Float): Boolean {
    if (deltaDp == 0f) return true
    val node = runCatching { outerScroller() }.getOrNull() ?: return false
    val density = runCatching { node.fetchSemanticsNode().layoutInfo.density }.getOrNull()
        ?: return false
    return runCatching {
        node.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, deltaDp * density.density) }
    }.isSuccess
}

/** Scrolls the outer doorway so [container]'s start (or end) meets the window edge. */
private fun ComposeContentTestRule.alignIntoWindow(container: String, alignEnd: Boolean) {
    repeat(WindowFitSteps) {
        val box = runCatching { bounds(container) }.getOrNull() ?: return
        val window = windowRect()
        val delta = if (alignEnd) box.bottom.value - window.bottom.value
        else box.top.value - window.top.value
        if (delta == 0f) return
        if (!scrollOuter(delta)) return
        waitForIdle()
    }
}

/**
 * Scrolls the outer doorway so [container] is as fully inside the window as it
 * can be. A region taller than the window cannot be shown whole, so either edge
 * may be the reachable one; the reveal loop tries both.
 */
private fun ComposeContentTestRule.bringIntoWindow(container: String) {
    val box = runCatching { bounds(container) }.getOrNull() ?: return
    val window = windowRect()
    val fullyInside = box.top.value >= window.top.value - 0.5f &&
        box.bottom.value <= window.bottom.value + 0.5f
    if (fullyInside) return
    if (heightOf(box) > heightOf(window) + 0.5f) {
        // Taller than the window: its start is the only reachable edge.
        alignIntoWindow(container, alignEnd = false)
        return
    }
    // A region whose start is already above the window is brought in by its end,
    // otherwise by its start.
    if (box.top.value < window.top.value - 0.5f) {
        alignIntoWindow(container, alignEnd = true)
    } else {
        alignIntoWindow(container, alignEnd = false)
    }
}

/** Scrolls whatever is scrollable inside [container] by [dy] pixels. */
internal fun ComposeContentTestRule.scrollContainer(container: String, dy: Float) {
    val tagged = onNodeWithTag(container)
    // Prefer the region's own scroller; only the composer nests its scrollable
    // column inside the tagged surface.
    // The rail is itself the LazyColumn, which carries the scroll action. The
    // composer's tagged node is its Surface, whose scrollable Column is nested.
    val target = if (container == "android_action_surface") {
        runCatching { tagged.onChildren().filter(hasScrollAction()).onFirst() }.getOrNull() ?: tagged
    } else {
        tagged
    }
    val ok = runCatching {
        target.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
    }.isSuccess
    if (!ok) {
        runCatching {
            if (dy >= 0f) tagged.performTouchInput { swipeUp() }
            else tagged.performTouchInput { swipeDown() }
        }
    }
}

internal fun ComposeContentTestRule.awaitTerminalDisconnect(
    runtime: () -> HomeRuntime?,
    cell: DoorwayMatrix.Cell,
) {
    val settled = runCatching {
        waitUntil(30_000) {
            runOnIdle {
                val state = runtime()?.recoveryState
                state != null && !state.isRecovering &&
                    state.connection != AndroidConnectionState.Connected
            }
        }
    }.isSuccess
    check(settled) { "$cell: the client never settled into a disconnected state" }
}

/**
 * Waits for a control to become enabled again. A held-down fixture puts the
 * client through its recovery ladder, which correctly disables Recover until
 * the ladder finishes; this waits for that, bounded, re-revealing as it goes
 * because the ladder finishing recomposes the recovery item.
 */
internal fun ComposeContentTestRule.awaitEnabled(
    tag: String,
    cell: DoorwayMatrix.Cell,
    timeoutMillis: Long = 60_000,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        reachRailItem(tag, cell)
        val disabled = runCatching {
            onNodeWithTag(tag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }.getOrDefault(true)
        if (!disabled) return
        waitForIdle()
    }
    check(false) { "$cell: $tag never became enabled, saw ${safeBounds(tag)}" }
}

internal fun ComposeContentTestRule.reachRailItem(tag: String, cell: DoorwayMatrix.Cell) =
    reachRailNode(tag, cell) { onAllNodesWithTag(tag, useUnmergedTree = true) }

/**
 * Composes and reveals a rail node the way the rail is actually used: doorway
 * at its top so the rail is in the window, rail rewound, then stepped by its own
 * scroller until the node is inside the visible rail and the platform reports
 * it displayed.
 */
private fun ComposeContentTestRule.reachRailNode(
    tag: String,
    cell: DoorwayMatrix.Cell,
    nodes: () -> SemanticsNodeInteractionCollection,
) = reachRegionNode("android_conversation_rail", outerFirst = -HugeScroll, tag, cell, nodes)

/**
 * Composes and reveals a node inside one doorway region the way the region is
 * actually used: the outer doorway parked at the end that shows the region
 * (top for the rail, bottom for the composer), the region rewound, then
 * stepped by its own scroller until the node is inside the visible region and
 * the platform reports it displayed.
 */
private fun ComposeContentTestRule.reachRegionNode(
    container: String,
    outerFirst: Float,
    tag: String,
    cell: DoorwayMatrix.Cell,
    nodes: () -> SemanticsNodeInteractionCollection,
): SemanticsNodeInteraction {
    fun exists() = nodes().fetchSemanticsNodes().isNotEmpty()
    // A lazy region keeps recycled copies of an item in its semantics; only the
    // copy the platform reports displayed is the one on screen.
    fun insideAt(index: Int): Boolean {
        if (runCatching { nodes()[index].assertIsDisplayed() }.isFailure) return false
        val item = runCatching { nodes()[index].getUnclippedBoundsInRoot() }.getOrNull() ?: return false
        val box = bounds(container)
        val window = windowRect()
        val top = maxOf(box.top.value, window.top.value) - 0.5f
        val bottom = minOf(box.bottom.value, window.bottom.value) + 0.5f
        if (item.top.value < top || item.top.value > bottom - MinimumVisiblePx) return false
        return heightOf(item) > bottom - top || item.bottom.value <= bottom
    }
    fun inside(): Boolean = nodes().fetchSemanticsNodes().indices.any(::insideAt)
    scrollOuter(outerFirst)
    waitForIdle()
    scrollContainer(container, -HugeScroll)
    waitForIdle()
    // A lazy region rewound from deep inside one very tall item can stop short
    // of its first item; its own index action reaches the start exactly.
    val region = onNodeWithTag(container)
    if (SemanticsActions.ScrollToIndex in region.fetchSemanticsNode().config) {
        region.performSemanticsAction(SemanticsActions.ScrollToIndex) { it(0) }
        waitForIdle()
    }
    bringIntoWindow(container)
    var steps = 0
    while (!inside() && steps < RailSteps) {
        scrollContainer(container, RailStepPx)
        waitForIdle()
        if (!inside() && exists()) {
            bringIntoWindow(container)
            waitForIdle()
        }
        steps += 1
    }
    var back = 0
    while (!inside() && exists() && back < RailSteps) {
        scrollContainer(container, -RailStepPx / 4f)
        waitForIdle()
        back += 1
    }
    if (!inside()) sweepOuterAndRegion(container, ::inside)
    check(inside()) {
        "$cell: $tag is not reachable inside the visible $container after $steps steps, " +
            "saw ${safeBounds(tag)} region=${safeBounds(container)} window=${windowRect()} " +
            "scroll=" + scrollState(container) + " outerRange=" +
            runCatching { outerScroller().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].let { "${it.value()}/${it.maxValue()}" } }.getOrNull() +
            " outerBounds=" + runCatching { rectOf(outerScroller()) }.getOrNull() + " nodes=" + nodes().fetchSemanticsNodes().map {
                "clipped=${it.boundsInRoot} window=${it.boundsInWindow} placed=${it.layoutInfo.isPlaced} " +
                    "attached=${it.layoutInfo.isAttached}"
            }
    }
    val found = nodes()[nodes().fetchSemanticsNodes().indices.first(::insideAt)].assertIsDisplayed()
    assertClearOfBars(container, found, "$cell: $tag")
    return found
}

/**
 * The part of the window no bar covers, in root dp: below the status bar,
 * above the navigation bar and above the keyboard. Each real-window test
 * installs it from the window's real insets.
 */
internal var unobscuredBand: (() -> DpRect)? = null

/**
 * The visible part of a reached control lies inside [unobscuredBand] and
 * touches no other region: a control the header, the composer surface, a bar or
 * the keyboard covers is not reachable however it scrolls.
 */
internal fun ComposeContentTestRule.assertClearOfBars(
    ownRegion: String,
    control: SemanticsNodeInteraction,
    label: String,
) = assertClearOfBars(ownRegion, control.fetchSemanticsNode(), label)

internal fun ComposeContentTestRule.assertClearOfBars(
    ownRegion: String,
    node: androidx.compose.ui.semantics.SemanticsNode,
    label: String,
) {
    val density = node.layoutInfo.density.density
    fun dp(r: androidx.compose.ui.geometry.Rect) =
        DpRect((r.left / density).dp, (r.top / density).dp, (r.right / density).dp, (r.bottom / density).dp)
    val visible = dp(node.boundsInRoot)
    check(visible.bottom > visible.top) { "$label: the control has no visible area, saw $visible" }
    val band = checkNotNull(unobscuredBand) { "$label: the test did not install the unobscured band" }()
    check(
        visible.top.value >= band.top.value - 0.5f && visible.bottom.value <= band.bottom.value + 0.5f,
    ) { "$label: the control's visible part $visible is under a bar or the keyboard, band=$band" }
    for (other in RegionTags - ownRegion) {
        val region = runCatching { dp(onNodeWithTag(other).fetchSemanticsNode().boundsInRoot) }.getOrNull()
            ?: continue
        val overlap = minOf(visible.bottom.value, region.bottom.value) - maxOf(visible.top.value, region.top.value)
        val across = minOf(visible.right.value, region.right.value) - maxOf(visible.left.value, region.left.value)
        check(overlap <= 0.5f || across <= 0.5f) {
            "$label: the control's visible part $visible overlaps $other $region"
        }
    }
}

/**
 * The user's own two-handed search when the doorway is shorter than one
 * region (a real keyboard over a landscape phone): every outer position, and at
 * each one every position of the region's own scroller.
 */
private fun ComposeContentTestRule.sweepOuterAndRegion(container: String, found: () -> Boolean) {
    scrollOuter(-HugeScroll)
    waitForIdle()
    fun range(node: SemanticsNodeInteraction?) = node?.let {
        runCatching { it.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange] }.getOrNull()
    }
    val region = onNodeWithTag(container)
    val scroller = if (container == "android_action_surface") {
        runCatching { region.onChildren().filter(hasScrollAction()).onFirst().also { it.fetchSemanticsNode() } }.getOrNull()
    } else {
        region
    }
    repeat(OuterSweepSteps) {
        if (found()) return
        range(scroller)?.let { inner ->
            scrollContainer(container, -HugeScroll)
            waitForIdle()
            if (found()) return
            var guard = 0
            while (inner.value() < inner.maxValue() && guard < RailSteps) {
                scrollContainer(container, RailStepPx)
                waitForIdle()
                if (found()) return
                guard += 1
            }
        }
        val outer = range(runCatching { outerScroller() }.getOrNull()) ?: return
        if (outer.value() >= outer.maxValue()) return
        scrollOuter(OuterSweepStepDp)
        waitForIdle()
    }
    found()
}

/** Reveals rail text (an untagged control label) the way [reachRailItem] reveals a tag. */
internal fun ComposeContentTestRule.reachRailText(text: String, cell: DoorwayMatrix.Cell) =
    reachRailNode(text, cell) { onAllNodesWithText(text, substring = true, useUnmergedTree = true) }

/** Reveals composer text (an untagged control label) the way [reachAction] reveals a tag. */
internal fun ComposeContentTestRule.reachActionText(text: String, cell: DoorwayMatrix.Cell) =
    reachRegionNode("android_action_surface", outerFirst = HugeScroll, text, cell) {
        onAllNodesWithText(text, useUnmergedTree = true)
    }

internal fun ComposeContentTestRule.reachAction(tag: String, cell: DoorwayMatrix.Cell) =
    reachRegionNode("android_action_surface", outerFirst = HugeScroll, tag, cell) {
        onAllNodesWithTag(tag, useUnmergedTree = true)
    }

/**
 * Reveals a rail control and presses it, re-checking that its centre is inside
 * the rail at press time: a click outside its region would land on whatever
 * covers that point instead of the control.
 */
internal fun ComposeContentTestRule.reachAndClickRail(tag: String, cell: DoorwayMatrix.Cell) {
    val node = reachRailItem(tag, cell)
    val rect = node.getUnclippedBoundsInRoot()
    val rail = bounds("android_conversation_rail")
    check(centreInside(rect, rail)) {
        "$cell: $tag's centre is outside the rail at press time, saw $rect in $rail"
    }
    node.performClick()
    waitForIdle()
}

private fun centreInside(node: DpRect, box: DpRect): Boolean {
    val x = (node.left.value + node.right.value) / 2f
    val y = (node.top.value + node.bottom.value) / 2f
    return x > box.left.value && x < box.right.value && y > box.top.value && y < box.bottom.value
}

/** The header is a region of its own; reachability is measured against the window. */
internal fun ComposeContentTestRule.reachHeaderControl(tag: String, cell: DoorwayMatrix.Cell) {
    // The header is the top of the doorway: park the doorway at its top first.
    scrollOuter(-HugeScroll)
    waitForIdle()
    bringIntoWindow("android_doorway_header")
    check(visibleInWindow("android_doorway_header") { onNodeWithTag(tag) }) {
        "$cell: header control $tag is unreachable, saw ${safeBounds(tag)} in " +
            safeBounds("android_doorway_header")
    }
    assertClearOfBars("android_doorway_header", onNodeWithTag(tag), "$cell: $tag")
}

private const val GeometrySettleTimeoutMillis = 10_000L
private const val GeometryStableReads = 3
private const val RailStepPx = 40f
private const val RailSteps = 80
private const val OuterSweepSteps = 40
private const val OuterSweepStepDp = 40f
private const val MinimumVisiblePx = 8f
private const val HugeScroll = 100_000f
private const val WindowFitSteps = 8

/** Clearance a header control must keep from its region edge. */
private const val RevealMarginDp = 4f
