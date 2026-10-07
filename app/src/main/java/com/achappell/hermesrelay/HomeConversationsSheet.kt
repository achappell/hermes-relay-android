package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** What the Conversations sheet is showing. */
internal sealed interface HomeConversationsState {
    data object Loading : HomeConversationsState

    data class Listed(val sessions: List<HomeClientSession>) : HomeConversationsState

    data class Unavailable(val message: String) : HomeConversationsState
}

/** Claim-management contents are hidden until Home has advertised claim_ref support. */
internal sealed interface HomeOpenClaimsState {
    data object Hidden : HomeOpenClaimsState

    data object Loading : HomeOpenClaimsState

    data class Listed(
        val pairingId: String,
        val maxClaims: Int,
        val claims: List<HomeClientClaim>,
    ) : HomeOpenClaimsState

    data class Unavailable(val message: String) : HomeOpenClaimsState
}

/** Rejects delayed list/close responses after a newer Profile load begins. */
internal class HomeProfileRequestGate {
    data class Request(
        val generation: Long,
        val profileId: String?,
    )

    private var generation = 0L

    @Synchronized
    fun begin(profileId: String?): Request = Request(++generation, profileId)

    @Synchronized
    fun invalidate() {
        generation += 1
    }

    @Synchronized
    fun isCurrent(request: Request, selectedProfileId: String?): Boolean =
        request.generation == generation && request.profileId == selectedProfileId
}

/** How one listed conversation may be used from this phone. */
internal enum class HomeConversationRow {
    Current,
    Resumable,

    /** Another device holds it; Home would refuse a resume as busy. */
    InUseElsewhere,
}

internal object HomeConversationRows {
    fun classify(session: HomeClientSession, currentRef: String?): HomeConversationRow = when {
        session.sessionRef == currentRef -> HomeConversationRow.Current
        session.active -> HomeConversationRow.InUseElsewhere
        else -> HomeConversationRow.Resumable
    }

    /** The divider recorded in Local History when a conversation is resumed. */
    fun resumedDivider(title: String?): String =
        "Resumed: ${title?.takeIf { it.isNotBlank() } ?: UNTITLED}"

    const val UNTITLED = "Untitled conversation"
}

internal object HomeOpenClaimRows {
    fun closeableRefs(claims: List<HomeClientClaim>, currentClaimRef: String?): List<String> =
        claims.filter { it.claimRef != currentClaimRef }.map { it.claimRef }

    fun startedAt(claim: HomeClientClaim): Double = claim.openedAt ?: claim.createdAt

    fun stateLabel(state: HomeClientClaimState): Int = when (state) {
        HomeClientClaimState.Connecting -> R.string.android_home_claim_state_connecting
        HomeClientClaimState.Idle -> R.string.android_home_claim_state_idle
        HomeClientClaimState.Replying -> R.string.android_home_claim_state_replying
        HomeClientClaimState.WaitingToReconnect -> R.string.android_home_claim_state_waiting
    }
}

