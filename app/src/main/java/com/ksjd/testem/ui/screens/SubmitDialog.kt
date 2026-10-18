package com.ksjd.testem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ksjd.testem.CpStopSuggestion
import com.ksjd.testem.R
import com.ksjd.testem.hub.HubClient
import kotlinx.coroutines.launch

/** What a rider can send: a connection the timetable lacks, a stop it lacks, or a problem at a stop. */
enum class SubmitKind(val label: Int) { Trip(R.string.submit_kind_trip), Stop(R.string.submit_kind_stop), Problem(R.string.submit_kind_problem) }

/**
 * 2.17.0: a rider tells us what the timetable gets wrong. Nothing changes until an admin approves it in emHUB. A
 * connection is the two stops already in the planner ([from], [to]) with its times; a stop is where the phone is now.
 */
@Composable
fun SubmitDialog(
    initial: SubmitKind,
    from: CpStopSuggestion? = null,
    to: CpStopSuggestion? = null,
    stopName: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.ksjd.testem.CredentialsManager(context) }
    var kind by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(prefs.getSubmitName()) }
    var line by remember { mutableStateOf("") }
    var leaves by remember { mutableStateOf("") }
    var arrives by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf(java.time.LocalDate.now(com.ksjd.testem.SK_ZONE).dayOfWeek.value - 1)) }
    var newStop by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clock = Regex("""\d{1,2}:\d{2}""")
    // A connection needs both stops picked from the list (the timetable's own stops) and its two times.
    val tripReady = from?.value2 == com.ksjd.testem.TimetableService.HUB_STOP && to?.value2 == com.ksjd.testem.TimetableService.HUB_STOP
    val ready = name.isNotBlank() && !sending && when (kind) {
        SubmitKind.Trip -> tripReady && line.isNotBlank() && clock.matches(leaves.trim()) && clock.matches(arrives.trim()) && days.isNotEmpty()
        SubmitKind.Stop -> newStop.isNotBlank()
        SubmitKind.Problem -> text.isNotBlank() && stopName.isNotBlank()
    }

    fun send() {
        sending = true
        error = null
        scope.launch {
            val body = JsonObject().apply {
                addProperty("kind", kind.name.lowercase())
                addProperty("name", name.trim())
                when (kind) {
                    SubmitKind.Trip -> {
                        addProperty("line", line.trim())
                        add("weekdays", JsonArray().apply { days.sorted().forEach { add(it) } })
                        add("stops", JsonArray().apply {
                            add(JsonArray().apply { add(from!!.value); add(leaves.trim().padStart(5, '0')) })
                            add(JsonArray().apply { add(to!!.value); add(arrives.trim().padStart(5, '0')) })
                        })
                    }
                    SubmitKind.Stop -> {
                        val at = com.ksjd.testem.live.Locations.current(context, maxAgeMs = 60_000L)
                        if (at == null) { error = context.getString(R.string.submit_no_location); sending = false; return@launch }
                        addProperty("stopName", newStop.trim()); addProperty("lat", at.latitude); addProperty("lon", at.longitude)
                    }
                    SubmitKind.Problem -> { addProperty("station", stopName); addProperty("text", text.trim()) }
                }
            }
            prefs.saveSubmitName(name.trim())
            runCatching { HubClient.submit(body) }
                .onSuccess {
                    android.widget.Toast.makeText(context, R.string.submit_thanks, android.widget.Toast.LENGTH_LONG).show()
                    onDismiss()
                }
                .onFailure { error = it.message ?: context.getString(R.string.submit_failed); sending = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.submit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubmitKind.entries.filter { it != SubmitKind.Problem || stopName.isNotBlank() }.forEach {
                        FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(stringResource(it.label)) })
                    }
                }
                when (kind) {
                    SubmitKind.Trip -> {
                        Text(
                            if (tripReady) stringResource(R.string.submit_trip_between, from!!.selectedText, to!!.selectedText)
                            else stringResource(R.string.submit_trip_pick),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (tripReady) {
                            OutlinedTextField(line, { line = it.take(12) }, label = { Text(stringResource(R.string.submit_line)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(leaves, { leaves = it.take(5) }, label = { Text(stringResource(R.string.submit_leaves)) }, placeholder = { Text("13:26") },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                                OutlinedTextField(arrives, { arrives = it.take(5) }, label = { Text(stringResource(R.string.submit_arrives)) }, placeholder = { Text("13:40") },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            }
                            val dayNames = java.time.DayOfWeek.entries.map { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                dayNames.forEachIndexed { i, d ->
                                    FilterChip(selected = i in days, onClick = { days = if (i in days) days - i else days + i }, label = { Text(d) })
                                }
                            }
                        }
                    }
                    SubmitKind.Stop -> {
                        Text(stringResource(R.string.submit_stop_here), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(newStop, { newStop = it.take(80) }, label = { Text(stringResource(R.string.submit_stop_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    SubmitKind.Problem -> {
                        Text(stopName, style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(text, { text = it.take(2000) }, label = { Text(stringResource(R.string.submit_problem)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.submit_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.submit_review_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = ::send, enabled = ready) { Text(stringResource(R.string.submit_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
