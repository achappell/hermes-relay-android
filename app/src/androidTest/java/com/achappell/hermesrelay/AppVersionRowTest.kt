package com.achappell.hermesrelay

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ANDROID-REL-01`: the version row is displayed with its test tag, and the
 * installed package reports the same version the build declares. Needs a
 * device; does not run in CI.
 */
@RunWith(AndroidJUnit4::class)
class AppVersionRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun the_row_shows_the_label_it_is_given() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(RelayProfileCollection(emptyList(), null)),
            credentials = KeystoreRelayCredentialStore(context),
        )
        val label = AppBuildIdentity.of("0.3.1", 301, debug = true, revision = "3e10ae2").label()

        composeRule.setContent {
            HermesRelayTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RelayConfigurationScreen(controller = controller, versionLabel = label, onChanged = {})
                }
            }
        }

        composeRule.onNodeWithTag("app-version").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("app-version").assertTextEquals(label)
    }

    @Test
    fun the_installed_package_reports_a_real_version_and_revision() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = AppBuildIdentity.current(context)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)

        assertEquals(info.versionName, identity.versionName)
        assertTrue(identity.versionCode > 0)
        assertEquals(BuildConfig.DEBUG, identity.buildType == "debug")
        assertTrue(identity.label(), identity.label().startsWith("Version ${info.versionName} ("))
        assertTrue(Build.VERSION.SDK_INT > 0)
    }
}
