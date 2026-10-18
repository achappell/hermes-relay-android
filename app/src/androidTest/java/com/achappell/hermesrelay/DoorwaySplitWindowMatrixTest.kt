package com.achappell.hermesrelay

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * `ANDROID-HOME-11` matrix, real multi-window layer.
 *
 * Android 12+ owns split-screen in WM Shell, so the test starts a companion
 * task and asks WM Shell to put it in the side stage beside the client: the
 * split a person gets. The granted window is asserted on the axis the display
 * actually splits — height on a portrait display (the spec's 50 %-height
 * cells), width on a landscape display (recorded as half-width cells).
 */
@RunWith(AndroidJUnit4::class)
class DoorwaySplitWindowMatrixTest {

    private val fixtureRuntime = InstallSplitRuntime()
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

    /** Leaves no keyboard and no split behind, and proves the split is gone. */
    @After
    fun leaveSplitScreen() {
        unobscuredBand = null
        closeKeyboardBeforeFinishing(composeRule.activity)
        val task = Regex("com\\.achappell\\.hermesrelay/\\.MainActivity t(\\d+)")
            .find(shell("dumpsys activity activities"))?.groupValues?.get(1)
        if (task != null) shell("dumpsys activity service SystemUIService WMShell splitscreen exitSplitScreen $task")
        shell("am force-stop com.android.settings")
        val exited = runCatching { composeRule.waitUntil(10_000) { multiWindowTasks().isEmpty() } }.isSuccess
        if (!exited) shell("am force-stop com.achappell.hermesrelay")
        check(multiWindowTasks().isEmpty()) { "split-screen did not exit: ${multiWindowTasks()}" }
    }

    private fun multiWindowTasks() = shell("dumpsys window").lines()
        .filter { "Task{" in it && "mode=multi-window" in it && "visible=true" in it }

    private fun ime() = imeBottomPx(composeRule.activity)

    private fun realCell(imeUp: Boolean): DoorwayMatrix.Cell {
        val configuration: Configuration = composeRule.activity.resources.configuration
        val metrics = composeRule.activity.resources.displayMetrics
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DoorwayMatrix.Cell(
            DoorwayMatrix.Display(
                "${row.row}-split-${row.navigation}-rot${row.rotation}",
                metrics.widthPixels / metrics.density,
                metrics.heightPixels / metrics.density,
            ),
            configuration.fontScale,
            imeUp,
            landscape,
        )
    }

