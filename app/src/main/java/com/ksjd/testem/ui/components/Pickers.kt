package com.ksjd.testem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** 24-hour clock dial, or typed hours and minutes; ticks as the hands move. onNow: an extra "Now" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit, onNow: (() -> Unit)? = null) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var typing by rememberSaveable { mutableStateOf(false) }
    val haptic = rememberHaptic()
    var moved by remember { mutableStateOf(false) }
    LaunchedEffect(state.hour, state.minute) {
        if (moved) haptic(Haptics.TAP)
        moved = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.picker_time_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { typing = !typing; haptic(Haptics.TAP) }) {
                    Icon(if (typing) Icons.Outlined.Schedule else Icons.Outlined.Keyboard,
                        stringResource(if (typing) R.string.picker_dial else R.string.picker_type))
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (typing) TimeInput(state) else TimePicker(state)
            }
        },
        confirmButton = {
            TextButton(onClick = { haptic(Haptics.CONFIRM); onPick(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                if (onNow != null) TextButton(onClick = { haptic(Haptics.CONFIRM); onNow() }) { Text(stringResource(R.string.picker_now)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

/** Calendar with Today / Tomorrow shortcuts; days before [earliest] can't be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit, earliest: LocalDate? = null) {
    val utc = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    // Never opened on a day it doesn't let you pick (EM-0069).
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utc(if (earliest != null && initial.isBefore(earliest)) earliest else initial),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = earliest == null || utcTimeMillis >= utc(earliest)
            override fun isSelectableYear(year: Int) = earliest == null || year >= earliest.year
        }
    )
    val haptic = rememberHaptic()
    val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val today = LocalDate.now(com.ksjd.testem.SK_ZONE)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { haptic(Haptics.CONFIRM); picked?.let(onPick) }, enabled = picked != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    ) {
        DatePicker(
            state = state,
            title = {
                Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(today to R.string.day_today, today.plusDays(1) to R.string.day_tomorrow).forEach { (day, label) ->
                        FilterChip(
                            selected = picked == day,
                            onClick = { haptic(Haptics.CONFIRM); onPick(day) },
                            label = { Text(stringResource(label)) }
                        )
                    }
                }
            }
        )
    }
}
