package com.achappell.hermesrelay

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * `ANDROID-HOME-11` matrix, real-window layer.
 *
 * Runs the real `MainActivity` on a real AVD: the real window, real status and
 * navigation bars, the real OS keyboard. Only the Home behind the screen is a
 * fixture (`HermesRelayApplication.homeRuntimeBox`). The driver passes the
 * declared row as instrumentation arguments and every test first asserts the
 * running font scale, rotation, navigation mode and display match it.
 */
@RunWith(AndroidJUnit4::class)
class DoorwayRealWindowMatrixTest {

    private val fixtureRuntime = InstallMatrixRuntime()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    // A fresh install's first turn asks for notifications; that system dialog
    // would cover the window under test. Decide it up front, as the existing
    // suite does.
    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(
        *if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fixtureRuntime).around(composeRule)

    private lateinit var row: DeclaredRow

    @Before
    fun declareRow() {
        row = assertDeclaredRow(composeRule.activity)
        installUnobscuredBand(composeRule, composeRule.activity)
    }

    /** Leaves no keyboard behind for the next test, and no band. */
    @After
    fun leaveNoKeyboard() {
        unobscuredBand = null
        closeKeyboardBeforeFinishing(composeRule.activity)
    }

    private fun hideKeyboard() {
        if (ime() == 0) return
        composeRule.runOnIdle {
            val activity = composeRule.activity
            activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitUntil(10_000) { ime() == 0 }
    }

    private fun ime() = imeBottomPx(composeRule.activity)

    private fun window() = composeRule.activity.window.decorView.let { "${it.width}x${it.height}" }

    /** The cell descriptor for the window the OS actually gave this Activity. */
    private fun realCell(imeUp: Boolean): DoorwayMatrix.Cell {
        val configuration: Configuration = composeRule.activity.resources.configuration
        val metrics = composeRule.activity.resources.displayMetrics
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DoorwayMatrix.Cell(
            DoorwayMatrix.Display(
                "${row.row}-${row.navigation}-${if (landscape) "landscape" else "portrait"}",
                metrics.widthPixels / metrics.density,
                metrics.heightPixels / metrics.density,
            ),
            configuration.fontScale,
            imeUp,
            landscape,
        )
    }

    private fun openMenu(cell: DoorwayMatrix.Cell) {
        composeRule.reachHeaderControl("android_more_menu", cell)
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("android_menu_conversations").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Keyboard down: ordered regions; Recover in the settled disconnected state
     * with a real press reaching the client; then connected: composer,
     * tap-to-speak, every menu entry and every sheet.
     */
    @Test
    fun real_window_keeps_every_control_reachable_without_the_keyboard() {
        assertTrue("the fixture must start without a keyboard, saw ${ime()}px", ime() == 0)
        val cell = realCell(imeUp = false)
        println("HOME11-WINDOW no-ime window=${window()} fontScale=${cell.fontScale} landscape=${cell.landscape}")
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachRailItem("android_doorway_state", cell)

        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        composeRule.reachRailItem("android_connect", cell)
        composeRule.onAllNodesWithTag("android_connect", useUnmergedTree = true)[0].assertIsEnabled()
        val before = fixtureRuntime.port.connectAttempts
        composeRule.reachAndClickRail("android_connect", cell)
        composeRule.waitUntil(10_000) { fixtureRuntime.port.connectAttempts > before }
        composeRule.connectFixture(fixtureRuntime.port, { fixtureRuntime.runtime() }, cell)

        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachAction("android_typed_prompt", cell)
        composeRule.reachAction("android_tap_to_speak", cell)
        openMenu(cell)
        for (entry in MenuEntries) {
            composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed()
        }
        pressBack()
        composeRule.waitForIdle()
        for ((entry, sheet) in SheetEntries) {
            openMenu(cell)
            composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(sheet).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag(sheet).assertIsDisplayed()
            pressBack()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(sheet).fetchSemanticsNodes().isEmpty() }
        }
    }

    /**
     * Keyboard up, connected: the prompt, Send and the idle doorway state, then
     * — after a completed 40-line turn — the newest response line, the prompt
     * and Send each come into view above the real keyboard by real drags,
     * clear of every bar and region.
     */
    @Test
    fun real_window_keeps_the_composer_and_newest_line_above_the_open_keyboard() {
        val cell = realCell(imeUp = true)
        composeRule.connectFixture(fixtureRuntime.port, { fixtureRuntime.runtime() }, cell)
        composeRule.raiseKeyboard(composeRule.activity, cell)
        composeRule.settleGeometry()
        println(
            "HOME11-WINDOW ime-up window=${window()} imePx=${ime()} fontScale=${cell.fontScale} " +
                "landscape=${cell.landscape}",
        )
        val activity = composeRule.activity
        fun field() = composeRule.revealAboveKeyboard(
            activity, "the prompt field", "android_action_surface", RevealEdge.Whole, true,
        ) { composeRule.onAllNodesWithTag("android_typed_prompt", useUnmergedTree = true).fetchSemanticsNodes() }
        fun send() = composeRule.revealAboveKeyboard(activity, "Send", "android_action_surface", RevealEdge.Whole, true) {
            composeRule.onAllNodesWithText(SendAction, useUnmergedTree = true).fetchSemanticsNodes()
        }
        // Idle and connected: the doorway state card is shown (it gives way to
        // the turn while one is running).
        field()
        send()
        composeRule.revealAboveKeyboard(activity, "the doorway state", "android_conversation_rail", RevealEdge.Whole, false) {
            composeRule.onAllNodesWithTag("android_doorway_state", useUnmergedTree = true).fetchSemanticsNodes()
        }
        // A completed 40-line turn: its newest line.
        composeRule.sendLongTurn(activity, fixtureRuntime.port, cell, complete = true)
        composeRule.raiseKeyboard(activity, cell)
        composeRule.settleGeometry()
        composeRule.revealAboveKeyboard(
            activity, "the newest response line", "android_conversation_rail", RevealEdge.Bottom, true,
        ) {
            composeRule.onAllNodesWithTag("android_response_text", useUnmergedTree = true).fetchSemanticsNodes()
        }
        field()
        send()
        assertTrue("the keyboard must still be up", keyboardShown(composeRule.activity))
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
    }

    /**
     * Authorization unavailable, long Profile name: the status block, Edit
     * relay, the composer and the menu are reachable; Recover is correctly
     * absent. The status block's line count is asserted where the row
     * declares a minimum and logged everywhere.
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
        composeRule.reachRailText(StatusBlock, cell)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onAllNodesWithText(StatusBlock, substring = true, useUnmergedTree = true)[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val lines = checkNotNull(layouts.firstOrNull()) { "$cell: the status block has no text layout" }.lineCount
        println("HOME11-WINDOW status-block lines=$lines fontScale=${cell.fontScale} landscape=${cell.landscape}")
        row.statusLinesMin?.let { minimum ->
            assertTrue("$cell: the status block must render at least $minimum lines, saw $lines", lines >= minimum)
        }
        composeRule.reachRailItem("android_edit_relay", cell)
        assertTrue(
            "$cell: Recover must not be offered while authorization is blocked",
            composeRule.onAllNodesWithTag("android_connect", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
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
     * Keyboard down, a real typed turn: phase and Interrupt; the newest item
     * reachable by the rail alone whenever the doorway fits; the response's first
     * and last lines reachable in the rail; after a mid-turn transport loss,
     * the unconfirmed turn, Recover, Resend and Discard.
     */
    @Test
    fun real_window_keeps_turn_controls_and_the_whole_transcript_reachable() {
        val cell = realCell(imeUp = false)
        composeRule.connectFixture(fixtureRuntime.port, { fixtureRuntime.runtime() }, cell)
        val binding = composeRule.sendLongTurn(composeRule.activity, fixtureRuntime.port, cell)
        hideKeyboard()
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.assertNewestItemReachableWhenFitting(cell)
        composeRule.reachRailItem("android_turn_phase", cell)
        composeRule.reachRailItem("android_interrupt", cell)
        composeRule.onNodeWithTag("android_interrupt").assertIsEnabled()

        // First line: the response's top is reachable inside the rail.
        composeRule.reachRailItem("android_response_text", cell)
        // Last line: stepping the rail brings the response's bottom edge inside
        // the visible rail, above the composer.
        val step = 40f * composeRule.activity.resources.displayMetrics.density
        var steps = 0
        while (steps < TranscriptSteps &&
            composeRule.bounds("android_response_text").bottom > composeRule.bounds("android_conversation_rail").bottom
        ) {
            composeRule.scrollContainer("android_conversation_rail", step)
            composeRule.waitForIdle()
            steps += 1
        }
        val tail = composeRule.bounds("android_response_text")
        val rail = composeRule.bounds("android_conversation_rail")
        val actions = composeRule.bounds("android_action_surface")
        assertTrue(
            "$cell: the last response line must be reachable inside the rail above the composer, " +
                "saw $tail rail=$rail actions=$actions after $steps steps",
            tail.bottom.value <= rail.bottom.value + 0.5f && tail.bottom.value > rail.top.value &&
                rail.bottom.value <= actions.top.value + 0.5f,
        )

        fixtureRuntime.port.failReconnect = true
        composeRule.runOnIdle {
            fixtureRuntime.port.emit(AndroidNormalizedEvent.Disconnected(binding.connectionId, "The socket closed."))
        }
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        // A settled turn returns focus to the prompt and raises the keyboard;
        // this test measures the keyboard-free window, so close it with a real
        // Back key as a person would.
        composeRule.waitForIdle()
        val imeAtSettle = ime()
        if (imeAtSettle > 0) {
            shell("input keyevent KEYCODE_BACK")
            composeRule.waitUntil(10_000) { ime() == 0 }
        }
        println("HOME11-WINDOW lost-turn imeAtSettlePx=$imeAtSettle fontScale=${cell.fontScale} landscape=${cell.landscape}")
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachRailItem("android_unconfirmed_turn", cell)
        composeRule.reachRailItem("android_connect", cell)
        composeRule.reachRailText(ResendAction, cell)
        composeRule.reachRailText(DiscardAction, cell)
    }

    private companion object {
        const val StatusBlock = "This Profile is unavailable until relay authorization"
        const val ResendAction = "Resend this turn"
        const val DiscardAction = "Discard this turn"
        const val TranscriptSteps = 400
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
