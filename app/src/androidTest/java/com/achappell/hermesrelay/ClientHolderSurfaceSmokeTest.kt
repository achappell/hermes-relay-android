package com.achappell.hermesrelay

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/** Actual Compose screen and runtime with disposable in-memory Home interfaces, not live Home. */
@RunWith(AndroidJUnit4::class)
class ClientHolderSurfaceSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typed_reply_resume_approval_and_history_use_the_extracted_holders() {
        val port = FixturePort()
        val configuration = RelayConfigurationController(
            InMemoryRelayProfileStore(RelayProfileCollection(
                profiles = listOf(RelayProfile(id = "fixture", endpoint = "wss://fixture.invalid",
                    clientId = "fixture-client", deviceId = "fixture-device", displayName = "Fixture")),
                selectedId = "fixture",
            )),
            InMemoryRelayCredentialStore(),
        )
        val selected = mutableStateOf(configuration.collection.copy(
            profiles = configuration.collection.profiles.map { it.copy(mode = RelayConnectionMode.Standard) },
        ))
        val mux = ModeAwareClientPort({ selected.value }, port, port)
        val handler = Handler(Looper.getMainLooper())
        val runtime = HomeRuntime(mux, null, InMemoryAndroidHistoryStore(),
            { handler.post(it) }, Executors.newSingleThreadExecutor())
        compose.setContent {
            DisposableEffect(runtime) {
                runtime.activityCreated()
                onDispose { runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false) }
            }
            HermesRelayTheme { AndroidClientScreen(runtime, configuration) }
        }
        compose.waitUntil(10_000) { runtime.recoveryState.connection == AndroidConnectionState.Connected }
        compose.runOnIdle {
            selected.value = selected.value.copy(profiles = selected.value.profiles.map { it.copy(mode = RelayConnectionMode.HomeBridge) })
            runtime.configurationChanged()
        }
        compose.waitUntil(10_000) { runtime.recoveryState.connection != AndroidConnectionState.Connected }
        compose.scrollToConversationTag("android_connect")
        click("android_connect")
        compose.waitUntil(10_000) { runtime.recoveryState.connection == AndroidConnectionState.Connected }
        compose.onNodeWithTag("android_typed_prompt").performTextInput("Disposable fixture prompt")
        compose.onNodeWithText("Start typed turn").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { port.listener != null }
        compose.onNodeWithText("Tap to speak").assertIsNotEnabled()
        compose.onNodeWithText("Start typed turn").assertIsNotEnabled()
        compose.runOnIdle {
            port.emit(AndroidNormalizedEvent.ResponseTextDelta(port.binding, "Fixture reply"))
            port.emit(AndroidNormalizedEvent.TurnCompleted(port.binding, "Fixture reply"))
        }
        compose.waitUntil(10_000) { runtime.turnState.isTerminal }
        assertEquals(1, port.submissions)
        // No draft edit, sheet reopen or recreation may be needed to leave busy state.
        compose.onNodeWithText("Tap to speak").assertIsEnabled()
        compose.onNodeWithTag("android_typed_prompt").assertIsEnabled()
        compose.onNodeWithTag("android_typed_prompt").performTextInput("Second explicit fixture prompt")
        compose.onNodeWithText("Start typed turn").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { port.submissions == 2 && port.listener != null && runtime.hasAcceptedTurn }
        compose.runOnIdle { port.emit(AndroidNormalizedEvent.TurnCompleted(port.binding, "Second fixture reply")) }
        compose.waitUntil(10_000) { runtime.turnState.isTerminal }
        compose.onNodeWithText("Tap to speak").assertIsEnabled()
        menu("android_menu_history")
        compose.onNodeWithTag("android_history_sheet").assert(hasAnyDescendant(hasText("Fixture reply")))
        pressBack()
        menu("android_menu_conversations")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("android_conversation_resume").fetchSemanticsNodes().isNotEmpty() }
        click("android_conversation_resume")
        compose.waitUntil(10_000) { port.resumed && runtime.recoveryState.connection == AndroidConnectionState.Connected }
        assertEquals(2, port.submissions)
        menu("android_menu_approvals")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("android_approval_approve").fetchSemanticsNodes().isNotEmpty() }
        click("android_approval_approve")
        compose.waitUntil(10_000) { port.approved && compose.onAllNodesWithTag("android_approval_holder").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("android_approvals_message").assertExists()
        assertTrue(port.approved)
        assertEquals(2, port.submissions)
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
    }
    private fun menu(tag: String) { click("android_more_menu"); click(tag) }

    private class FixturePort : AndroidClientPort, AndroidHomeConversations, AndroidHomeApprovals {
        @Volatile var binding = AndroidTurnBinding("fixture", "fixture-session", "fixture-turn")
        @Volatile var submissions = 0
        @Volatile var listener: ((AndroidNormalizedEvent) -> Unit)? = null
        @Volatile var resumed = false
        @Volatile var approved = false
        private val request = HomeProfileHolder("fixture-grant", "Fixture phone", "android", "fixture",
            HomeClientGrantStatus.PendingOwner, false, false, 0.0)
        override fun snapshot() = AndroidClientSnapshot(BootstrapState.titleRes, BootstrapState.descriptionRes,
            BootstrapState.boundaryRes, AndroidProfile("fixture", "Fixture"), AndroidAuthorizationState.Verified)
        override fun reconnect() = AndroidReconnectOutcome.Connected("fixture-session")
        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            submissions += 1
            binding = binding.copy(turnId = "fixture-turn-$submissions")
            return AndroidInitiationResult.Accepted(binding)
        }
        override fun observeTurn(binding: AndroidTurnBinding, onEvent: (AndroidNormalizedEvent) -> Unit): AndroidTurnObservation {
            listener = onEvent
            return AndroidTurnObservation { listener = null }
        }
        fun emit(event: AndroidNormalizedEvent) { checkNotNull(listener)(event) }
        override fun selectedIsPaired() = true
        override fun selectedPairingId() = "fixture-pairing"
        override fun listConversations() = HomeClientClaimProvider.Sessions.Listed(
            listOf(HomeClientSession("fixture-resume", "Saved fixture", 0.0, 2, false)))
        override fun supportsOpenClaims() = false
        override fun listOpenClaims(pairingId: String?) = HomeClientClaimProvider.OpenClaims.Unsupported
        override fun enrichOpenClaimTitles(pairingId: String, claims: List<HomeClientClaim>) = claims
        override fun closeAndListOpenClaims(pairingId: String, claimRefs: List<String>) =
            HomeClientClaimProvider.CloseAndList(HomeClientClaimProvider.CloseClaims.Unsupported, HomeClientClaimProvider.OpenClaims.Unsupported)
        override fun currentClaimRef(): String? = null
        override fun currentConversationRef() = if (resumed) "fixture-resume" else "fixture-session"
        override fun requestConversation(intent: HomeConversationIntent) { resumed = intent is HomeConversationIntent.Resume }
        override fun takeConversationNotice(): HomeClaimedConversation? = null
        override fun learnCurrentConversation() = currentConversationRef()
        override fun canRenameConversation() = false
        override fun renameConversation(title: String) = false
        override fun approvals() = if (approved) HomeClientClaimProvider.Approvals.Loaded(emptyList(), listOf(request.copy(status = HomeClientGrantStatus.Active)))
            else HomeClientClaimProvider.Approvals.Loaded(listOf(request), emptyList())
        override fun decideGrant(targetGrantId: String, action: HomeGrantAction): HomeGrantActionResult {
            check(targetGrantId == "fixture-grant" && action == HomeGrantAction.Approve)
            approved = true
            return HomeGrantActionResult.Done
        }
    }
}
