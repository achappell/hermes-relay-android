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
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** What the Conversations sheet is showing. */
internal sealed interface HomeConversationsState {
    data object Loading : HomeConversationsState

    data class Listed(val sessions: List<HomeClientSession>) : HomeConversationsState

    data class Unavailable(val message: String) : HomeConversationsState
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
