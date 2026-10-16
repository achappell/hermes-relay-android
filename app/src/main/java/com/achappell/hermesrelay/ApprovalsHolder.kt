package com.achappell.hermesrelay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Composition-scoped state, retaining the existing unguarded approval refresh ordering. */
internal class ApprovalsHolder(
    private val approvals: AndroidHomeApprovals?,
    private val selectedIsPaired: () -> Boolean,
    private val runOnWork: (() -> Unit) -> Unit,
    private val runOnMain: (() -> Unit) -> Unit,
) {
    data class State(
        val approvalsState: HomeApprovalsState = HomeApprovalsState.Loading,
        val approvalsMessage: String? = null,
        val approvalsBusy: Boolean = false,
        val pendingApprovals: Int = 0,
    )

    data class Messages(
        val doneTexts: Map<HomeGrantAction, String>,
        val gone: String,
        val notAllowed: String,
        val unreachable: String,
        val failed: String,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun refresh(showLoading: Boolean = true) {
        val approvals = approvals ?: return
        if (!selectedIsPaired()) {
            mutableState.value = state.value.copy(pendingApprovals = 0)
            return
        }
        if (showLoading) mutableState.value = state.value.copy(approvalsState = HomeApprovalsState.Loading)
        runOnWork {
            val loaded = when (val result = approvals.approvals()) {
                is HomeClientClaimProvider.Approvals.Loaded -> HomeApprovalsState.Loaded(result.pending, result.holders)
                is HomeClientClaimProvider.Approvals.Unavailable -> HomeApprovalsState.Unavailable(result.message)
            }
            runOnMain {
                mutableState.value = state.value.copy(
                    approvalsState = loaded,
                    pendingApprovals = if (loaded is HomeApprovalsState.Loaded) loaded.pending.size else state.value.pendingApprovals,
                )
            }
        }
    }

    fun clearMessage() {
        mutableState.value = state.value.copy(approvalsMessage = null)
    }

    fun decide(grantId: String, action: HomeGrantAction, messages: Messages) {
        val approvals = approvals ?: return
        mutableState.value = state.value.copy(approvalsBusy = true, approvalsMessage = null)
        runOnWork {
            val result = approvals.decideGrant(grantId, action)
            runOnMain {
                mutableState.value = state.value.copy(
                    approvalsBusy = false,
                    approvalsMessage = when (result) {
                        HomeGrantActionResult.Done -> messages.doneTexts.getValue(action)
                        HomeGrantActionResult.Gone -> messages.gone
                        HomeGrantActionResult.NotAllowed -> messages.notAllowed
                        HomeGrantActionResult.Unreachable -> messages.unreachable
                        HomeGrantActionResult.Failed -> messages.failed
                    },
                )
                refresh(showLoading = false)
            }
        }
    }
}
