package com.achappell.hermesrelay

import android.app.Activity
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlin.math.abs

/**
 * The matrix row the driver configured this run for, passed as instrumentation
 * arguments. Every real-window test checks the running device against it, so a
 * configuration the driver failed to apply fails the cell instead of passing
 * as a relabelled copy of another cell.
 */
internal data class DeclaredRow(
    val row: String,
    val fontScale: Float,
    val rotation: Int,
    val navigation: String,
    val naturalDisplay: String,
    val statusLinesMin: Int?,
) {
    companion object {
        fun fromArguments(): DeclaredRow {
            val args = InstrumentationRegistry.getArguments()
            fun required(key: String) = checkNotNull(args.getString(key)) {
                "matrix row not declared: instrumentation argument $key is missing"
            }
            return DeclaredRow(
                row = required("home11_row"),
                fontScale = required("home11_font").toFloat(),
                rotation = required("home11_rotation").toInt(),
                navigation = required("home11_nav"),
                naturalDisplay = required("home11_display"),
                statusLinesMin = args.getString("home11_status_lines_min")?.toInt(),
            )
        }
    }
}

/** Secure-settings value Android uses for each navigation mode. */
private val NavigationModes = mapOf("threebutton" to 0, "gestural" to 2)

/**
 * Asserts the running window is the declared row: font scale, display rotation,
 * navigation mode and the device's natural display size.
 */
internal fun assertDeclaredRow(activity: Activity): DeclaredRow {
    val row = DeclaredRow.fromArguments()
    val configuration: Configuration = activity.resources.configuration
    check(abs(configuration.fontScale - row.fontScale) < 0.01f) {
        "${row.row}: declared font scale ${row.fontScale}, running ${configuration.fontScale}"
    }
    val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    check(display.rotation == row.rotation) {
        "${row.row}: declared rotation ${row.rotation}, running ${display.rotation}"
    }
    val navigation = Settings.Secure.getInt(activity.contentResolver, "navigation_mode", -1)
    val expectedNavigation = checkNotNull(NavigationModes[row.navigation]) {
        "${row.row}: unknown navigation mode ${row.navigation}"
    }
    check(navigation == expectedNavigation) {
        "${row.row}: declared navigation ${row.navigation} ($expectedNavigation), running $navigation"
    }
    val mode = display.mode
    val natural = "${minOf(mode.physicalWidth, mode.physicalHeight)}x${maxOf(mode.physicalWidth, mode.physicalHeight)}"
    val declared = row.naturalDisplay.split("x").map { it.toInt() }.let { "${it.min()}x${it.max()}" }
    check(natural == declared) { "${row.row}: declared display ${row.naturalDisplay}, running $natural" }
    println(
        "HOME11-ROW row=${row.row} font=${configuration.fontScale} rotation=${display.rotation} " +
            "nav=${row.navigation}($navigation) display=$natural",
    )
    return row
}

internal fun shell(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        .also { descriptor.close() }
}

/**
 * Whether the real keyboard is showing. In a top/bottom split, WM Shell moves
 * the lower stage up above the keyboard, so that window gets no IME inset at
 * all while the keyboard is up; the input method service is the authority.
 */
internal fun keyboardShown(activity: Activity): Boolean =
    imeBottomPx(activity) > 0 || shell("dumpsys input_method").lines().any { it.trim() == "mInputShown=true" }

/**
 * Closes the keyboard and waits until the input method service reports it
 * hidden. A test whose Activity finishes with the keyboard up (a settled turn
 * refocuses the prompt) leaves the show request to the next window the process
 * opens: that window's first Back is then taken by the keyboard's back handler
 * instead of reaching its own (seen as ModalSheetBackTest's Back missing the
 * sheet after such a class).
 */
