package com.ksjd.testem.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.R
import com.ksjd.testem.live.TripTracking
import com.ksjd.testem.live.WatchSync
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Plan&Ride watch app shows (EM-0041), set on the phone; changes reach the watch right away. */
@Composable
fun WatchSettings() {
    val context = LocalContext.current
    val prefs = remember { CredentialsManager(context) }
    val found by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { WatchSync.checkWatch(context) } }
    var enabled by remember { mutableStateOf(prefs.getWatchEnabled()) }
    var ongoing by remember { mutableStateOf(prefs.getWatchOngoing()) }
    var alerts by remember { mutableStateOf(prefs.getWatchAlerts()) }
    var nextStops by remember { mutableStateOf(prefs.getWatchNextStops()) }
    var credit by remember { mutableStateOf(prefs.getWatchCredit()) }
    val changed = { TripTracking.refreshWatch(context) }

    Text(
        stringResource(when (found) {
            null -> R.string.watch_checking
            true -> R.string.watch_found
            false -> R.string.watch_not_found
        }),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
    ListGroup {
        ListRow(
            stringResource(R.string.watch_enabled), subtitle = stringResource(R.string.watch_enabled_hint),
            onClick = { enabled = !enabled; prefs.saveWatchEnabled(enabled); changed() },
            trailing = { Switch(enabled, { enabled = it; prefs.saveWatchEnabled(it); changed() }) }
        )
        GroupDivider()
        ListRow(
            stringResource(R.string.watch_ongoing), subtitle = stringResource(R.string.watch_ongoing_hint),
            onClick = { ongoing = !ongoing; prefs.saveWatchOngoing(ongoing); changed() },
            trailing = { Switch(ongoing, { ongoing = it; prefs.saveWatchOngoing(it); changed() }, enabled = enabled) }
        )
        GroupDivider()
        ListRow(
            stringResource(R.string.watch_alerts), subtitle = stringResource(R.string.watch_alerts_hint),
            onClick = { alerts = !alerts; prefs.saveWatchAlerts(alerts) },
            trailing = { Switch(alerts, { alerts = it; prefs.saveWatchAlerts(it) }) }
        )
        GroupDivider()
        ListRow(
            stringResource(R.string.watch_credit), subtitle = stringResource(R.string.watch_credit_hint),
            onClick = { credit = !credit; prefs.saveWatchCredit(credit) },
            trailing = { Switch(credit, { credit = it; prefs.saveWatchCredit(it) }) }
        )
    }
    SectionLabel(stringResource(R.string.watch_next_stops))
    ListGroup {
        listOf(0, 3, 5).forEachIndexed { index, n ->
            if (index > 0) GroupDivider()
            ListRow(
                if (n == 0) stringResource(R.string.watch_next_none) else stringResource(R.string.watch_next_count, n),
                onClick = { nextStops = n; prefs.saveWatchNextStops(n); changed() },
                trailing = { RadioButton(selected = nextStops == n, onClick = null) }
            )
        }
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.watch_report_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
