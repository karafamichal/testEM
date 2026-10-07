package com.ksjd.testem.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ksjd.testem.CardHistoryItem
import com.ksjd.testem.HistorySourceType
import com.ksjd.testem.R
import com.ksjd.testem.ui.Format
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** What a tap in the History insights opens (EM-0028): one month, or one boarding stop. */
sealed interface HistoryFocus {
    data class Month(val month: YearMonth) : HistoryFocus
    data class Stop(val name: String) : HistoryFocus
}

private fun CardHistoryItem.isRide() = sourceType == HistorySourceType.TICKET && !isTopUp && (amountCents ?: 0L) < 0L

/** A month or a stop up close: totals, when you ride (weekdays and hours) and where, and the rides themselves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailSheet(focus: HistoryFocus, items: List<CardHistoryItem>, currency: String, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val at = { i: CardHistoryItem -> Instant.ofEpochMilli(i.timestampMs).atZone(zone) }
    val inFocus = when (focus) {
        is HistoryFocus.Month -> items.filter { YearMonth.from(at(it)) == focus.month }
        is HistoryFocus.Stop -> items.filter { it.stopName == focus.name }
    }
    val rides = inFocus.filter { it.isRide() }
    val spent = inFocus.filter { !it.isTopUp && (it.amountCents ?: 0L) < 0L }.sumOf { -(it.amountCents ?: 0L) }
    val toppedUp = inFocus.filter { it.isTopUp }.sumOf { it.amountCents ?: 0L }
    val byWeekday = DayOfWeek.entries.map { d -> rides.count { at(it).dayOfWeek == d } }
    val byHour = (0 until 24).map { h -> rides.count { at(it).hour == h } }
    val stops = rides.filter { it.stopName.isNotBlank() }.groupingBy { it.stopName }.eachCount().entries.sortedByDescending { it.value }.take(5)
    val title = when (focus) {
        is HistoryFocus.Month -> focus.month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) } + " " + focus.month.year
        is HistoryFocus.Stop -> focus.name
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Figure(stringResource(R.string.history_detail_spent), Format.cents(spent, currency), Modifier.weight(1f))
                Figure(stringResource(R.string.history_detail_rides), rides.size.toString(), Modifier.weight(1f))
                Figure(stringResource(R.string.insights_avg_fare),
                    if (rides.isEmpty()) "–" else Format.cents(rides.sumOf { -(it.amountCents ?: 0L) } / rides.size, currency), Modifier.weight(1f))
            }
            if (toppedUp > 0) Text(stringResource(R.string.history_detail_topped_up, Format.cents(toppedUp, currency)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rides.isNotEmpty()) {
                Text(stringResource(R.string.history_detail_weekdays), style = MaterialTheme.typography.titleSmall)
                Bars(byWeekday, DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()) })
                Text(stringResource(R.string.history_detail_hours), style = MaterialTheme.typography.titleSmall)
                Bars(byHour.chunked(3).map { it.sum() }, (0 until 24 step 3).map { "$it" })
                if (focus is HistoryFocus.Month && stops.isNotEmpty()) {
                    Text(stringResource(R.string.insights_top_stops), style = MaterialTheme.typography.titleSmall)
                    stops.forEach { (stop, count) ->
                        Row {
                            Text(stop, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(pluralStringResource(R.plurals.insights_trips, count, count), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(stringResource(R.string.history_detail_list), style = MaterialTheme.typography.titleSmall)
            inFocus.sortedByDescending { it.timestampMs }.take(60).forEach { item ->
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        Text(listOf(Format.dateTime(item.timestampMs), item.stopName).filter { it.isNotBlank() }.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item.amountCents?.let { c ->
                        Text((if (c > 0) "+" else "−") + Format.cents(kotlin.math.abs(c)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

/** A small bar chart with a label under every bar. */
@Composable
private fun Bars(values: List<Int>, labels: List<String>) {
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (values.size - 1)) / values.size
            values.forEachIndexed { i, v ->
                val h = if (v == 0) 2.dp.toPx() else (v.toFloat() / max) * size.height
                drawRoundRect(if (v == 0) empty else color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            labels.forEachIndexed { i, label ->
                if (i > 0) Spacer(Modifier.padding(start = 6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}