internal fun closeKeyboardBeforeFinishing(activity: Activity) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Let any pending frame run first, so a refocus the screen has already
    // scheduled issues its keyboard request before this clears it.
    instrumentation.waitForIdleSync()
    instrumentation.runOnMainSync {
        activity.currentFocus?.clearFocus()
        // The screen asks for the keyboard through the insets API, which
        // records the window's requested visibility; only the same API clears
        // that request, the input method manager hide only hides the window.
        androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }
    val deadline = System.currentTimeMillis() + KeyboardCloseMillis
    while (System.currentTimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        if (shell("dumpsys input_method").lines().none { it.trim() == "mInputShown=true" }) return
    }
    error("the keyboard was still showing ${KeyboardCloseMillis}ms after it was closed")
}

internal fun imeBottomPx(activity: Activity): Int =
    ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

/**
 * Installs the window's unobscured band (below the status bar, above the
 * navigation bar and the keyboard) for [assertClearOfBars], read live from the
 * real window insets.
 */
internal fun installUnobscuredBand(rule: ComposeContentTestRule, activity: Activity) {
    unobscuredBand = {
        var band: DpRect? = null
        rule.runOnIdle {
            val decor = activity.window.decorView
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor)) { "the window has no insets" }
            val density = activity.resources.displayMetrics.density
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            band = DpRect(
                (bars.left / density).dp,
                (bars.top / density).dp,
                ((decor.width - bars.right) / density).dp,
                ((decor.height - maxOf(bars.bottom, ime.bottom)) / density).dp,
            )
        }
        checkNotNull(band)
    }
}

/** Which part of a node must come into view above the keyboard. */
internal enum class RevealEdge { Whole, Bottom }

/**
 * Reaches a node with the keyboard up the way a person does: real finger drags
 * (`input swipe`, in screen coordinates of this window) inside the part of the
 * window the bars and keyboard leave, until the node — or its bottom edge — is
 * visible there. Then the visible part must be clear of the other regions.
 */
