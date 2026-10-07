package com.ksjd.testem.ui.screens

import com.ksjd.testem.ui.components.WalletSwitch
import com.ksjd.testem.historyItems
import com.ksjd.testem.Wallet
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ksjd.testem.AppViewModel
import com.ksjd.testem.CardHistoryItem
import com.ksjd.testem.HistoryInsights
import com.ksjd.testem.R
import com.ksjd.testem.historyToCsv
import com.ksjd.testem.ui.Format
import com.ksjd.testem.ui.components.EmptyState
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.NoticeTone
import com.ksjd.testem.ui.components.ScreenHeader
import com.ksjd.testem.ui.components.SectionLabel
import com.ksjd.testem.ui.theme.TimeStyle
import com.ksjd.testem.ui.theme.TransitTheme
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: AppViewModel) {
    val ticket by viewModel.ticketState.collectAsState()
    val ids by viewModel.idsState.collectAsState()
    val app by viewModel.appState.collectAsState()
    val history = ticket.historyState
    // SAD Zvolen, IDS BBSK, or both merged (the selector on top when both accounts are saved, EM-0023).
    val shownWallet = when {
        app.bothAccounts -> app.historyWallet
        app.hasIdsAccount -> Wallet.IDS
        else -> Wallet.SADZV
    }
    val idsItems = remember(ids.account) { ids.account?.historyItems().orEmpty() }
    val items = remember(history.items, idsItems, shownWallet, app.hasAccount) {
        val sadzv = if (app.hasAccount) history.items else emptyList()
        when (shownWallet) {
            Wallet.SADZV -> sadzv
            Wallet.IDS -> idsItems
            null -> (sadzv + idsItems).sortedByDescending { it.timestampMs }
        }
    }
    val refresh = { if (app.hasAccount) viewModel.loadCardHistory(); viewModel.refreshIds() }
    val context = LocalContext.current
    val exportedMessage = stringResource(R.string.history_exported)
    val exportFailedMessage = stringResource(R.string.history_export_failed)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(historyToCsv(items).toByteArray()) }
        }.isSuccess
        Toast.makeText(context, if (ok) exportedMessage else exportFailedMessage, Toast.LENGTH_SHORT).show()
    }

    // Right after unlocking, the app is still signing in: load as soon as that's done, not only on the next visit.
    LaunchedEffect(ticket.selectedSnr, app.isLoggedIn) {
        if (app.isLoggedIn && history.lastUpdatedMs == 0L) viewModel.loadCardHistory()
    }
    // Nothing fetched yet (signing in or loading): a spinner, not "no history".
    val waiting = items.isEmpty() && if (shownWallet == Wallet.IDS) ids.isLoading || ids.account == null
        else app.hasAccount && history.errorMessage.isBlank() && (history.isLoading || history.lastUpdatedMs == 0L)

    val card = ticket.selectedCard
    val balance = when (shownWallet) {
        Wallet.SADZV -> card?.creditLastBalance
        Wallet.IDS -> ids.account?.credit
        null -> listOfNotNull(card?.creditLastBalance, ids.account?.credit).takeIf { it.isNotEmpty() }?.sum()
    }
    val insights = remember(items, balance) { HistoryInsights.from(items, balance) }
    var focus by remember { mutableStateOf<HistoryFocus?>(null) }
    focus?.let { HistoryDetailSheet(it, items, card?.currencySymbol ?: "€") { focus = null } }
    val grouped = remember(items) {
        items.groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestampMs).atZone(ZoneId.systemDefault())) }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ScreenHeader(
                title = stringResource(R.string.history_title),
                actions = {
                    IconButton(onClick = refresh, enabled = !history.isLoading && !ids.isLoading) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_button))
                    }
                    IconButton(
                        onClick = { exporter.launch("testem-history-${java.time.LocalDate.now()}.csv") },
                        enabled = items.isNotEmpty()
                    ) {
                        Icon(Icons.Outlined.FileDownload, stringResource(R.string.history_export))
                    }
                }
            )
        }
        if (app.bothAccounts) item {
            WalletSwitch(shownWallet, viewModel::setHistoryWallet, withBoth = true, modifier = Modifier.padding(bottom = 8.dp))
        }
        when {
            waiting -> item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            shownWallet != Wallet.IDS && history.errorMessage.isNotBlank() && items.isEmpty() -> item {
                Notice(
                    history.errorMessage,
                    tone = NoticeTone.Error,
                    actionLabel = stringResource(R.string.retry),
                    onAction = refresh,
                    modifier = Modifier.padding(16.dp)
                )
            }
            items.isEmpty() -> item {
                EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty))
            }
            else -> {
                item { InsightsPanel(insights, card?.currencySymbol ?: "€") { focus = it } }
                grouped.forEach { (month, monthItems) ->
                    item(key = "m-$month") { SectionLabel(monthTitle(month)) }
                    item(key = "g-$month") {
                        ListGroup {
                            monthItems.forEachIndexed { index, entry ->
                                if (index > 0) GroupDivider()
                                HistoryRow(entry, tagged = shownWallet == null)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun monthTitle(month: YearMonth): String {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    return if (month.year == YearMonth.now().year) name else "$name ${month.year}"
}

@Composable
private fun HistoryRow(item: CardHistoryItem, tagged: Boolean = false) {
    val positive = (item.amountCents ?: 0L) > 0
    // Merged history: which account each line is from.
    val subtitle = listOf(
        Format.dateTime(item.timestampMs), item.stopName,
        if (tagged) walletName(item.wallet) else ""
    ).filter { it.isNotBlank() }.joinToString(", ")
    ListRow(
        title = item.title,
        subtitle = subtitle,
        trailing = {
            if (item.amountText.isNotBlank()) {
                Text(
                    item.amountCents?.let { (if (it > 0) "+" else "−") + Format.cents(kotlin.math.abs(it)) } ?: item.amountText,
                    style = TimeStyle,
                    color = if (positive) TransitTheme.colors.onTime else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

@Composable
private fun InsightsPanel(insights: HistoryInsights, currency: String, onOpen: (HistoryFocus) -> Unit) {
    val thisMonth = insights.thisMonth
    val lastMonth = insights.lastMonth
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(16.dp)) {
            // Tap the month, a bar or a stop for a closer look (EM-0028).
            Column(Modifier.clickable { onOpen(HistoryFocus.Month(thisMonth.month)) }) {
            Text(stringResource(R.string.insights_this_month), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Format.cents(thisMonth.spentCents, currency), style = MaterialTheme.typography.displayMedium)
            Text(
                pluralStringResource(R.plurals.insights_trips, thisMonth.trips, thisMonth.trips) + ". " +
                    stringResource(R.string.insights_last_month, Format.cents(lastMonth.spentCents, currency)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
            Spacer(Modifier.height(16.dp))
            SpendChart(insights) { onOpen(HistoryFocus.Month(it)) }
            Spacer(Modifier.height(16.dp))

            if (insights.averageFareCents != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.insights_avg_fare), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Format.cents(insights.averageFareCents, currency), style = MaterialTheme.typography.titleLarge)
                    }
                    if (insights.tripsLeft != null) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.insights_trips_left_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                pluralStringResource(R.plurals.insights_trips_left, insights.tripsLeft, insights.tripsLeft),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
            }
            if (insights.topStops.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.insights_top_stops), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                insights.topStops.forEach { (stop, count) ->
                    Row(Modifier.clickable { onOpen(HistoryFocus.Stop(stop)) }.padding(vertical = 6.dp)) {
                        Text(stop, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(pluralStringResource(R.plurals.insights_trips, count, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (insights.oldestRecordMs > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.insights_basis, Format.date(insights.oldestRecordMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpendChart(insights: HistoryInsights, onMonth: (YearMonth) -> Unit) {
    val current = MaterialTheme.colorScheme.primary
    val past = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val baseline = MaterialTheme.colorScheme.outline
    val max = (insights.months.maxOf { it.spentCents }).coerceAtLeast(1L)
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .pointerInput(insights.months) {
                    detectTapGestures { tap ->
                        val count = insights.months.size
                        val index = (tap.x / size.width * count).toInt().coerceIn(0, count - 1)
                        onMonth(insights.months[index].month)
                    }
                }
        ) {
            val count = insights.months.size
            val gap = 10.dp.toPx()
            val barWidth = (size.width - gap * (count - 1)) / count
            insights.months.forEachIndexed { index, month ->
                val h = (month.spentCents.toFloat() / max) * (size.height - 2.dp.toPx())
                val x = index * (barWidth + gap)
                if (h > 0f) {
                    drawRoundRect(
                        color = if (index == count - 1) current else past,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                }
            }
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.padding(top = 4.dp)) {
            insights.months.forEachIndexed { index, month ->
                if (index > 0) Spacer(Modifier.width(10.dp))
                Text(
                    month.month.month.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()),
                    modifier = Modifier.weight(1f).clickable { onMonth(month.month) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
