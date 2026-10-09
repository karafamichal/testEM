package com.ksjd.testem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.R
import com.ksjd.testem.hub.HubClient
import com.ksjd.testem.live.ShareTrip
import com.ksjd.testem.live.TrackedTrip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Share a bus: as a link (anyone can open it), or by a one-time code told to someone. Whoever enters the code
 * sends their name with it; this dialog shows it and the sharer accepts or declines. Only then does the bus go over.
 */
@Composable
fun ShareTripDialog(trip: TrackedTrip, destination: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<HubClient.ShareCode?>(null) }
    var state by remember { mutableStateOf<HubClient.ShareState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val made = code
    // The hub says over a WebSocket the moment someone enters the code (with their name).
    val socket = remember(made) {
        made?.let { m -> HubClient.ShareSocket("owner", m.id, m.token, onStatus = { status, name, _ -> state = HubClient.ShareState(status, name) }, onPosition = {}).open() }
    }
    androidx.compose.runtime.DisposableEffect(socket) { onDispose { socket?.close() } }
    // A code nobody entered runs out after 10 minutes.
    LaunchedEffect(made) {
        if (made != null) {
            delay((made.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0))
            if (state == null || state?.status == "waiting") state = HubClient.ShareState("expired", "")
        }
    }
    val close = {
        // Closing while the code still waits takes it back.
        if (made != null && state?.status.let { it == null || it == "waiting" || it == "asked" }) {
            scope.launch { runCatching { HubClient.answerShare(made.id, made.token, "cancel") } }
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.share_trip)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    made == null -> {
                        Text(stringResource(R.string.share_how), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { ShareTrip.share(context, trip, destination); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.share_link))
                        }
                        OutlinedButton(
                            onClick = {
                                loading = true
                                scope.launch {
                                    runCatching { HubClient.createShare(ShareTrip.payload(trip), sharerLabel(context, trip, destination)) }
                                        .onSuccess { code = it }.onFailure { error = context.getString(R.string.share_code_failed) }
                                    loading = false
                                }
                            },
                            enabled = !loading && HubClient.isConfigured,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.share_code)) }
                        if (loading) CircularProgressIndicator()
                    }
                    state?.status == "asked" -> {
                        Text(stringResource(R.string.share_asked, state!!.name), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.share_asked_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state?.status == "accepted" -> Text(stringResource(R.string.share_accepted_location, state!!.name))
                    state?.status == "declined" -> Text(stringResource(R.string.share_declined_self))
                    state?.status == "expired" -> Text(stringResource(R.string.share_expired))
                    else -> {
                        Text(stringResource(R.string.share_code_tell), style = MaterialTheme.typography.bodyMedium)
                        Text(made.code.chunked(3).joinToString(" "), fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Text(stringResource(R.string.share_code_valid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state?.status == "asked" && made != null) {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { HubClient.answerShare(made.id, made.token, "accept") }.onSuccess {
                            // From now on, while following, they see where this phone is (and it sees them, if they send it).
                            com.ksjd.testem.live.Shares.addOutgoing(context, com.ksjd.testem.live.Shares.Outgoing(made.id, made.token, state?.name.orEmpty()))
                        }
                        state = state?.copy(status = "accepted")
                    }
                }) {
                    Text(stringResource(R.string.share_accept))
                }
            } else TextButton(onClick = close) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            if (state?.status == "asked" && made != null) {
                TextButton(onClick = { scope.launch { runCatching { HubClient.answerShare(made.id, made.token, "decline") } }; onDismiss() }) {
                    Text(stringResource(R.string.share_decline))
                }
            }
        }
    )
}

/**
 * Enter a code someone told you: it goes with your name (from your SAD Zvolen or IDS BBSK account, editable), and the
 * bus opens here once they accept.
 */
@Composable
fun EnterShareCodeDialog(onTrip: (TrackedTrip) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { CredentialsManager(context) }
    var code by remember { mutableStateOf("") }
    var name by remember {
        mutableStateOf(prefs.getLastAccount()?.userName?.takeIf { it.isNotBlank() } ?: prefs.getLastIdsAccount()?.name.orEmpty())
    }
    var claim by remember { mutableStateOf<HubClient.ShareClaim?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val sent = claim
    // The sharer's answer comes over a WebSocket right away.
    var answer by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val socket = remember(sent) {
        sent?.let { c -> HubClient.ShareSocket("claim", c.claimId, c.token, onStatus = { st, _, trip -> answer = st to trip }, onPosition = {}).open() }
    }
    androidx.compose.runtime.DisposableEffect(socket) { onDispose { socket?.close() } }
    LaunchedEffect(answer) {
        val (now, payload) = answer ?: return@LaunchedEffect
        if (sent != null) {
            status = now
            if (now == "accepted") {
                val trip = payload?.let(ShareTrip::decode)
                if (trip != null) {
                    com.ksjd.testem.live.Shares.addIncoming(context, com.ksjd.testem.live.Shares.Incoming(
                        sent.claimId, sent.token, sent.label, trip.ref.line, trip.ref.tripNumber, trip.ref.lineId))
                    onTrip(trip)
                } else error = context.getString(R.string.share_bad_link)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_enter_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    sent == null -> {
                        OutlinedTextField(code, { v -> code = v.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.share_enter_code)) },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.share_your_name)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.share_enter_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    status == "declined" || status == "cancelled" -> Text(stringResource(R.string.share_declined))
                    else -> {
                        Text(stringResource(R.string.share_waiting, sent.label))
                        CircularProgressIndicator()
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (sent == null) {
                TextButton(
                    onClick = {
                        error = null
                        scope.launch {
                            runCatching { HubClient.claimShare(code, name.trim()) }
                                .onSuccess { claim = it }
                                .onFailure { error = context.getString(R.string.share_code_wrong) }
                        }
                    },
                    enabled = code.length == 6 && name.isNotBlank()
                ) { Text(stringResource(R.string.share_send)) }
            } else TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = { if (sent == null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** What the other side sees: who shares (the account's name, if any) and the bus. */
private fun sharerLabel(context: android.content.Context, trip: TrackedTrip, destination: String): String {
    val prefs = CredentialsManager(context)
    val name = prefs.getLastAccount()?.userName?.takeIf { it.isNotBlank() } ?: prefs.getLastIdsAccount()?.name.orEmpty()
    val bus = "${trip.ref.line} → ${destination.ifBlank { trip.ref.destination }}"
    return if (name.isBlank()) bus else "$name · $bus"
}