internal fun ComposeContentTestRule.revealAboveKeyboard(
    activity: Activity,
    label: String,
    ownRegion: String,
    edge: RevealEdge,
    preferUp: Boolean,
    nodes: () -> List<SemanticsNode>,
) {
    val density = activity.resources.displayMetrics.density
    fun band(): Pair<Float, Float> {
        val dp = checkNotNull(unobscuredBand) { "$label: the unobscured band is not installed" }()
        return dp.top.value * density to dp.bottom.value * density
    }
    /** The part of [ownRegion] a person can see: its visible bounds cut to the band. */
    fun regionSpan(): Pair<Float, Float>? {
        val (top, bottom) = band()
        val region = onAllNodesWithTag(ownRegion).fetchSemanticsNodes().firstOrNull() ?: return null
        val r = region.boundsInWindow
        val spanTop = maxOf(r.top, top)
        val spanBottom = minOf(r.bottom, bottom)
        return if (spanBottom > spanTop) spanTop to spanBottom else null
    }
    fun shown(node: SemanticsNode): Boolean {
        if (!node.layoutInfo.isPlaced) return false
        val (top, bottom) = band()
        val clipped = node.boundsInWindow
        val fullBottom = node.positionInWindow.y + node.size.height
        if (clipped.height <= 0f || clipped.top < top - 1f || clipped.bottom > bottom + 1f) return false
        return when (edge) {
            RevealEdge.Bottom -> abs(clipped.bottom - fullBottom) <= 1f
            // Wholly visible, or — for a node taller than the part of its
            // region a person can see — filling that part.
            RevealEdge.Whole -> {
                val span = regionSpan()?.let { it.second - it.first } ?: 0f
                clipped.height >= minOf(node.size.height.toFloat(), span) - 1f
            }
        }
    }
    fun found(): SemanticsNode? = nodes().firstOrNull(::shown)
    val origin = IntArray(2)
    runOnIdle { activity.window.decorView.getLocationOnScreen(origin) }
    val x = origin[0] + activity.window.decorView.width / 2
    fun drag(rawFrom: Float, rawTo: Float, millis: Int = DragMillis) {
        // Keep every stroke well inside the band: a stroke that starts at the
        // status bar opens the notification shade, and one that ends on the
        // keyboard's toolbar presses its buttons.
        val (bandTop, bandBottom) = band()
        // Nor inside the system gesture areas: a stroke that starts there is
        // Home or Back on gesture navigation, not a scroll.
        var gestureTop = 0
        var gestureBottom = 0
        runOnIdle {
            val decor = activity.window.decorView
            val gestures = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.systemGestures())
            gestureTop = gestures?.top ?: 0
            gestureBottom = decor.height - (gestures?.bottom ?: 0)
        }
        val margin = EdgeMarginDp * density
        val top = maxOf(bandTop, gestureTop.toFloat()) + margin
        val bottom = minOf(bandBottom, gestureBottom.toFloat()) - margin
        if (bottom - top < 8f * density) return
        val fromY = rawFrom.coerceIn(top, bottom)
        val toY = rawTo.coerceIn(top, bottom)
        if (abs(toY - fromY) < 4f * density) return
        // The stroke must land on this Activity: never on whatever replaced it.
        check(activity.hasWindowFocus() || keyboardShown(activity)) {
            "$label: this window lost focus before a stroke; refusing to stroke another app"
        }
        val command = "input swipe $x ${origin[1] + fromY.toInt()} $x ${origin[1] + toY.toInt()} $millis"
        println("HOME11-STROKE $label $command band=$bandTop..$bandBottom gestures=$gestureTop..$gestureBottom")
        // Wait for the stroke to finish: closing the command's stream returns
        // at once while `input swipe` is still running, so strokes would
        // overlap each other and the reads below, and still-queued strokes
        // would run after the test ended (on the launcher).
        shell(command)
        // A drag with the keyboard up can leave the test clock reading two
        // alternating layouts (the focused field asking to be brought into
        // view against the drag); the real display was checked to stay still.
        // Visibility below is therefore required on consecutive reads.
        awaitStableGeometry(DragSettleMillis)?.let { println("HOME11-WINDOW unsettled-after-drag $label: $it") }
    }
    fun scrollPositions(): String {
        val region = onAllNodesWithTag(ownRegion).fetchSemanticsNodes().firstOrNull()
        val inner = region?.let { r ->
            (listOf(r) + r.children).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
        }?.value()
        return "$inner/" + (outerScrollPosition() ?: "none")
    }
    var swipes = 0
    /** Strokes the doorway as a whole until a readable part of the region shows. */
    fun bringRegion() {
        repeat(RegionStrokes) {
            val (top, bottom) = band()
            val span = regionSpan()
            val regionNode = onAllNodesWithTag(ownRegion).fetchSemanticsNodes().firstOrNull()
            val regionVisible = span?.let { it.second - it.first } ?: 0f
            val wanted = minOf(regionNode?.size?.height?.toFloat() ?: 0f, (bottom - top) * 0.4f) - 1f
            if (span != null && regionVisible >= wanted) return
            val regionBelow = (regionNode?.positionInWindow?.y ?: bottom) > top
            val distance = (bottom - top) * 0.5f
            if (regionBelow) drag(bottom, bottom - distance) else drag(top, top + distance)
            swipes += 1
        }
    }
    /**
     * One finger stroke that starts on the region's visible part (so the
     * region, not the doorway, takes it) and travels across the band; up
     * moves content up.
     */
    fun stroke(up: Boolean, millis: Int) {
        bringRegion()
        val (top, bottom) = band()
        val span = regionSpan() ?: (top to bottom)
        val distance = (bottom - top) * 0.5f
        val start = if (up) span.second - 2f * density else span.first + 2f * density
        drag(start, if (up) start - distance else start + distance, millis)
        swipes += 1
    }
    // 1. Bring the region itself into the band.
    bringRegion()
    // A bottom edge is the region's far end: the region's own scroll action
    // takes it there directly (the doorway stays where the drags left it).
    if (edge == RevealEdge.Bottom && found() == null) {
        scrollContainer(ownRegion, HugeScrollPx)
        awaitStableGeometry(DragSettleMillis)
        bringRegion()
    }
    // 2. A composed node with a known position: stroke toward its hidden side.
    repeat(DirectStrokes) {
        if (found() != null) return@repeat
        val span = regionSpan() ?: return@repeat
        val node = nodes().firstOrNull()?.takeIf { it.size.height > 0 } ?: return@repeat
        val nodeTop = node.positionInWindow.y
        val nodeBottom = nodeTop + node.size.height
        val up = when (edge) {
            RevealEdge.Bottom -> nodeBottom > span.second
            RevealEdge.Whole -> if (nodeTop < span.first - 1f) false else nodeBottom > span.second
        }
        // Far targets get a fling, near ones a slow stroke, as a person would.
        val gap = if (up) nodeBottom - span.second else span.first - nodeTop
        val (bandTop, bandBottom) = band()
        stroke(up, if (gap > (bandBottom - bandTop) * FlingGapBands) FlingMillis else DragMillis)
    }
    // 3. Otherwise sweep the whole region as a person would: fling to the
    //    preferred end, then stroke back slowly to the other end, and again.
    fun sweep(up: Boolean, millis: Int, check: Boolean): Boolean {
        var unchanged = 0
        repeat(SweepStrokes) {
            if (check && found() != null) return true
            val before = scrollPositions()
            stroke(up, millis)
            unchanged = if (scrollPositions() == before) unchanged + 1 else 0
            if (unchanged >= 2) return check && found() != null
        }
        return check && found() != null
    }
    if (found() == null) {
        sweep(preferUp, FlingMillis, check = true) ||
            sweep(!preferUp, DragMillis, check = true) ||
            sweep(preferUp, DragMillis, check = true)
    }
    var node = found()
    repeat(ConsecutiveReads - 1) {
        waitForIdle()
        if (node != null) node = found()
    }
    check(node != null) {
        "$label must come into view above the keyboard after $swipes real drags, " +
            "saw ${nodes().map { it.boundsInWindow }} region=${regionSpan()} band=${band()} ime=${imeBottomPx(activity)}px"
    }
    assertClearOfBars(ownRegion, node, label)
}

