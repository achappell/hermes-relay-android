package com.achappell.hermesrelay

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DoorwayHeaderLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun long_profile_and_header_menu_remain_reachable_at_one_and_two_x_in_both_themes() {
        val profileName = "Long synthetic profile name for layout testing"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.bootstrap_title)
        val description = context.getString(R.string.bootstrap_description)
        val profileLabel = context.getString(R.string.android_profile_label)
        val menuDescription = context.getString(R.string.android_menu_content_description)
        val authorization = context.getString(
            R.string.android_authorization_label,
            context.getString(AndroidAuthorizationState.Verified.labelRes()),
        )
        val baseConfiguration = Configuration(context.resources.configuration)
        val density = context.resources.displayMetrics.density

        val darkTheme = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)

        composeRule.setContent {
            val isDarkTheme = darkTheme.value
            val currentFontScale = fontScale.value
            val uiModeNight = if (isDarkTheme) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            val configuration = Configuration(baseConfiguration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or uiModeNight
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, currentFontScale),
            ) {
                HermesRelayTheme {
                    Scaffold(
                        topBar = {
                            DoorwayHeaderZone(
                                snapshot = AndroidClientSnapshot(
                                    titleRes = R.string.bootstrap_title,
                                    descriptionRes = R.string.bootstrap_description,
                                    boundaryRes = R.string.bootstrap_boundary,
                                    selectedProfile = AndroidProfile("layout-test", profileName),
                                    authorizationState = AndroidAuthorizationState.Verified,
                                ),
                                canShowConversations = true,
                            )
                        },
                    ) { insets ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(insets),
                        ) {
                            Text("Synthetic conversation content")
                        }
                    }
                }
            }
        }

        for (darkThemeValue in listOf(false, true)) {
            for (fontScaleValue in listOf(1f, 2f)) {
                composeRule.runOnIdle {
                    darkTheme.value = darkThemeValue
                    fontScale.value = fontScaleValue
                }
                composeRule.waitForIdle()

                composeRule.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
                composeRule.onNodeWithText(description, useUnmergedTree = true).assertIsDisplayed()
                composeRule.onNodeWithText(profileName, useUnmergedTree = true).assertIsDisplayed()
                composeRule.onNodeWithText(authorization, useUnmergedTree = true).assertIsDisplayed()
                composeRule.onNodeWithText("Synthetic conversation content").assertIsDisplayed()

                val titleBounds = composeRule
                    .onNodeWithText(title, useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val descriptionBounds = composeRule
                    .onNodeWithText(description, useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val profileLabelBounds = composeRule
                    .onNodeWithText(profileLabel, useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val profileBounds = composeRule
                    .onNodeWithText(profileName, useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val authorizationBounds = composeRule
                    .onNodeWithText(authorization, useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "subtitle must be below the title",
                    descriptionBounds.top >= titleBounds.bottom,
                )
                assertTrue(
                    "profile must be below the subtitle",
                    profileLabelBounds.top >= descriptionBounds.bottom,
                )
                assertTrue(
                    "profile value must be below its label",
                    profileBounds.top >= profileLabelBounds.bottom,
                )
                assertTrue(
                    "authorization remains below the profile name",
                    authorizationBounds.top >= profileBounds.bottom,
                )
                val accessibleProfileName = composeRule
                    .onNodeWithText(profileName, useUnmergedTree = true)
                    .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                assertEquals(
                    "the complete profile name remains in the accessible text",
                    profileName,
                    accessibleProfileName,
                )

                val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                val headerBounds = composeRule
                    .onNodeWithTag("android_doorway_header")
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "header remains inside the screen",
                    headerBounds.left >= rootBounds.left &&
                        headerBounds.right <= rootBounds.right &&
                        headerBounds.top >= rootBounds.top &&
                        headerBounds.bottom <= rootBounds.bottom,
                )
                assertTrue(
                    "title remains inside the header",
                    titleBounds.left >= headerBounds.left &&
                        titleBounds.right <= headerBounds.right &&
                        titleBounds.top >= headerBounds.top &&
                        titleBounds.bottom <= headerBounds.bottom,
                )
                assertTrue(
                    "subtitle remains inside the header",
                    descriptionBounds.left >= headerBounds.left &&
                        descriptionBounds.right <= headerBounds.right &&
                        descriptionBounds.top >= headerBounds.top &&
                        descriptionBounds.bottom <= headerBounds.bottom,
                )
                assertTrue(
                    "profile remains inside the header",
                    profileBounds.left >= headerBounds.left &&
                        profileBounds.right <= headerBounds.right &&
                        profileBounds.top >= headerBounds.top &&
                        profileBounds.bottom <= headerBounds.bottom,
                )
                assertTrue(
                    "authorization remains inside the header",
                    authorizationBounds.left >= headerBounds.left &&
                        authorizationBounds.right <= headerBounds.right &&
                        authorizationBounds.top >= headerBounds.top &&
                        authorizationBounds.bottom <= headerBounds.bottom,
                )
                val menu = composeRule.onNodeWithTag("android_more_menu")
                menu.assertIsDisplayed().assertHasClickAction()
                val menuSemantics = menu.fetchSemanticsNode().config
                assertEquals(
                    "overflow menu retains its accessible label",
                    listOf(menuDescription),
                    menuSemantics[SemanticsProperties.ContentDescription],
                )
                val menuBounds = menu.fetchSemanticsNode().boundsInRoot
                assertTrue("overflow menu remains inside the screen", menuBounds.left >= rootBounds.left)
                assertTrue("overflow menu remains inside the screen", menuBounds.right <= rootBounds.right)
                assertTrue("overflow menu does not overlap the title", menuBounds.left >= titleBounds.right)

                val theme = if (darkThemeValue) "dark" else "light"
                val scale = fontScaleValue.toInt()
                captureScreen("ux03-header-$theme-${scale}x-closed.png")
                menu.performClick()
                composeRule.onNodeWithTag("android_navigation_menu").assertIsDisplayed()
                composeRule.onNodeWithTag("android_menu_conversations").assertIsDisplayed()
                captureScreen("ux03-header-$theme-${scale}x-menu.png")
                composeRule.onNodeWithTag("android_menu_conversations").performClick()
                composeRule.waitForIdle()
            }
        }
    }

    private fun captureScreen(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val directory = instrumentation.targetContext.cacheDir.resolve("ux03-header")
        check(directory.mkdirs() || directory.isDirectory)
        FileOutputStream(File(directory, fileName)).use { output ->
            assertTrue(
                "screenshot should be encoded",
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}
