package com.achappell.hermesrelay

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What the pairing section is showing. Codes shown here are for reading aloud, not secrets. */
internal sealed interface HomePairingViewState {
    data object Idle : HomePairingViewState

    data class Submitting(val host: String) : HomePairingViewState

    data class Waiting(val host: String, val confirmationCode: String) : HomePairingViewState

    data class Paired(
        val host: String,
        val addedCount: Int,
        val pendingOwnerLabels: List<String>,
    ) : HomePairingViewState

    data class Failed(@StringRes val messageRes: Int) : HomePairingViewState
}

/**
 * Runs one pairing attempt against the coordinator. Blocking; call from a
 * worker thread. Kept free of Compose so every transition is unit-testable.
 */
internal class HomePairingSession(
    private val coordinator: HomeClientPairingCoordinator,
    private val publish: (HomePairingViewState) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    fun run(target: HomePairingTarget): HomePairingOutcome? {
        publish(HomePairingViewState.Submitting(target.host))
        val submission = try {
            coordinator.submit(target)
        } catch (error: HomeAdministrationException) {
            publish(HomePairingViewState.Failed(submitFailureMessage(error.reason)))
            return null
        }
        if (cancelled.get()) {
            publish(HomePairingViewState.Idle)
            return HomePairingOutcome.Cancelled
        }
        publish(HomePairingViewState.Waiting(target.host, submission.confirmationCode))
        val outcome = coordinator.awaitApproval(target, submission, cancelled::get)
        publish(
            when (outcome) {
                is HomePairingOutcome.Paired -> HomePairingViewState.Paired(
                    host = target.host,
                    addedCount = outcome.addedProfileIds.size,
                    pendingOwnerLabels = outcome.pendingOwnerLabels,
                )
                HomePairingOutcome.Rejected ->
                    HomePairingViewState.Failed(R.string.android_home_pair_rejected)
                HomePairingOutcome.Expired ->
                    HomePairingViewState.Failed(R.string.android_home_pair_expired)
                HomePairingOutcome.Cancelled -> HomePairingViewState.Idle
                is HomePairingOutcome.Failed ->
                    HomePairingViewState.Failed(finishFailureMessage(outcome.reason))
            },
        )
        return outcome
    }

    companion object {
        @StringRes
        fun inputErrorMessage(error: HomePairingInputError): Int = when (error) {
            HomePairingInputError.NotPairingLink -> R.string.android_home_pair_link_invalid
            HomePairingInputError.HomeAddressInvalid -> R.string.android_home_pair_address_invalid
            HomePairingInputError.CodeInvalid -> R.string.android_home_pair_code_invalid
        }

        @StringRes
        private fun submitFailureMessage(reason: HomeAdministrationError): Int = when (reason) {
            HomeAdministrationError.TransportUnavailable,
            HomeAdministrationError.ServiceUnavailable,
            -> R.string.android_home_pair_unreachable
            HomeAdministrationError.Expired,
            HomeAdministrationError.NotFound,
            HomeAdministrationError.Unauthorized,
            HomeAdministrationError.Forbidden,
            HomeAdministrationError.InvalidRequest,
            -> R.string.android_home_pair_code_rejected
            HomeAdministrationError.SecureStorageUnavailable ->
                R.string.android_home_pair_storage_failed
            else -> R.string.android_home_pair_failed
        }

        @StringRes
        private fun finishFailureMessage(reason: HomeAdministrationError): Int = when (reason) {
            HomeAdministrationError.TransportUnavailable,
            HomeAdministrationError.ServiceUnavailable,
            -> R.string.android_home_pair_unreachable
            HomeAdministrationError.SecureStorageUnavailable ->
                R.string.android_home_pair_storage_failed
            else -> R.string.android_home_pair_failed
        }
    }
}

/**
 * Pair this phone with a Home from a pairing link, or from a typed short code
 * and Home address. [pendingLink] is a link delivered by the system (a scanned
 * QR code or a tapped link); it is submitted once and then consumed.
 */