/** Raises the real keyboard on the prompt field (a real tap if focus alone does not). */
internal fun ComposeContentTestRule.raiseKeyboard(activity: Activity, cell: DoorwayMatrix.Cell) {
    if (keyboardShown(activity)) return
    reachAction("android_typed_prompt", cell)
    onNodeWithTag("android_typed_prompt").performClick()
    if (runCatching { waitUntil(4_000) { keyboardShown(activity) } }.isSuccess) return logKeyboard(activity)
    // A window that does not hold input focus (the other half of a split) only
    // takes it from a real finger tap through the OS input pipeline.
    repeat(KeyboardTaps) { attempt ->
        reachAction("android_typed_prompt", cell)
        val node = onNodeWithTag("android_typed_prompt").fetchSemanticsNode()
        val origin = IntArray(2)
        runOnIdle { activity.window.decorView.getLocationOnScreen(origin) }
        val centre = node.boundsInWindow.center
        val x = origin[0] + centre.x.toInt()
        val y = origin[1] + centre.y.toInt()
        println("HOME11-WINDOW keyboard-tap=$x,$y origin=${origin[0]},${origin[1]} attempt=$attempt")
        shell("input tap $x $y")
        if (runCatching { waitUntil(4_000) { keyboardShown(activity) } }.isSuccess) return logKeyboard(activity)
    }
    check(keyboardShown(activity)) { "$cell: the real keyboard never appeared after $KeyboardTaps taps" }
}

private fun ComposeContentTestRule.logKeyboard(activity: Activity) {
    settleGeometry()
    val origin = IntArray(2)
    runOnIdle { activity.window.decorView.getLocationOnScreen(origin) }
    println("HOME11-WINDOW keyboard-up imePx=${imeBottomPx(activity)} windowOrigin=${origin[0]},${origin[1]}")
}

/** The fixture is released and a real press on Recover connects the client. */
internal fun ComposeContentTestRule.connectFixture(
    port: LayoutMatrixPort,
    runtime: () -> HomeRuntime?,
    cell: DoorwayMatrix.Cell,
) {
    awaitTerminalDisconnect(runtime, cell)
    port.failReconnect = false
    reachRailItem("android_connect", cell)
    awaitEnabled("android_connect", cell)
    reachAndClickRail("android_connect", cell)
    waitUntil(10_000) { port.connected }
    waitUntil(10_000) { onAllNodesWithTag("android_connect").fetchSemanticsNodes().isEmpty() }
}

