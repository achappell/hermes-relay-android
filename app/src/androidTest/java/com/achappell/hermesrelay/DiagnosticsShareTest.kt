package com.achappell.hermesrelay

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * `ANDROID-DIAG-01`: the share intent and its `FileProvider` wiring, and the
 * Troubleshooting control. Asserts the chooser intent, not the system UI.
 *
 * Needs a device or emulator; does not run in CI.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsShareTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class OneLineJournal : DiagnosticsJournal {
        override fun record(event: String) = Unit

        override fun snapshot() = listOf(JournalEntry(1_700_000_000_000L, "app phase=started"))
    }

    @Test
    fun the_chooser_shares_one_text_file_the_provider_can_serve() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val chooser = DiagnosticsShare.chooserIntent(context, OneLineJournal())

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("${context.packageName}.diagnostics", uri.authority)
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertTrue(text.contains("hermes-relay-diagnostics/1"))
        assertTrue(text.contains("app phase=started"))
    }

    @Test
    fun share_diagnostics_is_offered_without_a_profile_and_reports_the_tap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(RelayProfileCollection(emptyList(), null)),
            credentials = KeystoreRelayCredentialStore(context),
        )
        var shared = 0

        composeRule.setContent {
            HermesRelayTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RelayConfigurationScreen(
                        controller = controller,
                        onShareDiagnostics = { shared += 1 },
                        onChanged = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("android_diagnostics_share").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("android_diagnostics_share").performClick()
        assertEquals(1, shared)
    }
}
