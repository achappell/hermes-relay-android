package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsHolderTest {
    private class Fixture {
        val work = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val port = FakeConversations()
        var profile: String? = "profile"
        val holder = ConversationsHolder(port, { profile }, { work.addLast(it) }, { main.addLast(it) },
            ConversationsHolder.Messages("list failed", "close unconfirmed", "refreshed", { "$it closed" }))
        fun work() = work.removeFirst().invoke()
        fun main() = main.removeFirst().invoke()
        fun finish() { while (work.isNotEmpty() || main.isNotEmpty()) { if (work.isNotEmpty()) work(); if (main.isNotEmpty()) main() } }
    }


    @Test fun headerTitleUsesTheCurrentSessionTitleAndRejectsChangedConversation() {
        val f = Fixture()
        f.port.currentRef = "current"
        f.port.sessions = HomeClientClaimProvider.Sessions.Listed(
            listOf(
                HomeClientSession("other", "Other title", 0.0, 0, false),
                HomeClientSession("current", "Current session title", 0.0, 0, false),
            ),
        )
        f.holder.loadHeaderTitle()
        f.work()
        f.port.currentRef = "other"
        f.main()
        assertNull(f.holder.state.value.headerTitle)

        f.holder.loadHeaderTitle()
        f.finish()
        assertEquals("Other title", f.holder.state.value.headerTitle)
    }
    @Test fun conversationGenerationAndProfileRejectObsoleteLists() {
        val f = Fixture()
        f.port.sessions = HomeClientClaimProvider.Sessions.Unavailable("old")
        f.holder.loadConversations()
        f.work()
        f.holder.loadConversations()
        f.main()
        assertEquals(HomeConversationsState.Loading, f.holder.state.value.conversationsState)
        f.port.sessions = HomeClientClaimProvider.Sessions.Listed(emptyList())
        f.finish()
        assertEquals(HomeConversationsState.Listed(emptyList()), f.holder.state.value.conversationsState)
        f.holder.loadConversations()
        f.work()
        f.profile = "other"
        f.main()
        assertEquals(HomeConversationsState.Loading, f.holder.state.value.conversationsState)
    }

    @Test fun hidingInvalidatesBothQueuedLists() {
        val f = Fixture()
        f.holder.loadConversations()
        f.holder.loadOpenClaims()
        f.work(); f.work()
        f.holder.hide()
        f.main(); f.main()
        assertEquals(HomeConversationsState.Loading, f.holder.state.value.conversationsState)
        assertEquals(HomeOpenClaimsState.Loading, f.holder.state.value.openClaimsState)
        assertTrue(f.work.isEmpty())
    }

    @Test fun claimsPublishBeforeTitlesAndEnrichmentIsInvalidatedByNewRequest() {
        val f = Fixture()
        f.holder.loadOpenClaims()
        f.work(); f.main()
        assertEquals(null, (f.holder.state.value.openClaimsState as HomeOpenClaimsState.Listed).claims.single().title)
        f.work() // old enrichment is now awaiting main publication
        f.holder.loadOpenClaims()
        f.main()
        assertEquals(HomeOpenClaimsState.Loading, f.holder.state.value.openClaimsState)
        f.finish()
        assertEquals("enriched", (f.holder.state.value.openClaimsState as HomeOpenClaimsState.Listed).claims.single().title)
    }

    @Test fun profileChangeRejectsClaimsAndHideRejectsEnrichment() {
        val f = Fixture()
        f.holder.loadOpenClaims(); f.work()
        f.profile = "other"
        f.main()
        assertEquals(HomeOpenClaimsState.Loading, f.holder.state.value.openClaimsState)
        f.holder.loadOpenClaims(); f.work(); f.main(); f.work()
        f.holder.hide(); f.main()
        assertNull((f.holder.state.value.openClaimsState as HomeOpenClaimsState.Listed).claims.single().title)
    }

    @Test fun staleCloseCannotReplaceNewListingOrMessage() {
        val f = Fixture()
        f.holder.submitClaimClose("pairing", listOf("claim")); f.work()
        f.holder.loadOpenClaims()
        f.main()
        assertNull(f.holder.state.value.claimManagementMessage)
        assertEquals(HomeOpenClaimsState.Loading, f.holder.state.value.openClaimsState)
        f.finish()
        assertTrue(f.holder.state.value.openClaimsState is HomeOpenClaimsState.Listed)
    }

    @Test fun closePublishesResultAndRefreshAndHandlesUnconfirmedFailure() {
        val f = Fixture()
        f.holder.submitClaimClose("other", listOf("claim"))
        assertTrue(f.work.isEmpty())
        f.holder.submitClaimClose("pairing", listOf("claim"))
        assertEquals(HomeOpenClaimsState.Loading, f.holder.state.value.openClaimsState)
        f.finish()
        assertEquals("1 closed", f.holder.state.value.claimManagementMessage)
        f.port.closeThrows = true
        f.holder.submitClaimClose("pairing", listOf("claim")); f.finish()
        assertEquals("close unconfirmed", f.holder.state.value.claimManagementMessage)
        assertTrue(f.holder.state.value.openClaimsState is HomeOpenClaimsState.Listed)
        assertEquals("pairing", f.port.explicitListing)
        f.port.listThrows = true
        f.holder.submitClaimClose("pairing", listOf("claim")); f.finish()
        assertEquals(HomeOpenClaimsState.Unavailable("list failed"), f.holder.state.value.openClaimsState)
    }

    @Test fun unsupportedClaimsClearMessageAndUnpairedClaimsStayHidden() {
        val f = Fixture()
        f.port.claims = HomeClientClaimProvider.OpenClaims.Unsupported
        f.holder.submitClaimClose("pairing", listOf("claim")); f.finish()
        assertEquals(HomeOpenClaimsState.Hidden, f.holder.state.value.openClaimsState)
        assertNull(f.holder.state.value.claimManagementMessage)
        f.port.paired = false
        f.holder.loadOpenClaims()
        assertTrue(f.work.isEmpty())
        assertEquals(HomeOpenClaimsState.Hidden, f.holder.state.value.openClaimsState)
    }

    @Test fun renameRefreshesOnlyOnSuccessAndShowClearsResult() {
        val f = Fixture()
        f.holder.rename("title", "renamed", "failed")
        assertNull(f.holder.state.value.renameMessage)
        f.work(); f.main()
        assertEquals("renamed", f.holder.state.value.renameMessage)
        assertEquals(HomeConversationsState.Loading, f.holder.state.value.conversationsState)
        f.finish()
        assertTrue(f.holder.state.value.conversationsState is HomeConversationsState.Listed)
        f.port.renameSuccess = false
        f.holder.rename("title", "renamed", "failed"); f.finish()
        assertEquals("failed", f.holder.state.value.renameMessage)
        f.holder.show()
        assertNull(f.holder.state.value.renameMessage)
    }

    @Test fun noticeConsumesDividerOnceAndFallbackOverridesRequestedDivider() {
        val f = Fixture()
        val dividers = mutableListOf<String>()
        f.holder.setConversationMessage("previous")
        f.holder.switchConversation(HomeConversationIntent.New, "new") { }
        assertNull(f.holder.state.value.conversationMessage)
        assertEquals("new", f.holder.state.value.pendingDivider)
        assertFalse(f.holder.consumeConversationNotice("busy", "gone", dividers::add))
        assertEquals("new", f.holder.state.value.pendingDivider)
        f.port.notice = HomeClaimedConversation(null, false, HomeResumeFallback.Busy)
        assertTrue(f.holder.consumeConversationNotice("busy", "gone", dividers::add))
        assertEquals(listOf("busy"), dividers)
        assertEquals("busy", f.holder.state.value.conversationMessage)
        assertNull(f.holder.state.value.pendingDivider)
        assertFalse(f.holder.consumeConversationNotice("busy", "gone", dividers::add))
        f.holder.switchConversation(HomeConversationIntent.New, "new") { }
        f.port.notice = HomeClaimedConversation(null, false)
        f.holder.consumeConversationNotice("busy", "gone", dividers::add)
        assertEquals(listOf("busy", "new"), dividers)
    }

    private class FakeConversations : AndroidHomeConversations {
        var paired = true
        var sessions: HomeClientClaimProvider.Sessions = HomeClientClaimProvider.Sessions.Listed(emptyList())
        var claims: HomeClientClaimProvider.OpenClaims = HomeClientClaimProvider.OpenClaims.Listed("pairing", 3,
            listOf(HomeClientClaim("claim", "grant", null, "session", 0.0, null, HomeClientClaimState.Idle)))
        var closeThrows = false
        var listThrows = false
        var explicitListing: String? = null
        var renameSuccess = true
        var notice: HomeClaimedConversation? = null
        var currentRef: String? = null
        override fun selectedIsPaired() = paired
        override fun selectedPairingId() = "pairing"
        override fun listConversations() = sessions
        override fun supportsOpenClaims() = true
        override fun listOpenClaims(pairingId: String?): HomeClientClaimProvider.OpenClaims {
            explicitListing = pairingId
            if (listThrows) error("listing failed")
            return claims
        }
        override fun enrichOpenClaimTitles(pairingId: String, claims: List<HomeClientClaim>) = claims.map { it.copy(title = "enriched") }
        override fun closeAndListOpenClaims(pairingId: String, claimRefs: List<String>): HomeClientClaimProvider.CloseAndList {
            if (closeThrows) error("close failed")
            return HomeClientClaimProvider.CloseAndList(HomeClientClaimProvider.CloseClaims.Completed(claimRefs.size, 1), claims)
        }
        override fun currentClaimRef(): String? = null
        override fun currentConversationRef() = currentRef
        override fun requestConversation(intent: HomeConversationIntent) = Unit
        override fun takeConversationNotice() = notice.also { notice = null }
        override fun learnCurrentConversation(): String? = null
        override fun canRenameConversation() = true
        override fun renameConversation(title: String) = renameSuccess
    }
}
