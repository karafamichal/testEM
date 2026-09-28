package com.ksjd.testem.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R
import com.ksjd.testem.RecurringRide
import com.ksjd.testem.SK_ZONE
import com.ksjd.testem.parseClock
import com.ksjd.testem.rides.RideScheduler
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.NoticeTone
import com.ksjd.testem.ui.components.SectionLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Regular rides in the planner: next run each, tap to edit. */
@Composable
internal fun RidesList(rides: List<RecurringRide>, onEdit: (RecurringRide) -> Unit) {
    SectionLabel(stringResource(R.string.rides_title))
    if (rides.isEmpty()) {
        Text(
            stringResource(R.string.rides_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        return
    }
    val now = ZonedDateTime.now(SK_ZONE)
    ListGroup {
        rides.forEachIndexed { index, ride ->
            if (index > 0) GroupDivider()
            val next = ride.nextDeparture(now)
            ListRow(
                title = ride.label,
                subtitle = listOf(
                    ride.time + " · " + daysLabel(ride),
                    if (next != null) stringResource(R.string.rides_next, rideDay(next), "%d:%02d".format(next.hour, next.minute))
                    else stringResource(if (ride.enabled) R.string.rides_no_next else R.string.rides_paused)
                ).joinToString("\n"),
                onClick = { onEdit(ride) },
                leading = { Icon(Icons.Outlined.EventRepeat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }
    }
}

@Composable
private fun daysLabel(ride: RecurringRide): String = when {
    ride.days == RecurringRide.EVERY_DAY -> stringResource(R.string.rides_every_day)
    ride.days == RecurringRide.WORKDAYS -> stringResource(R.string.rides_workdays)
    ride.days == setOf(6, 7) -> stringResource(R.string.rides_weekends)
    ride.days.isEmpty() -> stringResource(R.string.rides_dates_only)
    else -> ride.days.sorted().joinToString(" ") { dayName(DayOfWeek.of(it)) }
}

private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

private fun shortDate(iso: String): String =
    runCatching { LocalDate.parse(iso) }.getOrNull()?.let { "${it.dayOfMonth}.${it.monthValue}.${it.year}" } ?: iso

/** Shows the date picker and hands back an ISO date. */
private fun pickDate(context: android.content.Context, initial: String?, onPick: (String) -> Unit) {
    val start = initial?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(SK_ZONE)
    DatePickerDialog(context, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d).toString()) }, start.year, start.monthValue - 1, start.dayOfMonth).show()
}

/**
 * Everything about a regular ride: time, days, a date range, extra dates, and how early
 * following starts. New rides come from a planner result, so stops and lines are already set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RideEditorDialog(
    initial: RecurringRide,
    isNew: Boolean,
    onSave: (RecurringRide) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var ride by remember { mutableStateOf(initial) }
    var canExact by remember { mutableStateOf(RideScheduler.canScheduleExact(context)) }
    val withNotifications = rememberWithNotifications()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ride.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.rides_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ride.lines.isNotEmpty()) {
                    Text(stringResource(R.string.rides_lines, ride.lines.joinToString(" → ")), style = MaterialTheme.typography.bodyMedium)
                }
                // Departure time
                AssistChip(
                    onClick = {
                        val t = parseClock(ride.time)
                        TimePickerDialog(context, { _, h, m -> ride = ride.copy(time = "%02d:%02d".format(h, m)) }, t?.hour ?: 7, t?.minute ?: 0, true).show()
                    },
                    label = { Text(stringResource(R.string.rides_departs, ride.time)) }
                )
                // Days of the week
                Text(stringResource(R.string.rides_days), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val on = day.value in ride.days
                        FilterChip(
                            selected = on,
                            onClick = { ride = ride.copy(days = if (on) ride.days - day.value else ride.days + day.value) },
                            label = { Text(dayName(day)) }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        R.string.rides_workdays to RecurringRide.WORKDAYS,
                        R.string.rides_weekends to setOf(6, 7),
                        R.string.rides_every_day to RecurringRide.EVERY_DAY
                    ).forEach { (label, days) ->
                        AssistChip(onClick = { ride = ride.copy(days = days) }, label = { Text(stringResource(label)) })
                    }
                }
                // Date range
                Text(stringResource(R.string.rides_range), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DateChip(stringResource(R.string.rides_from), ride.startDate, { pickDate(context, ride.startDate) { ride = ride.copy(startDate = it) } }) {
                        ride = ride.copy(startDate = null)
                    }
                    DateChip(stringResource(R.string.rides_until), ride.endDate, { pickDate(context, ride.endDate ?: ride.startDate) { ride = ride.copy(endDate = it) } }) {
                        ride = ride.copy(endDate = null)
                    }
                }
                // Single extra dates
                Text(stringResource(R.string.rides_dates), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ride.dates.sorted().forEach { date ->
                        InputChip(
                            selected = false,
                            onClick = { ride = ride.copy(dates = ride.dates - date) },
                            label = { Text(shortDate(date)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.clear), Modifier.size(16.dp)) }
                        )
                    }
                    AssistChip(
                        onClick = { pickDate(context, null) { if (it !in ride.dates) ride = ride.copy(dates = ride.dates + it) } },
                        label = { Text(stringResource(R.string.rides_add_date)) }
                    )
                }
                // When following starts
                Text(stringResource(R.string.rides_lead), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 20, 30, 45).forEach { m ->
                        FilterChip(
                            selected = ride.leadMinutes == m,
                            onClick = { ride = ride.copy(leadMinutes = m) },
                            label = { Text(stringResource(R.string.arrival_alert_minutes, m)) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rides_enabled), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(ride.enabled, { ride = ride.copy(enabled = it) })
                }
                if (ride.days.isEmpty() && ride.dates.isEmpty()) {
                    Notice(stringResource(R.string.rides_pick_days), tone = NoticeTone.Warning)
                }
                if (!canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Notice(
                        stringResource(R.string.rides_exact_needed),
                        tone = NoticeTone.Warning,
                        actionLabel = stringResource(R.string.catch_location_allow),
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                                )
                            }
                            canExact = RideScheduler.canScheduleExact(context)
                        }
                    )
                }
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.rides_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { withNotifications { onSave(ride) } },
                enabled = ride.days.isNotEmpty() || ride.dates.isNotEmpty()
            ) { Text(stringResource(R.string.rides_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun DateChip(label: String, value: String?, onPick: () -> Unit, onClear: () -> Unit) {
    if (value == null) {
        AssistChip(onClick = onPick, label = { Text(label) })
    } else {
        InputChip(
            selected = true,
            onClick = onPick,
            label = { Text("$label ${shortDate(value)}") },
            trailingIcon = {
                Icon(Icons.Outlined.Close, stringResource(R.string.clear), Modifier.size(18.dp).clickable(onClick = onClear))
            }
        )
    }
}
