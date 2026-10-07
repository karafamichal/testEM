package com.ksjd.testem.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R
import com.ksjd.testem.TimetableConnection
import com.ksjd.testem.TimetableService
import com.ksjd.testem.ui.Format
import com.ksjd.testem.ui.components.LinePlate
import com.ksjd.testem.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun short(name: String) = name.substringAfter(", ").ifBlank { name }

/**
 * The rider's own change (EM-0026, EM-0030): 1. where to get off the bus, 2. where to get on the next one
 * (a stop nearby, or any stop searched), and a preview of the rest of the journey from there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransferEditor(connection: TimetableConnection, index: Int, onDismiss: () -> Unit, onApply: (TimetableConnection) -> Unit) {
    val service = remember { TimetableService() }
    val bus = connection.segments[index - 1]
    val next = connection.segments[index]
    var stops by remember { mutableStateOf<List<TimetableService.LegStop>?>(null) }
    var off by remember { mutableStateOf<TimetableService.LegStop?>(null) }
    var near by remember { mutableStateOf(emptyList<TimetableService.BoardStop>()) }
    var board by remember { mutableStateOf<TimetableService.BoardStop?>(null) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<com.ksjd.testem.CpStopSuggestion>()) }
    var preview by remember { mutableStateOf<TimetableConnection?>(null) }
    var previewing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { runCatching { service.stopsAfterBoarding(bus) }.getOrDefault(emptyList()) }
        stops = list
        off = list.firstOrNull { it.name == bus.arrivalStop } ?: list.firstOrNull()
    }
    // Stops to get on near where the rider gets off; the one the connection uses now is picked when it's there.
    LaunchedEffect(off) {
        val o = off ?: return@LaunchedEffect
        near = withContext(Dispatchers.IO) { runCatching { service.boardStopsNear(o) }.getOrDefault(emptyList()) }
        board = near.firstOrNull { it.stop.selectedText == next.departureStop } ?: near.firstOrNull()
    }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { found = emptyList(); return@LaunchedEffect }
        delay(300)
        found = withContext(Dispatchers.IO) { service.suggestStops("", query).getOrDefault(emptyList()).take(5) }
    }
    LaunchedEffect(off, board) {
        val o = off ?: return@LaunchedEffect
        val b = board ?: return@LaunchedEffect
        previewing = true
        preview = withContext(Dispatchers.IO) { runCatching { service.editTransfer(connection, index, o, b) }.getOrNull() }
        previewing = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.transfer_title), style = MaterialTheme.typography.headlineSmall)

            // 1 — where to get off
            Step(1, stringResource(R.string.transfer_step_off, bus.line.substringAfterLast(' ')))
            val list = stops
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(stringResource(R.string.transfer_no_stops), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    val state = rememberLazyListState(initialFirstVisibleItemIndex = (list.indexOf(off) - 1).coerceAtLeast(0))
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp), state = state) {
                            itemsIndexed(list) { _, stop ->
                                val selected = stop == off
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { off = stop; query = ""; found = emptyList() }
                                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                                    Spacer(Modifier.width(12.dp))
                                    Text(Format.clock(stop.arrivalMs), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp))
                                    Text(short(stop.name), style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (stop.name == bus.arrivalStop) Text(stringResource(R.string.transfer_now), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            // 2 — where to get on: stops nearby (walk), or any stop
            Step(2, stringResource(R.string.transfer_step_on))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                near.forEach { b ->
                    FilterChip(
                        selected = b.stop.value == board?.stop?.value,
                        onClick = { board = b },
                        label = {
                            Text(short(b.stop.selectedText) + "  ·  " + if (b.walkSeconds == 0L) stringResource(R.string.transfer_here)
                                else stringResource(R.string.transfer_walk, ((b.walkSeconds + 30) / 60).toInt()))
                        },
                        leadingIcon = if (b.walkSeconds > 0) ({ Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null, Modifier.size(16.dp)) }) else null
                    )
                }
            }
            OutlinedTextField(
                query, { query = it },
                label = { Text(stringResource(R.string.transfer_other_stop)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            found.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val o = off
                            board = TimetableService.BoardStop(s, service.walkSeconds(o?.lat, o?.lon, s.coorX?.toDoubleOrNull(), s.coorY?.toDoubleOrNull()))
                            query = ""
                            found = emptyList()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) { Text(s.selectedText, style = MaterialTheme.typography.bodyLarge) }
            }
            board?.takeIf { b -> near.none { it.stop.value == b.stop.value } }?.let { b ->
                Text(stringResource(R.string.transfer_chosen, b.stop.selectedText, ((b.walkSeconds + 30) / 60).toInt()),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            // The rest of the journey from there
            SectionLabel(stringResource(R.string.transfer_then), modifier = Modifier.padding(start = 0.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val p = preview
                    when {
                        previewing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        p == null -> Text(stringResource(R.string.transfer_none_from), style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            p.segments.drop(index - 1).forEachIndexed { i, s ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinePlate(s.line.substringAfterLast(' '))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (i == 0) stringResource(R.string.transfer_off_at, short(s.arrivalStop), s.arrivalTime)
                                        else "${s.departureTime} ${short(s.departureStop)} → ${s.arrivalTime} ${short(s.arrivalStop)}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            Text(stringResource(R.string.transfer_arrive, p.arrivalTime, p.totalDuration),
                                style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            Button(
                onClick = { preview?.let(onApply) },
                enabled = preview != null && !previewing,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.transfer_apply)) }
        }
    }
}

@Composable
private fun Step(number: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}