/**
 * Types and sends a real turn through the real composer, then streams a
 * 40-line response, optionally completing the turn. Returns the binding.
 */
internal fun ComposeContentTestRule.sendLongTurn(
    activity: Activity,
    port: LayoutMatrixPort,
    cell: DoorwayMatrix.Cell,
    complete: Boolean = false,
): AndroidTurnBinding {
    reachAction("android_typed_prompt", cell)
    typePrompt(PromptText)
    settleGeometry()
    if (keyboardShown(activity)) {
        revealAboveKeyboard(activity, "Send", "android_action_surface", RevealEdge.Whole, preferUp = true) {
            onAllNodesWithText(SendAction, useUnmergedTree = true).fetchSemanticsNodes()
        }
    } else {
        reachActionText(SendAction, cell)
    }
    onAllNodesWithText(SendAction, useUnmergedTree = true)[0].performClick()
    waitUntil(10_000) { port.lastBinding != null }
    val binding = checkNotNull(port.lastBinding)
    runOnIdle {
        port.emit(AndroidNormalizedEvent.Thinking(binding))
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, LongResponse))
        // A completed turn stops the in-flight phase animation, so the doorway
        // can go idle while it is measured.
        if (complete) port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
    }
    settleGeometry()
    return binding
}

private fun ComposeContentTestRule.promptText(): String =
    onNodeWithTag("android_typed_prompt").fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

/**
 * Types into the real prompt and waits until the composer holds the text. A
 * draft typed in the same frame the client finishes connecting can be reset by
 * that state change; that is logged and the text typed again, never ignored.
 */
internal fun ComposeContentTestRule.typePrompt(text: String) {
    repeat(PromptAttempts) { attempt ->
        if (promptText() == text) return
        if (attempt > 0) println("HOME11-WINDOW prompt-reset attempt=$attempt saw='${promptText()}'")
        onNodeWithTag("android_typed_prompt").performTextClearance()
        onNodeWithTag("android_typed_prompt").performTextInput(text)
        runCatching { waitUntil(3_000) { promptText() == text } }
    }
    check(promptText() == text) { "the prompt never held '$text', saw '${promptText()}'" }
}

/**
 * When the doorway fits its viewport (no whole-layout scroll), the newest
 * transcript item is reachable by scrolling the rail alone: the doorway itself
 * never has to move. (The rail does not auto-follow new content; that is
 * recorded as a finding, not asserted.)
 */
internal fun ComposeContentTestRule.assertNewestItemReachableWhenFitting(cell: DoorwayMatrix.Cell) {
    if ((outerScrollMax() ?: 0f) > 1f) return
    val shownWithoutScrolling = onAllNodesWithTag("android_response_text", useUnmergedTree = true)
        .fetchSemanticsNodes().any { it.layoutInfo.isPlaced && it.boundsInRoot.height > 0f }
    println("HOME11-WINDOW fitting newest-item-shown-without-scrolling=$shownWithoutScrolling")
    reachRailItem("android_response_text", cell)
    check((outerScrollMax() ?: 0f) <= 1f) { "$cell: reaching the newest item must not need the doorway to scroll" }
}

internal const val SendAction = "Start typed turn"
private const val PromptText = "matrix long response"
private const val PromptAttempts = 3
private const val RegionStrokes = 12
private const val DirectStrokes = 20
private const val SweepStrokes = 80
private const val DragMillis = 600
private const val EdgeMarginDp = 12f
private const val FlingMillis = 80
private const val FlingGapBands = 2f
private const val HugeScrollPx = 1_000_000f
private const val DragSettleMillis = 1_500L
private const val ConsecutiveReads = 3
private const val KeyboardTaps = 4
private const val KeyboardCloseMillis = 10_000L

internal val LongResponse: String = buildString {
    append("LINE-01 first line of the long response\n")
    for (line in 2..39) append("LINE-%02d a line of the long response\n".format(line))
    append("LINE-40 last line of the long response")
}
