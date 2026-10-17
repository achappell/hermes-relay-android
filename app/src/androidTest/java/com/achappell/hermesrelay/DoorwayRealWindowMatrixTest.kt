package com.achappell.hermesrelay

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso.pressBack
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * `ANDROID-HOME-11` matrix, actual-emulator layer.
 *
 * This runs the real `MainActivity` on a real AVD: the real window, the real
 * status/navigation-bar insets, the real OS soft keyboard and the real
 * multi-window bounds the shell driver configured before the run. Only the Home
 * behind the screen is a fixture (`HermesRelayApplication.homeRuntimeBox`); the
 * fixture makes every control state reachable, so an idle-only screen cannot
 * stand in for the missing states.
 *
 * The shell driver records the device receipts (wm size/density, font scale,
 * navigation mode, task bounds) and the screenshots; this test records the
 * observable geometry and the real IME inset.
 */
@RunWith(AndroidJUnit4::class)
class DoorwayRealWindowMatrixTest {

    private val fixtureRuntime = InstallMatrixRuntime()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fixtureRuntime).around(composeRule)

    /**
     * Leaves no soft keyboard behind: a keyboard left open would make the next
     * test's first Back close the keyboard instead of what that test expects.
     */
    @org.junit.After
    fun leaveNoKeyboard() = hideKeyboard()

    private fun hideKeyboard() {
        runCatching {
            composeRule.runOnIdle {
                val activity = composeRule.activity
                activity.currentFocus?.clearFocus()
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            composeRule.waitUntil(10_000) { imeBottomPx() == 0 }
        }
    }

    private companion object {
        const val KeyboardSwipes = 30
        const val FourLineStatus = "This Profile is unavailable until relay authorization"
        const val SendAction = "Start typed turn"
        const val ResendAction = "Resend this turn"
        const val DiscardAction = "Discard this turn"
        const val TranscriptFirstLine = "LINE-01 first line of the long response"
        const val TranscriptLastLine = "LINE-40 last line of the long response"
        val MenuEntries = listOf(
            "android_menu_configure_relay",
            "android_menu_conversations",
            "android_menu_approvals",
            "android_menu_history",
            "android_menu_disconnect",
        )
        val SheetEntries = listOf(
            "android_menu_conversations" to "android_conversations_sheet",
            "android_menu_approvals" to "android_approvals_sheet",
            "android_menu_history" to "android_history_sheet",
        )
    }

    /**
     * Reaches a node with the keyboard up the way a person does: real finger
     * drags (`input swipe`) inside the part of the window the keyboard leaves,
     * until the node is wholly visible above the keyboard.
     */
    private fun revealAboveKeyboard(label: String, preferUp: Boolean, nodes: () -> List<androidx.compose.ui.semantics.SemanticsNode>) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val d = density()
        val origin = IntArray(2)
        fun visible(): Pair<Float, Float> {
            composeRule.runOnIdle { composeRule.activity.window.decorView.getLocationInWindow(origin) }
            val top = composeRule.onAllNodesWithTag("android_doorway_header").fetchSemanticsNodes()
                .firstOrNull()?.let { 0f } ?: 0f
            val statusTop = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top?.toFloat() ?: top
            val bottom = (composeRule.activity.window.decorView.height - imeBottomPx()).toFloat()
            return statusTop to bottom
        }
        fun whollyVisible(): Boolean {
            val (top, bottom) = visible()
            return nodes().any { node ->
                val clipped = node.boundsInWindow
                // A node taller than the space above the keyboard counts once
                // it fills that space; anything else must be wholly inside it.
                val needed = minOf(node.size.height.toFloat(), bottom - top) - 1f
                node.layoutInfo.isPlaced && clipped.height >= needed && needed > 0f &&
                    clipped.top >= top - 1f && clipped.bottom <= bottom + 1f
            }
        }
        var swipes = 0
        while (!whollyVisible() && swipes < KeyboardSwipes) {
            val (top, bottom) = visible()
            val node = nodes().firstOrNull()
            val up = when {
                node == null || node.size.height == 0 -> preferUp
                else -> node.positionInWindow.y + node.size.height > bottom
            }
            val span = minOf(60f * d, (bottom - top) * 0.6f)
            val start = if (up) bottom - 8f * d else top + 8f * d
            val end = if (up) start - span else start + span
            val x = composeRule.activity.window.decorView.width / 2
            ui.executeShellCommand("input swipe $x ${start.toInt()} $x ${end.toInt()} 600").close()
            composeRule.waitForIdle()
            Thread.sleep(300)
            swipes += 1
        }
        assertTrue(
            "$label must be wholly visible above the open keyboard after $swipes real drags, " +
                "saw ${nodes().map { it.boundsInWindow }} visible=${visible()} ime=${imeBottomPx()}px",
            whollyVisible(),
        )
    }

    private fun density() = composeRule.activity.resources.displayMetrics.density

    private fun imeBottomPx(): Int =
        ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0


    /** The cell descriptor for the window the OS actually gave this Activity. */
    private fun realCell(imeUp: Boolean): DoorwayMatrix.Cell {
        val configuration: Configuration = composeRule.activity.resources.configuration
        val metrics = composeRule.activity.resources.displayMetrics
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DoorwayMatrix.Cell(
            DoorwayMatrix.Display(
                "real-window-${if (landscape) "landscape" else "portrait"}",
                metrics.widthPixels / metrics.density,
                metrics.heightPixels / metrics.density,
            ),
            configuration.fontScale,
            imeUp,
            landscape,
        )
    }

    /**
     * With the keyboard down: the real regions stay ordered in the real window,
     * the real rail scrolls, and the state, Recover, composer and tap-to-speak
     * controls are reachable with real input.
     */
    @Test
    fun real_window_keeps_every_control_reachable_without_the_keyboard() {
        val ime = imeBottomPx()
        assertTrue("the fixture must start without a soft keyboard, saw IME ${ime}px", ime == 0)
        val cell = realCell(imeUp = false)
        val metrics = composeRule.activity.resources.displayMetrics
        println(
            "HOME11-WINDOW no-ime window=" +
                "${composeRule.activity.window.decorView.width}x${composeRule.activity.window.decorView.height} " +
                "density=${metrics.density} fontScale=${cell.fontScale} " +
                "landscape=${cell.landscape} imePx=${imeBottomPx()}",
        )
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachRailItem("android_doorway_state", cell)

        // Disconnected state: Recover is a primary control and this is the real
        // OS window, so the press must both reach the client and connect. The
        // fixture starts held down, so this is the state the client really shows,
        // but only once the client has settled out of any recovery attempt.
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        composeRule.reachRailItem("android_connect", cell)
        composeRule.onAllNodesWithTag("android_connect", useUnmergedTree = true)[0].assertIsEnabled()
        // The physical press must arrive at the client: count attempts across it,
        // and let the press's own (held-down) recovery settle before going on.
        val before = fixtureRuntime.port.connectAttempts
        composeRule.reachAndClickRail("android_connect", cell)
        composeRule.waitUntil(10_000) { fixtureRuntime.port.connectAttempts > before }
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)

        // Release the fixture and press again: this press connects.
        fixtureRuntime.port.failReconnect = false
        composeRule.reachRailItem("android_connect", cell)
        composeRule.awaitEnabled("android_connect", cell)
        composeRule.reachAndClickRail("android_connect", cell)
        composeRule.waitUntil(10_000) { fixtureRuntime.port.connected }

        // Connected state: the connected controls are reachable and Recover is
        // correctly gone.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("android_connect").fetchSemanticsNodes().isEmpty()
        }
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachAction("android_typed_prompt", cell)
        composeRule.reachAction("android_tap_to_speak", cell)
        // Every menu entry is displayed in this window, and each sheet entry
        // really opens its sheet.
        openMenu(cell)
        for (entry in MenuEntries) {
            composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed()
        }
        pressBack()
        composeRule.waitForIdle()
        for ((entry, sheet) in SheetEntries) {
            openMenu(cell)
            composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag(sheet).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(sheet).assertIsDisplayed()
            pressBack()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag(sheet).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    private fun openMenu(cell: DoorwayMatrix.Cell) {
        composeRule.reachHeaderControl("android_more_menu", cell)
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("android_menu_conversations").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * With the real soft keyboard up: the OS reports a real IME inset, the
     * focused composer stays above it in the resized window, and the rail keeps
     * a readable height rather than collapsing.
     */
    @Test
    fun real_window_keeps_the_composer_above_the_open_keyboard() {
        val cell = realCell(imeUp = true)
        composeRule.reachAction("android_typed_prompt", cell)
        composeRule.onNodeWithTag("android_typed_prompt").performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var ime = imeBottomPx()
        if (ime == 0) {
            // In a split the companion task can hold window focus, so focus the
            // field through its own semantics and show the keyboard for the
            // view that now holds focus.
            composeRule.onNodeWithTag("android_typed_prompt")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            composeRule.runOnIdle {
                val view = composeRule.activity.currentFocus ?: composeRule.activity.window.decorView
                composeRule.activity
                    .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(view, 0)
            }
            composeRule.waitUntil(10_000) { imeBottomPx() > 0 }
            ime = imeBottomPx()
        }
        assertTrue("the OS must report a real soft-keyboard inset, saw ${ime}px", ime > 0)
        println(
            "HOME11-WINDOW ime-up window=" +
                "${composeRule.activity.window.decorView.width}x${composeRule.activity.window.decorView.height} " +
                "imePx=$ime density=${density()} fontScale=${cell.fontScale} landscape=${cell.landscape}",
        )

        // Reachability is measured against the doorway's own viewport, which
        // ends above the real keyboard: the field and Send must both be
        // reachable there by scrolling, with the keyboard still up.
        composeRule.settleGeometry()
        revealAboveKeyboard("the prompt field", preferUp = true) {
            composeRule.onAllNodesWithTag("android_typed_prompt", useUnmergedTree = true).fetchSemanticsNodes()
        }
        revealAboveKeyboard("Send", preferUp = true) {
            composeRule.onAllNodesWithText(SendAction, useUnmergedTree = true).fetchSemanticsNodes()
        }
        revealAboveKeyboard("the doorway state", preferUp = false) {
            composeRule.onAllNodesWithTag("android_doorway_state", useUnmergedTree = true).fetchSemanticsNodes()
        }
        assertTrue("the keyboard must still be up, saw ${imeBottomPx()}px", imeBottomPx() > 0)
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
    }

    /**
     * Authorization unavailable, with the long Profile name: the four-line
     * status block, Edit relay, Recover and the composer stay reachable.
     */
    @Test
    fun real_window_keeps_the_authorization_state_reachable() {
        val cell = realCell(imeUp = false)
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        assertTrue(
            "the long Profile name must be rendered",
            composeRule.onAllNodesWithText(DoorwayMatrix.LongProfileName, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.reachRailItem("android_doorway_state", cell)
        composeRule.reachRailText(FourLineStatus, cell)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onAllNodesWithText(FourLineStatus, substring = true, useUnmergedTree = true)[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
        println(
            "HOME11-WINDOW status-block lines=${layouts.firstOrNull()?.lineCount} " +
                "fontScale=${cell.fontScale} landscape=${cell.landscape}",
        )
        composeRule.reachRailItem("android_edit_relay", cell)
        // Blocked authorization offers Edit relay, not Recover
        // (`canAttemptClientConnection`), so Connect is correctly absent here.
        assertTrue(
            "$cell: Recover must not be offered while authorization is blocked",
            composeRule.onAllNodesWithTag("android_connect", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        composeRule.reachAction("android_typed_prompt", cell)
        composeRule.reachAction("android_tap_to_speak", cell)
        openMenu(cell)
        for (entry in MenuEntries - "android_menu_disconnect") {
            composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed()
        }
        pressBack()
        composeRule.waitForIdle()
    }

    /**
     * A real typed turn through the real composer, then: its phase and
     * Interrupt; a 40-line response whose first and last lines are both
     * reachable in the rail; and, after the transport drops mid-turn,
     * Recover, Resend and Discard.
     */
    @Test
    fun real_window_keeps_turn_controls_and_the_whole_transcript_reachable() {
        val cell = realCell(imeUp = false)
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        fixtureRuntime.port.failReconnect = false
        composeRule.reachRailItem("android_connect", cell)
        composeRule.awaitEnabled("android_connect", cell)
        composeRule.reachAndClickRail("android_connect", cell)
        composeRule.waitUntil(10_000) { fixtureRuntime.port.connected }

        composeRule.reachAction("android_typed_prompt", cell)
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("matrix long response")
        if (imeBottomPx() > 0) {
            revealAboveKeyboard("Send", preferUp = true) {
                composeRule.onAllNodesWithText(SendAction, useUnmergedTree = true).fetchSemanticsNodes()
            }
        } else {
            composeRule.reachActionText(SendAction, cell)
        }
        composeRule.onAllNodesWithText(SendAction, useUnmergedTree = true)[0].performClick()
        composeRule.waitUntil(10_000) { fixtureRuntime.port.lastBinding != null }
        val binding = requireNotNull(fixtureRuntime.port.lastBinding)
        // This test measures the keyboard-free window; the IME cells are
        // real_window_keeps_the_composer_above_the_open_keyboard.
        composeRule.runOnIdle {
            val activity = composeRule.activity
            activity.currentFocus?.clearFocus()
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitUntil(10_000) { imeBottomPx() == 0 }

        composeRule.runOnIdle { fixtureRuntime.port.emit(AndroidNormalizedEvent.Thinking(binding)) }
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachRailItem("android_turn_phase", cell)
        composeRule.reachRailItem("android_interrupt", cell)
        composeRule.onNodeWithTag("android_interrupt").assertIsEnabled()

        val response = buildString {
            append("$TranscriptFirstLine\n")
            for (line in 2..39) append("LINE-%02d a line of the long response\n".format(line))
            append(TranscriptLastLine)
        }
        composeRule.runOnIdle {
            fixtureRuntime.port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, response))
        }
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        // First line: the response's top is reachable inside the rail.
        composeRule.reachRailItem("android_response_text", cell)
        // Last line: stepping the rail reaches the response's bottom edge inside
        // the visible rail, above the composer.
        val railBox = composeRule.bounds("android_conversation_rail")
        repeat(400) {
            val text = composeRule.bounds("android_response_text")
            if (text.bottom.value <= composeRule.bounds("android_conversation_rail").bottom.value + 0.5f) {
                return@repeat
            }
            composeRule.scrollContainer("android_conversation_rail", 40f * density())
            composeRule.waitForIdle()
        }
        val tail = composeRule.bounds("android_response_text")
        val rail = composeRule.bounds("android_conversation_rail")
        val actions = composeRule.bounds("android_action_surface")
        assertTrue(
            "$cell: the last response line must be reachable inside the rail above the composer, " +
                "saw $tail rail=$rail (start $railBox) actions=$actions",
            tail.bottom.value <= rail.bottom.value + 0.5f &&
                tail.bottom.value > rail.top.value &&
                rail.bottom.value <= actions.top.value + 0.5f,
        )

        // Transport lost mid-turn: the unconfirmed turn offers Resend/Discard.
        fixtureRuntime.port.failReconnect = true
        composeRule.runOnIdle {
            fixtureRuntime.port.emit(AndroidNormalizedEvent.Disconnected(binding.connectionId, "The socket closed."))
        }
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        // A settled turn returns focus to the prompt, which raises the real
        // keyboard. This test measures the keyboard-free window (the open
        // keyboard is real_window_keeps_the_composer_above_the_open_keyboard),
        // so the keyboard is closed the way a person closes it: Back.
        val imeAtSettle = imeBottomPx()
        if (imeAtSettle > 0) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent KEYCODE_BACK").close()
            composeRule.waitUntil(10_000) { imeBottomPx() == 0 }
        }
        println(
            "HOME11-WINDOW lost-turn imeAtSettlePx=$imeAtSettle fontScale=${cell.fontScale} " +
                "landscape=${cell.landscape}",
        )
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachRailItem("android_unconfirmed_turn", cell)
        composeRule.reachRailItem("android_connect", cell)
        composeRule.reachRailText(ResendAction, cell)
        composeRule.reachRailText(DiscardAction, cell)
    }

    /**
     * Installs the matrix fixture into the process-scoped runtime box *before*
     * the Activity rule launches `MainActivity`, which resolves the box in
     * `onCreate`.
     */
    private class InstallMatrixRuntime : ExternalResource() {
        val port = LayoutMatrixPort(holdDown = true)

        override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
            super.apply(base, description).also {
                if (description.methodName.orEmpty().contains("authorization")) {
                    port.authorization = AndroidAuthorizationState.Unavailable
                }
            }
        private var installed: HomeRuntime? = null

        fun runtime(): HomeRuntime? = installed

        private var previous: HomeRuntimeBox? = null

        private val application: HermesRelayApplication
            get() = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as HermesRelayApplication

        override fun before() {
            previous = application.homeRuntimeBox
            application.homeRuntimeBox = HomeRuntimeBox { onTornDown ->
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                HomeRuntime(
                    clientPort = port,
                    speechInput = FakeSpeechInput(),
                    historyStore = InMemoryAndroidHistoryStore(),
                    postToMain = { mainHandler.post(it) },
                    workExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                        Thread(runnable, "hermes-real-window-work").apply { isDaemon = true }
                    },
                    onTornDown = onTornDown,
                ).also { installed = it }
            }
        }

        /**
         * Ends the fixture runtime and restores the process box, so the next
         * test class in the same process never inherits this fixture's Home.
         */
        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                installed?.activityDestroyed(isFinishing = true, isChangingConfigurations = false)
            }
            previous?.let { application.homeRuntimeBox = it }
        }
    }
}