/**
 * A paired Profile's Home conversations: start a new one, rename the current
 * one, or resume another. Actions are disabled while a turn is in progress so
 * a switch can never strand or replay an uncertain turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeConversationsSheet(
    state: HomeConversationsState,
    currentRef: String?,
    canRename: Boolean,
    actionsEnabled: Boolean,
    renameMessage: String?,
    onNew: () -> Unit,
    onResume: (HomeClientSession) -> Unit,
    onRename: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    openClaimsState: HomeOpenClaimsState = HomeOpenClaimsState.Hidden,
    currentClaimRef: String? = null,
    claimManagementMessage: String? = null,
    onCloseClaims: (String, List<String>) -> Unit = { _, _ -> },
    onRefreshOpenClaims: () -> Unit = {},
) {
    ModalBottomSheet(
        modifier = Modifier.testTag("android_conversations_sheet"),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.android_conversations_label),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!actionsEnabled) {
                Text(
                    text = stringResource(R.string.android_conversations_turn_active),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(
                modifier = Modifier.testTag("android_conversations_new"),
                enabled = actionsEnabled,
                onClick = onNew,
            ) {
                Text(stringResource(R.string.android_conversations_new))
            }

            if (canRename) {
                var title by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("android_conversations_rename_field"),
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.android_conversations_rename_label)) },
                    singleLine = true,
                )
                TextButton(
                    modifier = Modifier.testTag("android_conversations_rename"),
                    enabled = actionsEnabled && title.isNotBlank(),
                    onClick = { onRename(title) },
                ) {
                    Text(stringResource(R.string.android_conversations_rename))
                }
                renameMessage?.let {
                    Text(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HomeOpenClaimsSection(
                state = openClaimsState,
                currentClaimRef = currentClaimRef,
                actionsEnabled = actionsEnabled,
                message = claimManagementMessage,
                onCloseClaims = onCloseClaims,
                onRefresh = onRefreshOpenClaims,
            )

            when (state) {
                HomeConversationsState.Loading -> Text(
                    text = stringResource(R.string.android_conversations_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is HomeConversationsState.Unavailable -> {
                    Text(text = state.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.android_conversations_retry))
                    }
                }
                is HomeConversationsState.Listed -> {
                    if (state.sessions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.android_conversations_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    state.sessions.forEach { session ->
                        ConversationRow(
                            session = session,
                            row = HomeConversationRows.classify(session, currentRef),
                            started = if (session.startedAt > 0) {
                                dateFormat.format(Date((session.startedAt * 1000).toLong()))
                            } else {
                                null
                            },
                            actionsEnabled = actionsEnabled,
                            onResume = { onResume(session) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeOpenClaimsSection(
    state: HomeOpenClaimsState,
    currentClaimRef: String?,
    actionsEnabled: Boolean,
    message: String?,
    onCloseClaims: (String, List<String>) -> Unit,
    onRefresh: () -> Unit,
) {
    when (state) {
        HomeOpenClaimsState.Hidden -> Unit
        HomeOpenClaimsState.Loading -> {
            Text(
                text = stringResource(R.string.android_home_claims_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.android_home_claims_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is HomeOpenClaimsState.Unavailable -> {
            Text(
                text = stringResource(R.string.android_home_claims_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = state.message, style = MaterialTheme.typography.bodyMedium)
            message?.let {
                Text(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                modifier = Modifier.testTag("android_home_claims_retry"),
                onClick = onRefresh,
            ) {
                Text(stringResource(R.string.android_home_claims_retry))
            }
        }
        is HomeOpenClaimsState.Listed -> {
            Text(
                text = stringResource(
                    R.string.android_home_claims_header,
                    state.claims.size,
                    state.maxClaims,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            message?.let {
                Text(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.claims.isEmpty()) {
                Text(
                    text = stringResource(R.string.android_home_claims_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val closeableRefs = HomeOpenClaimRows.closeableRefs(state.claims, currentClaimRef)
            if (closeableRefs.isNotEmpty()) {
                TextButton(
                    modifier = Modifier.testTag("android_home_claims_close_all_others"),
                    enabled = actionsEnabled,
                    onClick = { onCloseClaims(state.pairingId, closeableRefs) },
                ) {
                    Text(stringResource(R.string.android_home_claims_close_all_others))
                }
            }
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            state.claims.forEachIndexed { index, claim ->
                val isCurrent = claim.claimRef == currentClaimRef
                val title = claim.title?.takeIf(String::isNotBlank)
                    ?: HomeConversationRows.UNTITLED
                val profile = claim.profileLabel?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.android_home_claims_profile_unknown)
                val started = dateFormat.format(
                    Date((HomeOpenClaimRows.startedAt(claim) * 1000).toLong()),
                )
                val stateLabel = stringResource(HomeOpenClaimRows.stateLabel(claim.state))
                val rowDescription = listOfNotNull(
                    title,
                    profile,
                    started,
                    stateLabel,
                    if (isCurrent) stringResource(R.string.android_home_claims_current) else null,
                ).joinToString(", ")
                val closeDescription = stringResource(
                    R.string.android_home_claims_close_description,
                    title,
                )
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("android_home_claim_row_$index"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                contentDescription = rowDescription
                            }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = title, style = MaterialTheme.typography.titleSmall)
                        Text(text = profile, style = MaterialTheme.typography.bodySmall)
                        Text(text = started, style = MaterialTheme.typography.bodySmall)
                        Text(text = stateLabel, style = MaterialTheme.typography.bodySmall)
                        if (isCurrent) {
                            Text(
                                text = stringResource(R.string.android_home_claims_current),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        } else {
                            TextButton(
                                modifier = Modifier
                                    .testTag("android_home_claim_close_$index")
                                    .semantics {
                                        contentDescription = closeDescription
                                    },
                                enabled = actionsEnabled,
                                onClick = {
                                    onCloseClaims(state.pairingId, listOf(claim.claimRef))
                                },
                            ) {
                                Text(stringResource(R.string.android_home_claims_close))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    session: HomeClientSession,
    row: HomeConversationRow,
    started: String?,
    actionsEnabled: Boolean,
    onResume: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("android_conversation_row"),
        colors = CardDefaults.cardColors(
            containerColor = if (row == HomeConversationRow.Current) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = session.title.ifBlank { HomeConversationRows.UNTITLED },
                style = MaterialTheme.typography.titleSmall,
            )
            val details = listOfNotNull(
                started,
                pluralStringResource(
                    R.plurals.android_conversations_messages,
                    session.messageCount,
                    session.messageCount,
                ),
            ).joinToString(" · ")
            Text(text = details, style = MaterialTheme.typography.bodySmall)
            Row {
                when (row) {
                    HomeConversationRow.Current -> Text(
                        text = stringResource(R.string.android_conversations_current),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    HomeConversationRow.InUseElsewhere -> Text(
                        text = stringResource(R.string.android_conversations_in_use),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    HomeConversationRow.Resumable -> TextButton(
                        modifier = Modifier.testTag("android_conversation_resume"),
                        enabled = actionsEnabled,
                        onClick = onResume,
                    ) {
                        Text(stringResource(R.string.android_conversations_resume))
                    }
                }
            }
        }
    }
}
