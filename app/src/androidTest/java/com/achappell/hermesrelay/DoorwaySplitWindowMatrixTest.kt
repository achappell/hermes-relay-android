package com.achappell.hermesrelay

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import android.app.Instrumentation
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * `ANDROID-HOME-11` matrix, real multi-window layer.
 *
 * The spec adds "split-screen at 50 % height" to the viewport matrix. A
 * `wm size` override or a synthetic `Box` is not multi-window, so this test
 * enters **real Android multi-window** from inside the running client with
 * `am start --windowingMode` (a companion app in split-primary, the client in
 * split-secondary) and then asserts against the bounds the system actually
 * granted. The driver records `dumpsys activity activities` and screenshots so
 * the two resumed tasks and the ~50 % split are provable outside the test.
 */
@RunWith(AndroidJUnit4::class)
class DoorwaySplitWindowMatrixTest {

    private val fixtureRuntime = InstallSplitRuntime()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fixtureRuntime).around(composeRule)

    @After
    fun leaveSplitScreen() {
        hideKeyboard()
        val task = Regex("com\\.achappell\\.hermesrelay/\\.MainActivity t(\\d+)")
            .find(shell("dumpsys activity activities"))?.groupValues?.get(1)
        if (task != null) shell("dumpsys activity service SystemUIService WMShell splitscreen exitSplitScreen $task")
        shell("am force-stop com.android.settings")
    }

    private fun shell(command: String): String {
        val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }

    /**
     * Leaves no soft keyboard behind: a keyboard left open would make the next
     * test's first Back close the keyboard instead of what that test expects.
     */
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

    private fun density() = composeRule.activity.resources.displayMetrics.density

    /** Saves a real screenshot of the whole display for the evidence record. */
    private fun settleSplitScreenshot(name: String) {
        Thread.sleep(800)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
        java.io.File(dir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun imeBottomPx(): Int =
        ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    /** The physical display, read from the system rather than from the half-window. */
    private fun displayBounds(): Pair<Int, Int> {
        val match = Regex("Physical size: (\\d+)x(\\d+)").find(shell("wm size"))
        val (width, height) = match?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            ?: error("could not read wm size")
        return width to height
    }

    private fun realCell(imeUp: Boolean): DoorwayMatrix.Cell {
        val configuration: Configuration = composeRule.activity.resources.configuration
        val metrics = composeRule.activity.resources.displayMetrics
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DoorwayMatrix.Cell(
            DoorwayMatrix.Display(
                "real-split-${if (landscape) "landscape" else "portrait"}",
                metrics.widthPixels / metrics.density,
                metrics.heightPixels / metrics.density,
            ),
            configuration.fontScale,
            imeUp,
            landscape,
        )
    }

    /**
     * The client really is in a multi-window split: its window is about half the
     * display, the bars stay ordered, and the composer stays reachable — with
     * the real keyboard up as well.
     */
    @Test
    fun split_screen_grants_the_client_half_the_display_and_keeps_the_composer_reachable() {
        // Android 12+ ignores `am start --windowingMode 3/4`; split-screen is owned
        // by WM Shell. Start a companion task, then ask WM Shell to put it in the
        // side stage beside the client: that is the real split the user gets.
        // A cold Settings start can outlast one short wait, so look for its task
        // a few times before giving up.
        var companionTask: String? = null
        var tries = 0
        shell("am force-stop com.android.settings")
        shell("am force-stop com.google.android.settings.intelligence")
        while (companionTask == null && tries < 4) {
            shell("am start -W -n com.android.settings/.Settings")
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(1500)
            shell("am start -n com.achappell.hermesrelay/.MainActivity")
            Thread.sleep(1500)
            companionTask = Regex("Task\\{\\w+ #(\\d+) type=standard A=\\d+:com\\.android\\.settings\\.root")
                .find(shell("dumpsys activity activities"))?.groupValues?.get(1)
            tries += 1
        }
        checkNotNull(companionTask) { "no companion task to split with after $tries starts" }
        shell("dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage $companionTask 0")
        Thread.sleep(3000)
        composeRule.waitForIdle()

        val tasks = shell("dumpsys window")
            .lines().filter { "Task{" in it && "mode=multi-window" in it && "visible=true" in it }
        println("HOME11-SPLIT tasks=" + tasks.joinToString(" || ") { it.trim() })
        assertTrue(
            "real multi-window must show the client and its companion side by side, saw $tasks",
            tasks.any { "hermesrelay" in it } && tasks.any { "settings" in it },
        )

        val (displayWidth, displayHeight) = displayBounds()
        val configuration: Configuration = composeRule.activity.resources.configuration
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // A split window's own configuration orientation describes the window,
        // not the display (a half-height portrait split reads as landscape), so
        // the split axis is whichever display axis the window actually halves.
        val decor = composeRule.activity.window.decorView
        val widthRatio = decor.width.toFloat() / displayWidth.toFloat()
        val heightRatio = decor.height.toFloat() / displayHeight.toFloat()
        val splitAxis = if (heightRatio <= widthRatio) displayHeight else displayWidth
        val windowAxis = if (heightRatio <= widthRatio) decor.height else decor.width
        val ratio = minOf(widthRatio, heightRatio)
        println(
            "HOME11-SPLIT display=${displayWidth}x${displayHeight} window=" +
                "${composeRule.activity.window.decorView.width}x${composeRule.activity.window.decorView.height} " +
                "ratio=$ratio fontScale=${configuration.fontScale} landscape=$landscape",
        )
        println(
            "HOME11-SPLIT dumpsys=" +
                shell("dumpsys activity activities | grep -E 'ResumedActivity|mBounds|windowingMode'"),
        )
        assertTrue(
            "the split window must be about half the display, saw window=${windowAxis}px " +
                "display=${splitAxis}px ratio=$ratio",
            ratio in 0.35f..0.65f,
        )

        val cell = realCell(imeUp = false)
        composeRule.settleGeometry()
        composeRule.assertBarsDoNotOverlap(cell)
        composeRule.reachRailItem("android_doorway_state", cell)
        // Held-down fixture: Recover is asserted in the settled disconnected state.
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        composeRule.reachRailItem("android_connect", cell)
        val before = fixtureRuntime.port.connectAttempts
        composeRule.reachAndClickRail("android_connect", cell)
        composeRule.waitUntil(10_000) { fixtureRuntime.port.connectAttempts > before }
        composeRule.awaitTerminalDisconnect({ fixtureRuntime.runtime() }, cell)
        composeRule.reachAction("android_typed_prompt", cell)
        composeRule.reachAction("android_tap_to_speak", cell)
        // Every menu entry is displayed in the half window, and each sheet
        // entry really opens its sheet there. In a split the companion task
        // can hold input focus, so first give this window focus the way a
        // person does: a real tap on its own header text.
        focusThisWindow()
        openMenu(cell)
        for (entry in MenuEntries) {
            composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed()
        }
        shellBack()
        composeRule.waitForIdle()
        for ((entry, sheet) in SheetEntries) {
            openMenu(cell)
            composeRule.onNodeWithTag(entry).performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag(sheet).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(sheet).assertIsDisplayed()
            shellBack()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag(sheet).fetchSemanticsNodes().isEmpty()
            }
        }

        // The real keyboard inside the split window still leaves the field reachable.
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
            // A real finger tap on the field, through the OS input pipeline, is
            // what gives a split window focus. Retry: the first tap can land
            // while WM Shell is still settling the split.
            var attempt = 0
            while (imeBottomPx() == 0 && attempt < 4) {
                val shown = runCatching { composeRule.waitUntil(4_000) { imeBottomPx() > 0 } }.isSuccess
                if (shown) break
                composeRule.reachAction("android_typed_prompt", cell)
                val field = composeRule.onNodeWithTag("android_typed_prompt").getUnclippedBoundsInRoot()
                val origin = IntArray(2)
                composeRule.runOnIdle { composeRule.activity.window.decorView.getLocationOnScreen(origin) }
                val d = density()
                val x = origin[0] + ((field.left.value + field.right.value) / 2f * d).toInt()
                val y = origin[1] + ((field.top.value + field.bottom.value) / 2f * d).toInt()
                println("HOME11-SPLIT tap=$x,$y origin=${origin[0]},${origin[1]} field=$field attempt=$attempt")
                shell("input tap $x $y")
                attempt += 1
            }
            composeRule.waitUntil(10_000) { imeBottomPx() > 0 }
            ime = imeBottomPx()
        }
        settleSplitScreenshot("split-ime-up")
        println(
            "HOME11-SPLIT ime=${ime}px window=" +
                "${composeRule.activity.window.decorView.width}x${composeRule.activity.window.decorView.height}" +
                " field=" + composeRule.onNodeWithTag("android_typed_prompt").getUnclippedBoundsInRoot() +
                " actions=" + composeRule.onNodeWithTag("android_action_surface").getUnclippedBoundsInRoot() +
                " header=" + composeRule.onNodeWithTag("android_doorway_header").getUnclippedBoundsInRoot(),
        )
        assertTrue("the OS must report a real keyboard inset in the split window, saw ${ime}px", ime > 0)
        // The visible part of the split window is its height less the part of
        // the keyboard that still overlaps it. The focused field and Send must
        // both sit wholly inside it, as the screenshot shows.
        val visibleDp = (composeRule.activity.window.decorView.height - ime) / density()
        val field = composeRule.bounds("android_typed_prompt")
        assertTrue(
            "the focused prompt must stay above the keyboard in the split window, saw " +
                "field=$field visible=0..${visibleDp}dp",
            field.top.value >= -0.5f && field.bottom.value <= visibleDp + 0.5f,
        )
        // Send is reachable by scrolling the composer inside the visible area.
        repeat(20) {
            val current = composeRule.onNodeWithText("Start typed turn").getUnclippedBoundsInRoot()
            if (current.bottom.value <= visibleDp + 0.5f && current.top.value >= -0.5f) return@repeat
            val delta = if (current.bottom.value > visibleDp) 40f * density() else -40f * density()
            composeRule.scrollContainer("android_action_surface", delta)
            composeRule.waitForIdle()
        }
        val send = composeRule.onNodeWithText("Start typed turn").getUnclippedBoundsInRoot()
        assertTrue(
            "Send must be reachable above the keyboard in the split window, saw " +
                "send=$send visible=0..${visibleDp}dp",
            send.top.value >= -0.5f && send.bottom.value <= visibleDp + 0.5f,
        )
    }

    /** A real Back key through the OS, delivered to the window that holds focus. */
    private fun shellBack() {
        shell("input keyevent KEYCODE_BACK")
        composeRule.waitForIdle()
    }

    private fun focusThisWindow() {
        // The header is the top of the doorway: bring it into this window so
        // the tap lands on this window's own header text, never on the
        // companion task.
        composeRule.reachHeaderControl("android_more_menu", realCell(imeUp = false))
        val header = composeRule.onNodeWithTag("android_doorway_header").getUnclippedBoundsInRoot()
        check(header.top.value >= 0f) { "the header must be inside this window before the focus tap, saw $header" }
        val origin = IntArray(2)
        composeRule.runOnIdle { composeRule.activity.window.decorView.getLocationOnScreen(origin) }
        val d = density()
        val x = origin[0] + (16f * d).toInt()
        val y = origin[1] + ((header.top.value + 8f) * d).toInt()
        shell("input tap $x $y")
        composeRule.waitUntil(10_000) { composeRule.activity.hasWindowFocus() }
    }

    private fun openMenu(cell: DoorwayMatrix.Cell) {
        composeRule.reachHeaderControl("android_more_menu", cell)
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("android_menu_conversations").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** The disconnected split fixture offers every entry but Disconnect. */
        val MenuEntries = listOf(
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