    /** Saves a real screenshot of the whole display for the evidence record. */
    private fun screenshot(name: String) {
        composeRule.settleGeometry()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        java.io.File(dir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun enterSplit() {
        shell("am force-stop com.android.settings")
        shell("am force-stop com.google.android.settings.intelligence")
        var companionTask: String? = null
        var tries = 0
        while (companionTask == null && tries < CompanionStarts) {
            shell("am start -W -n com.android.settings/.Settings")
            shell("am start -W -n com.achappell.hermesrelay/.MainActivity")
            companionTask = runCatching {
                var found: String? = null
                composeRule.waitUntil(5_000) {
                    found = Regex("Task\\{\\w+ #(\\d+) type=standard A=\\d+:com\\.android\\.settings\\.root")
                        .find(shell("dumpsys activity activities"))?.groupValues?.get(1)
                    found != null
                }
                found
            }.getOrNull()
            tries += 1
        }
        checkNotNull(companionTask) { "no companion task to split with after $tries starts" }
        shell("dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage $companionTask 0")
        composeRule.waitUntil(10_000) {
            val tasks = multiWindowTasks()
            tasks.any { "hermesrelay" in it } && tasks.any { "settings" in it }
        }
        composeRule.settleGeometry()
        println("HOME11-SPLIT tasks=" + multiWindowTasks().joinToString(" || ") { it.trim() })
    }

    /** Asserts the granted window halves the display on the axis Android splits. */
    private fun assertSplitAxis(): String {
        val activity = composeRule.activity
        val display = activity.windowManager.maximumWindowMetrics.bounds
        val decor = activity.window.decorView
        val heightAxis = display.height() > display.width()
        val split = if (heightAxis) decor.height.toFloat() / display.height() else decor.width.toFloat() / display.width()
        val other = if (heightAxis) decor.width.toFloat() / display.width() else decor.height.toFloat() / display.height()
        val axis = if (heightAxis) "height" else "width"
        println(
            "HOME11-SPLIT axis=$axis display=${display.width()}x${display.height()} " +
                "window=${decor.width}x${decor.height} splitRatio=$split otherRatio=$other " +
                "fontScale=${activity.resources.configuration.fontScale}",
        )
        assertTrue("the split window must be about half the display's $axis, saw $split", split in 0.35f..0.65f)
        assertTrue("the split window must keep the display's other axis, saw $other", other >= 0.9f)
        return axis
    }

    /** Gives this half of the split input focus with a real tap on its header text. */
    private fun focusThisWindow(cell: DoorwayMatrix.Cell) {
        composeRule.reachHeaderControl("android_more_menu", cell)
        val header = composeRule.onNodeWithTag("android_doorway_header").getUnclippedBoundsInRoot()
        check(header.top.value >= 0f) { "the header must be inside this window before the focus tap, saw $header" }
        val origin = IntArray(2)
        composeRule.runOnIdle { composeRule.activity.window.decorView.getLocationOnScreen(origin) }
        val density = composeRule.activity.resources.displayMetrics.density
        val x = origin[0] + (16f * density).toInt()
        // The middle of the header's left edge is plain text, clear of the
        // status bar and of the menu button.
        val y = origin[1] + ((header.top.value + header.bottom.value) / 2f * density).toInt()
        repeat(FocusTaps) {
            if (composeRule.activity.hasWindowFocus()) return
            shell("input tap $x $y")
            runCatching { composeRule.waitUntil(4_000) { composeRule.activity.hasWindowFocus() } }
        }
        check(composeRule.activity.hasWindowFocus()) {
            "this half of the split never took input focus after $FocusTaps taps at $x,$y; " +
                shell("dumpsys window").lines().filter { "mCurrentFocus" in it || "mFocusedApp" in it }
                    .joinToString(" ") { it.trim() }
        }
    }

    private fun openMenu(cell: DoorwayMatrix.Cell) {
        composeRule.reachHeaderControl("android_more_menu", cell)
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("android_menu_conversations").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A real Back key through the OS, delivered to the window holding focus. */
    private fun shellBack() {
        shell("input keyevent KEYCODE_BACK")
        composeRule.waitForIdle()
    }

    /**
     * In a real split: the axis; disconnected — ordered regions, state, Recover
     * (a real press reaches the client), composer, every disconnected menu
     * entry and sheet; connected — the menu's Disconnect; keyboard up with a
     * streaming 40-line turn — prompt, Send, state and the newest line above
     * the real keyboard, clear of every bar and region.
     */
    @Test
    fun split_screen_grants_the_client_half_the_display_and_keeps_the_composer_reachable() {
        enterSplit()
        val axis = assertSplitAxis()
        val cell = realCell(imeUp = false)
        println("HOME11-SPLIT cell=$cell axis=$axis")
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachRailItem("android_doorway_state", cell)
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        composeRule.reachRailItem("android_connect", cell)
        val before = fixtureRuntime.port.connectAttempts
        composeRule.reachAndClickRail("android_connect", cell)
        composeRule.waitUntil(10_000) { fixtureRuntime.port.connectAttempts > before }
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        composeRule.reachAction("android_typed_prompt", cell)
        composeRule.reachAction("android_tap_to_speak", cell)

        focusThisWindow(cell)
        openMenu(cell)
        for (entry in DisconnectedMenu) composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed()
        shellBack()
        for ((entry, sheet) in SheetEntries) {
            openMenu(cell)
            composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(sheet).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag(sheet).assertIsDisplayed()
            shellBack()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(sheet).fetchSemanticsNodes().isEmpty() }
        }

        // Connected phase: the menu's Disconnect entry.
        composeRule.connectFixture(fixtureRuntime.port, { fixtureRuntime.runtime() }, cell)
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        openMenu(cell)
        composeRule.onNodeWithTag("android_menu_disconnect").performScrollTo().assertIsDisplayed()
        shellBack()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("android_menu_disconnect").fetchSemanticsNodes().isEmpty()
        }

        // Keyboard phase, with a streaming 40-line turn.
        val imeCell = realCell(imeUp = true)
        val activity = composeRule.activity
        composeRule.raiseKeyboard(activity, imeCell)
        println("HOME11-SPLIT ime=${ime()}px window=${activity.window.decorView.let { "${it.width}x${it.height}" }}")
        fun field() = composeRule.revealAboveKeyboard(
            activity, "the prompt field", "android_action_surface", RevealEdge.Whole, true,
        ) { composeRule.onAllNodesWithTag("android_typed_prompt", useUnmergedTree = true).fetchSemanticsNodes() }
        fun send() = composeRule.revealAboveKeyboard(activity, "Send", "android_action_surface", RevealEdge.Whole, true) {
            composeRule.onAllNodesWithText(SendAction, useUnmergedTree = true).fetchSemanticsNodes()
        }
        field()
        send()
        // The idle doorway state card (it gives way to the turn while one runs).
        composeRule.revealAboveKeyboard(activity, "the doorway state", "android_conversation_rail", RevealEdge.Whole, false) {
            composeRule.onAllNodesWithTag("android_doorway_state", useUnmergedTree = true).fetchSemanticsNodes()
        }
        composeRule.sendLongTurn(activity, fixtureRuntime.port, imeCell, complete = true)
        composeRule.raiseKeyboard(activity, imeCell)
        screenshot("split-ime-up")
        composeRule.revealAboveKeyboard(
            activity, "the newest response line", "android_conversation_rail", RevealEdge.Bottom, true,
        ) {
            composeRule.onAllNodesWithTag("android_response_text", useUnmergedTree = true).fetchSemanticsNodes()
        }
        field()
        send()
        assertTrue("the keyboard must still be up", keyboardShown(composeRule.activity))
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(imeCell)
    }

    private companion object {
        const val CompanionStarts = 4
        const val FocusTaps = 3
        val DisconnectedMenu = listOf(
            "android_menu_configure_relay",
            "android_menu_conversations",
            "android_menu_approvals",
            "android_menu_history",
        )
        val SheetEntries = listOf(
            "android_menu_conversations" to "android_conversations_sheet",
            "android_menu_approvals" to "android_approvals_sheet",
            "android_menu_history" to "android_history_sheet",
        )
    }

    private class InstallSplitRuntime : ExternalResource() {
        val port = LayoutMatrixPort(holdDown = true)
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
                        Thread(runnable, "hermes-split-work").apply { isDaemon = true }
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
