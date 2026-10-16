package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalsHolderTest {
    private val request = HomeProfileHolder("grant", "phone", "android", "profile",
        HomeClientGrantStatus.PendingOwner, false, false, 0.0)
    private val messages = ApprovalsHolder.Messages(
        mapOf(HomeGrantAction.Approve to "approved", HomeGrantAction.Reject to "rejected", HomeGrantAction.Revoke to "revoked"),
        "gone", "not allowed", "unreachable", "failed",
    )

    private class Fixture {
        val work = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val port = FakeApprovals()
        var paired = true
        val holder = ApprovalsHolder(port, { paired }, { work.addLast(it) }, { main.addLast(it) })
        fun work() = work.removeFirst().invoke()
        fun main() = main.removeFirst().invoke()
        fun finish() { while (work.isNotEmpty() || main.isNotEmpty()) { if (work.isNotEmpty()) work(); if (main.isNotEmpty()) main() } }
    }

    @Test fun decisionKeepsLoadedRowsUntilRefreshAndPublishesBusyResultThenCount() {
        val f = Fixture()
        f.port.loaded = HomeClientClaimProvider.Approvals.Loaded(listOf(request), emptyList())
        f.holder.refresh(); f.finish()
        val before = f.holder.state.value.approvalsState
        assertEquals(1, f.holder.state.value.pendingApprovals)
        f.holder.decide("grant", HomeGrantAction.Approve, messages)
        assertTrue(f.holder.state.value.approvalsBusy)
        assertNull(f.holder.state.value.approvalsMessage)
        assertEquals(before, f.holder.state.value.approvalsState)
        f.work()
        assertTrue(f.holder.state.value.approvalsBusy)
        f.main()
        assertFalse(f.holder.state.value.approvalsBusy)
        assertEquals("approved", f.holder.state.value.approvalsMessage)
        assertEquals(before, f.holder.state.value.approvalsState)
        assertEquals(1, f.holder.state.value.pendingApprovals)
        f.port.loaded = HomeClientClaimProvider.Approvals.Loaded(emptyList(), listOf(request.copy(status = HomeClientGrantStatus.Active)))
        f.finish()
        assertEquals(0, f.holder.state.value.pendingApprovals)
        assertEquals(1, (f.holder.state.value.approvalsState as HomeApprovalsState.Loaded).holders.size)
        assertEquals("approved", f.holder.state.value.approvalsMessage)
        f.holder.clearMessage()
        assertNull(f.holder.state.value.approvalsMessage)
    }

    @Test fun explicitRefreshLoadsAndUnavailablePreservesPreviousPendingCount() {
        val f = Fixture()
        f.port.loaded = HomeClientClaimProvider.Approvals.Loaded(listOf(request), emptyList())
        f.holder.refresh(); f.finish()
        f.port.loaded = HomeClientClaimProvider.Approvals.Unavailable("offline")
        f.holder.refresh()
        assertEquals(HomeApprovalsState.Loading, f.holder.state.value.approvalsState)
        f.work()
        assertEquals(HomeApprovalsState.Loading, f.holder.state.value.approvalsState)
        f.main()
        assertEquals(HomeApprovalsState.Unavailable("offline"), f.holder.state.value.approvalsState)
        assertEquals(1, f.holder.state.value.pendingApprovals)
        f.paired = false
        f.holder.refresh()
        assertEquals(0, f.holder.state.value.pendingApprovals)
        assertEquals(HomeApprovalsState.Unavailable("offline"), f.holder.state.value.approvalsState)
        assertTrue(f.work.isEmpty())
    }

    @Test fun allDecisionResultsClearBusyAndRefreshWithoutLoading() {
        val outcomes = listOf(
            HomeGrantActionResult.Done to "rejected",
            HomeGrantActionResult.Gone to "gone",
            HomeGrantActionResult.NotAllowed to "not allowed",
            HomeGrantActionResult.Unreachable to "unreachable",
            HomeGrantActionResult.Failed to "failed",
        )
        for ((result, text) in outcomes) {
            val f = Fixture()
            f.holder.refresh(); f.finish()
            f.port.result = result
            f.holder.decide("grant", HomeGrantAction.Reject, messages)
            f.work(); f.main()
            assertFalse(f.holder.state.value.approvalsBusy)
            assertEquals(text, f.holder.state.value.approvalsMessage)
            assertTrue(f.holder.state.value.approvalsState is HomeApprovalsState.Loaded)
            f.port.loaded = HomeClientClaimProvider.Approvals.Unavailable("refresh failed")
            f.finish()
            assertEquals(HomeApprovalsState.Unavailable("refresh failed"), f.holder.state.value.approvalsState)
            assertEquals(text, f.holder.state.value.approvalsMessage)
        }
    }

    @Test fun existingUnguardedResponseOrderingIsPreserved() {
        val f = Fixture()
        f.port.loaded = HomeClientClaimProvider.Approvals.Loaded(listOf(request), emptyList())
        f.holder.refresh(); f.work()
        f.paired = false
        f.holder.refresh()
        assertEquals(0, f.holder.state.value.pendingApprovals)
        f.main()
        // Refactoring must not silently introduce a profile/generation race fix.
        assertEquals(1, f.holder.state.value.pendingApprovals)
        assertTrue(f.holder.state.value.approvalsState is HomeApprovalsState.Loaded)
    }

    private class FakeApprovals : AndroidHomeApprovals {
        var loaded: HomeClientClaimProvider.Approvals = HomeClientClaimProvider.Approvals.Loaded(emptyList(), emptyList())
        var result: HomeGrantActionResult = HomeGrantActionResult.Done
        override fun approvals() = loaded
        override fun decideGrant(targetGrantId: String, action: HomeGrantAction) = result
    }
}