@Composable
internal fun HomePairingSection(
    coordinator: HomeClientPairingCoordinator,
    pendingLink: String?,
    onPendingLinkConsumed: () -> Unit,
    onPaired: () -> Unit,
) {
    var state by remember { mutableStateOf<HomePairingViewState>(HomePairingViewState.Idle) }
    var link by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var session by remember { mutableStateOf<HomePairingSession?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun start(input: HomePairingInput) {
        val target = when (input) {
            is HomePairingInput.Invalid -> {
                state = HomePairingViewState.Failed(HomePairingSession.inputErrorMessage(input.error))
                return
            }
            is HomePairingInput.Parsed -> input.target
        }
        session?.cancel()
        val next = HomePairingSession(coordinator) { published ->
            scope.launch { state = published }
        }
        session = next
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { next.run(target) }
            if (outcome is HomePairingOutcome.Paired) {
                code = ""
                link = ""
                onPaired()
            }
        }
    }

    LaunchedEffect(pendingLink) {
        pendingLink?.let {
            onPendingLinkConsumed()
            // Keep the delivered link visible so a failed attempt can be retried.
            link = it
            start(HomePairingLink.parse(it))
        }
    }
    // Leaving the sheet stops polling; an approval that lands later is
    // abandoned, and Home expires the request after five minutes.
    DisposableEffect(Unit) {
        onDispose { session?.cancel() }
    }

    Card(
        modifier = Modifier.testTag("android_home_pairing"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.android_home_pair_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.android_home_pair_description),
                style = MaterialTheme.typography.bodySmall,
            )

            val busy = state is HomePairingViewState.Submitting ||
                state is HomePairingViewState.Waiting
            if (!busy && scanning) {
                HomePairingScanner(
                    onLink = { scanned ->
                        scanning = false
                        link = scanned
                        start(HomePairingLink.parse(scanned))
                    },
                    onClose = { scanning = false },
                )
            } else if (!busy) {
                FilledTonalButton(
                    modifier = Modifier.testTag("android_home_pair_scan"),
                    onClick = { scanning = true },
                ) {
                    Text(stringResource(R.string.android_home_pair_scan))
                }
                OutlinedTextField(
                    modifier = Modifier.testTag("android_home_pair_link"),
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(stringResource(R.string.android_home_pair_link)) },
                    singleLine = true,
                )
                FilledTonalButton(
                    modifier = Modifier.testTag("android_home_pair_link_submit"),
                    enabled = link.isNotBlank(),
                    onClick = { start(HomePairingLink.parse(link)) },
                ) {
                    Text(stringResource(R.string.android_home_pair_link_submit))
                }
                OutlinedTextField(
                    modifier = Modifier.testTag("android_home_pair_code"),
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.android_home_pair_code)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.testTag("android_home_pair_address"),
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.android_home_pair_address)) },
                    singleLine = true,
                )
                FilledTonalButton(
                    modifier = Modifier.testTag("android_home_pair_code_submit"),
                    enabled = code.isNotBlank() && address.isNotBlank(),
                    onClick = { start(HomePairingLink.fromTyped(code, address)) },
                ) {
                    Text(stringResource(R.string.android_home_pair_code_submit))
                }
            }

            val status = when (val current = state) {
                HomePairingViewState.Idle -> null
                is HomePairingViewState.Submitting ->
                    stringResource(R.string.android_home_pair_submitting, current.host)
                is HomePairingViewState.Waiting -> stringResource(
                    R.string.android_home_pair_waiting,
                    current.host,
                    current.confirmationCode,
                )
                is HomePairingViewState.Paired -> buildString {
                    append(
                        stringResource(
                            R.string.android_home_pair_done,
                            current.host,
                            current.addedCount,
                        ),
                    )
                    if (current.pendingOwnerLabels.isNotEmpty()) {
                        append(' ')
                        append(
                            stringResource(
                                R.string.android_home_pair_pending_owner,
                                current.pendingOwnerLabels.joinToString(", "),
                            ),
                        )
                    }
                }
                is HomePairingViewState.Failed -> stringResource(current.messageRes)
            }
            status?.let {
                Text(
                    modifier = Modifier
                        .testTag("android_home_pair_status")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (busy) {
                Row {
                    TextButton(
                        modifier = Modifier.testTag("android_home_pair_cancel"),
                        onClick = {
                            session?.cancel()
                            state = HomePairingViewState.Idle
                        },
                    ) {
                        Text(stringResource(R.string.android_home_pair_cancel))
                    }
                }
            }
        }
    }
}
