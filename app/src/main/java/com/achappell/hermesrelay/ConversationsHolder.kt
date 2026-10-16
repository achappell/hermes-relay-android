package com.achappell.hermesrelay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Composition-scoped management state; execution stays on the Home runtime queues. */
internal class ConversationsHolder(
    private val conversations: AndroidHomeConversations?,
    private val selectedProfileId: () -> String?,
    private val runOnWork: (() -> Unit) -> Unit,
    private val runOnMain: (() -> Unit) -> Unit,
    private val messages: Messages,
) {
    data class Messages(
        val claimsListFailed: String,
        val claimsCloseUnconfirmed: String,
        val claimsRefreshed: String,
        val claimsClosed: (Int) -> String,
    )

    data class State(
        val conversationsState: HomeConversationsState = HomeConversationsState.Loading,
        val openClaimsState: HomeOpenClaimsState = HomeOpenClaimsState.Hidden,
        val claimManagementMessage: String? = null,
        val renameMessage: String? = null,
        val pendingDivider: String? = null,
        val conversationMessage: String? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var generation = 0
    private val openClaimsRequests = HomeProfileRequestGate()

    fun loadConversations() {
        val conversations = conversations ?: return
        val profileId = selectedProfileId()
        val requestId = ++generation
        mutableState.value = state.value.copy(conversationsState = HomeConversationsState.Loading)
        runOnWork {
            val listed = when (val result = conversations.listConversations()) {
                is HomeClientClaimProvider.Sessions.Listed -> HomeConversationsState.Listed(result.sessions)
                is HomeClientClaimProvider.Sessions.Unavailable -> HomeConversationsState.Unavailable(result.message)
            }
            runOnMain {
                if (requestId == generation && profileId == selectedProfileId()) {
                    mutableState.value = state.value.copy(conversationsState = listed)
                }
            }
        }
    }

    private fun isCurrent(request: HomeProfileRequestGate.Request) =
        openClaimsRequests.isCurrent(request, selectedProfileId())

    private fun applyOpenClaimsResult(
        request: HomeProfileRequestGate.Request,
        result: HomeClientClaimProvider.OpenClaims,
    ) {
        if (!isCurrent(request)) return
        when (result) {
            HomeClientClaimProvider.OpenClaims.Unsupported -> {
                mutableState.value = state.value.copy(
                    openClaimsState = HomeOpenClaimsState.Hidden,
                    claimManagementMessage = null,
                )
            }
            is HomeClientClaimProvider.OpenClaims.Listed -> {
                mutableState.value = state.value.copy(openClaimsState = HomeOpenClaimsState.Listed(
                    result.pairingId, result.maxClaims, result.claims,
                ))
                runOnWork {
                    val enriched = runCatching {
                        conversations?.enrichOpenClaimTitles(result.pairingId, result.claims)
                    }.getOrNull() ?: result.claims
                    runOnMain {
                        if (isCurrent(request)) {
                            val current = state.value.openClaimsState as? HomeOpenClaimsState.Listed
                            if (current?.pairingId == result.pairingId) {
                                mutableState.value = state.value.copy(openClaimsState = current.copy(claims = enriched))
                            }
                        }
                    }
                }
            }
            is HomeClientClaimProvider.OpenClaims.Unavailable -> {
                mutableState.value = state.value.copy(openClaimsState = HomeOpenClaimsState.Unavailable(result.message))
            }
        }
    }

    fun loadOpenClaims() {
        val conversations = conversations ?: return
        val request = openClaimsRequests.begin(selectedProfileId())
        mutableState.value = state.value.copy(claimManagementMessage = null)
        if (!conversations.selectedIsPaired() || !conversations.supportsOpenClaims()) {
            mutableState.value = state.value.copy(openClaimsState = HomeOpenClaimsState.Hidden)
            return
        }
        mutableState.value = state.value.copy(openClaimsState = HomeOpenClaimsState.Loading)
        runOnWork {
            val listed = try {
                conversations.listOpenClaims()
            } catch (_: Exception) {
                HomeClientClaimProvider.OpenClaims.Unavailable(messages.claimsListFailed)
            }
            runOnMain { applyOpenClaimsResult(request, listed) }
        }
    }

    fun submitClaimClose(pairingId: String, claimRefs: List<String>) {
        val conversations = conversations ?: return
        if (conversations.selectedPairingId() != pairingId) return
        val request = openClaimsRequests.begin(selectedProfileId())
        mutableState.value = state.value.copy(openClaimsState = HomeOpenClaimsState.Loading, claimManagementMessage = null)
        runOnWork {
            val closeAndList = try {
                conversations.closeAndListOpenClaims(pairingId, claimRefs)
            } catch (_: Exception) {
                val refreshed = try {
                    conversations.listOpenClaims(pairingId)
                } catch (_: Exception) {
                    HomeClientClaimProvider.OpenClaims.Unavailable(messages.claimsListFailed)
                }
                HomeClientClaimProvider.CloseAndList(
                    HomeClientClaimProvider.CloseClaims.Unavailable(messages.claimsCloseUnconfirmed), refreshed,
                )
            }
            val message = when (val result = closeAndList.closeResult) {
                HomeClientClaimProvider.CloseClaims.Unsupported -> null
                is HomeClientClaimProvider.CloseClaims.Completed ->
                    if (result.closed == 0) messages.claimsRefreshed else messages.claimsClosed(result.closed)
                is HomeClientClaimProvider.CloseClaims.Unavailable -> messages.claimsCloseUnconfirmed
            }
            runOnMain {
                if (isCurrent(request)) {
                    mutableState.value = state.value.copy(claimManagementMessage = message)
                    applyOpenClaimsResult(request, closeAndList.listing)
                }
            }
        }
    }

    fun hide() {
        generation += 1
        openClaimsRequests.invalidate()
    }

    fun show() {
        mutableState.value = state.value.copy(renameMessage = null)
    }

    fun rename(title: String, successText: String, failureText: String) {
        val conversations = conversations ?: return
        mutableState.value = state.value.copy(renameMessage = null)
        runOnWork {
            val renamed = conversations.renameConversation(title)
            runOnMain {
                mutableState.value = state.value.copy(renameMessage = if (renamed) successText else failureText)
                if (renamed) loadConversations()
            }
        }
    }

    fun switchConversation(intent: HomeConversationIntent, divider: String, switch: (HomeConversationIntent) -> Unit) {
        if (conversations == null) return
        mutableState.value = state.value.copy(conversationMessage = null, pendingDivider = divider)
        switch(intent)
    }

    /** Returns whether a notice was consumed, preserving the caller's history revision. */
    fun consumeConversationNotice(busyText: String, goneText: String, recordDivider: (String) -> Unit): Boolean {
        val notice = conversations?.takeConversationNotice() ?: return false
        val fallbackMessage = when (notice.fallback) {
            HomeResumeFallback.Busy -> busyText
            HomeResumeFallback.Gone -> goneText
            null -> null
        }
        val requested = state.value.pendingDivider
        mutableState.value = state.value.copy(pendingDivider = null)
        when {
            fallbackMessage != null -> {
                setConversationMessage(fallbackMessage)
                recordDivider(fallbackMessage)
            }
            requested != null -> recordDivider(requested)
        }
        return true
    }

    fun setConversationMessage(message: String?) {
        mutableState.value = state.value.copy(conversationMessage = message)
    }
}
