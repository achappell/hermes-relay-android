package com.achappell.hermesrelay

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `ANDROID-UX-01`: the overflow icon button is announced by its label, and its icon adds no second announcement. */
@RunWith(AndroidJUnit4::class)
class OverflowMenuSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun the_overflow_icon_button_exposes_one_content_description_and_a_click_action() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HermesRelayTheme {
                DoorwayHeaderZone(
                    snapshot = ConnectedFakeSnapshot.value,
                    canShowHistory = true,
                    onShowHistory = {},
                )
            }
        }

        val config = composeRule.onNodeWithTag("android_more_menu").fetchSemanticsNode().config
        assertEquals(
            listOf(context.getString(R.string.android_menu_content_description)),
            config[SemanticsProperties.ContentDescription],
        )
        assertTrue(config.contains(SemanticsActions.OnClick))
        // The decorative glyph is not a second node with its own description.
        composeRule.onNodeWithTag("android_more_menu").fetchSemanticsNode().children.forEach {
            assertTrue(!it.config.contains(SemanticsProperties.ContentDescription))
        }
    }
}

private object ConnectedFakeSnapshot {
    val value = AndroidClientSnapshot(
        titleRes = BootstrapState.titleRes,
        descriptionRes = BootstrapState.descriptionRes,
        boundaryRes = BootstrapState.boundaryRes,
        selectedProfile = AndroidProfile("amanda-laptop", "Amanda"),
        authorizationState = AndroidAuthorizationState.Verified,
    )
}
