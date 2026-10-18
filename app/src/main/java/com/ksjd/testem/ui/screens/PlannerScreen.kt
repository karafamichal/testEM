package com.ksjd.testem.ui.screens

import androidx.compose.material.icons.outlined.Check
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ksjd.testem.AppViewModel
import com.ksjd.testem.CpStopSuggestion
import com.ksjd.testem.PLANNER_ALL
import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripTracking
import com.ksjd.testem.live.plannerTrip
import com.ksjd.testem.R
import com.ksjd.testem.RecurringRide
import com.ksjd.testem.SK_ZONE
import com.ksjd.testem.ui.theme.TransitTheme
import java.time.LocalDate
import com.ksjd.testem.SavedRoute
import com.ksjd.testem.TimetableConnection
import com.ksjd.testem.TimetableSegment
import com.ksjd.testem.TimetableState
import com.ksjd.testem.ui.components.LinePlate
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.ui.components.DatePickDialog
import com.ksjd.testem.ui.components.Haptics
import com.ksjd.testem.ui.components.TimePickDialog
import com.ksjd.testem.ui.components.rememberHaptic
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.NoticeTone
import com.ksjd.testem.ui.components.ScreenHeader
import com.ksjd.testem.ui.components.SectionLabel
import com.ksjd.testem.ui.theme.TimeStyle
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun PlannerScreen(
    viewModel: AppViewModel,
    onOpenStop: (stopName: String, line: String, time: String) -> Unit,
    onOpenTrip: (segment: TimetableSegment, line: String) -> Unit
) {
    val state by viewModel.timetableState.collectAsState()
    val listState = rememberLazyListState()
    // A regular ride being edited (new ones come from a result's Repeat button).
    var editing by remember { mutableStateOf<Pair<RecurringRide, Boolean>?>(null) }
    editing?.let { (ride, isNew) ->
        RideEditorDialog(
            initial = ride,
            isNew = isNew,
            onSave = { viewModel.saveRide(it); editing = null },
            onDelete = { viewModel.deleteRide(ride.id); editing = null },
            onDismiss = { editing = null }
        )
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 2
        }.distinctUntilChanged().collect { nearEnd ->
            val s = viewModel.timetableState.value
            if (nearEnd && s.canLoadMore && s.connections.isNotEmpty()) viewModel.loadMoreTimetables()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.ksjd.testem.ui.components.barSpace())) {
        item {
            ScreenHeader(
                title = stringResource(R.string.planner_title),
                subtitle = stringResource(R.string.planner_subtitle)
            )
        }
        item { PlacesRow(state, viewModel) }
        item { SearchForm(state, viewModel) }

        if (state.savedRoutes.isNotEmpty() || state.recentRoutes.isNotEmpty()) {
            item { RouteShortcuts(state, viewModel) }
        }
        if (state.connections.isEmpty()) {
            item { RidesList(state.rides) { editing = it to false } }
        }

        if (state.errorMessage.isNotBlank()) {
            item {
                Notice(state.errorMessage, tone = NoticeTone.Error, modifier = Modifier.padding(16.dp))
            }
        }

        // 2.17.0: a connection the planner doesn't know (only cp.sk had line 36 at 13:26): tell us, an admin checks it.
        if (state.connections.isNotEmpty() || state.errorMessage.isNotBlank()) {
            item {
                var reporting by remember { mutableStateOf(false) }
                if (reporting) SubmitDialog(SubmitKind.Trip, state.selectedFromSuggestion, state.selectedToSuggestion) { reporting = false }
                androidx.compose.material3.OutlinedButton(onClick = { reporting = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.submit_missing_connection))
                }
            }
        }
        if (state.connections.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.timetables_results_title)) }
            if (state.offline) item { Notice(stringResource(R.string.offline_results), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            if (state.rainy) item { Notice(stringResource(R.string.planner_rain), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            items(state.connections, key = { it.id }) { connection ->
                ConnectionCard(
                    connection = connection,
                    saved = state.savedJourneys.any { it.sameAs(connection) },
                    onToggleSave = { viewModel.toggleSaveJourney(connection) },
                    onRepeat = { viewModel.rideFrom(connection)?.let { editing = it to true } },
                    onReplace = { viewModel.replaceConnection(connection, it) },
                    onOpenStop = onOpenStop,
                    onOpenTrip = { segment, line ->
                        viewModel.openedPlannerLeg(connection, connection.segments.indexOf(segment))
                        onOpenTrip(segment, line)
                    }
                )
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        state.isLoadingMore -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        state.canLoadMore -> TextButton(onClick = viewModel::loadMoreTimetables) {
                            Text(stringResource(R.string.timetables_load_more))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 2.17.0 "Take me home": Home and Work, one tap each from the stop nearest you, now. Set (or change) one by holding it
 * with that stop in To; holding it with To empty forgets it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlacesRow(state: TimetableState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val prefs = remember { com.ksjd.testem.CredentialsManager(context) }
    var places by remember { mutableStateOf(listOf("home", "work").associateWith { prefs.getPlace(it) }) }
    var pending by remember { mutableStateOf<com.ksjd.testem.CpStopSuggestion?>(null) }
    val placesScope = androidx.compose.runtime.rememberCoroutineScope()
    val go: (com.ksjd.testem.CpStopSuggestion) -> Unit = { place ->
        viewModel.takeMeTo(place) { android.widget.Toast.makeText(context, R.string.place_no_location, android.widget.Toast.LENGTH_LONG).show() }
    }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.let(go); pending = null
    }
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("home" to R.string.place_home, "work" to R.string.place_work).forEach { (kind, label) ->
            val place = places[kind]
            val name = stringResource(label)
            Surface(
                shape = RoundedCornerShape(50),
                color = if (place != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).combinedClickable(
                    onClick = {
                        haptic(Haptics.TAP)
                        when {
                            place == null -> android.widget.Toast.makeText(context, context.getString(R.string.place_set_hint, name), android.widget.Toast.LENGTH_LONG).show()
                            com.ksjd.testem.live.Locations.hasPermission(context) -> go(place)
                            else -> { pending = place; askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }
                        }
                    },
                    onLongClick = {
                        haptic(Haptics.CONFIRM)
                        val to = state.selectedToSuggestion?.takeIf { it.selectedText.equals(state.toInput.trim(), true) }
                        prefs.savePlace(kind, to)
                        com.ksjd.testem.reminders.LastBus.reschedule(context)
                        placesScope.launch { com.ksjd.testem.widget.PlacesWidget.refresh(context) }
                        places = places + (kind to to)
                        android.widget.Toast.makeText(context,
                            if (to != null) context.getString(R.string.place_saved, name, to.selectedText) else context.getString(R.string.place_cleared, name),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == "home") Icons.Outlined.Home else Icons.Outlined.Work, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(place?.selectedText ?: stringResource(R.string.place_not_set), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchForm(state: TimetableState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val canSearch = !state.isLoading && state.fromInput.isNotBlank() && state.toInput.isNotBlank()
    val isSaved = state.savedRoutes.any {
        it.fromText.equals(state.fromInput.trim(), true) && it.toText.equals(state.toInput.trim(), true)
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    // EM-0068: start at the nearest stop; the planner also walks to the stops around it (400 m).
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    var locating by remember { mutableStateOf(false) }
                    val nearest: () -> Unit = {
                        locating = true
                        scope.launch {
                            val stop = com.ksjd.testem.live.Locations.current(context, fallbackToLastKnown = true)?.let { at ->
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { com.ksjd.testem.TimetableService().nearestStop(at.latitude, at.longitude) }.getOrNull()
                                }
                            }
                            locating = false
                            if (stop != null) viewModel.selectTimetableFromSuggestion(stop)
                            else android.widget.Toast.makeText(context, R.string.planner_no_stop_near, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                        if (granted.values.any { it }) nearest()
                    }
                    StopField(
                        value = state.fromInput,
                        label = stringResource(R.string.timetables_from_label),
                        loading = state.isLoadingFromSuggestions || locating,
                        suggestions = state.fromSuggestions,
                        onValueChange = viewModel::updateTimetableFromInput,
                        onPick = viewModel::selectTimetableFromSuggestion,
                        trailing = {
                            IconButton(onClick = {
                                haptic(Haptics.TAP)
                                if (com.ksjd.testem.live.Locations.hasPermission(context)) nearest()
                                else askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            }) { Icon(Icons.Outlined.MyLocation, stringResource(R.string.planner_nearest_stop)) }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    StopField(
                        value = state.toInput,
                        label = stringResource(R.string.timetables_to_label),
                        loading = state.isLoadingToSuggestions,
                        suggestions = state.toSuggestions,
                        onValueChange = viewModel::updateTimetableToInput,
                        onPick = viewModel::selectTimetableToSuggestion
                    )
                }
                IconButton(onClick = { haptic(Haptics.TAP); viewModel.swapTimetableEnds() }) {
                    Icon(Icons.Outlined.SwapVert, stringResource(R.string.planner_swap))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        var pickTime by remember { mutableStateOf(false) }
        var pickDate by remember { mutableStateOf(false) }
        // EM-0069: a date left from yesterday (a starred journey opened then) is today again.
        LaunchedEffect(state.dateInput) {
            val picked = runCatching { LocalDate.parse(state.dateInput) }.getOrNull()
            if (picked != null && picked.isBefore(LocalDate.now(SK_ZONE))) viewModel.setTimetableDate("")
        }
        if (pickTime) {
            val parts = state.timeInput.split(":").mapNotNull { it.toIntOrNull() }
            val now = java.time.LocalTime.now(SK_ZONE)
            TimePickDialog(
                initial = java.time.LocalTime.of(parts.getOrNull(0) ?: now.hour, parts.getOrNull(1) ?: now.minute),
                onPick = { pickTime = false; viewModel.setTimetableTime("%02d:%02d".format(it.hour, it.minute)) },
                onDismiss = { pickTime = false },
                onNow = { pickTime = false; viewModel.setTimetableTime("") }
            )
        }
        if (pickDate) {
            val today = LocalDate.now(SK_ZONE)
            DatePickDialog(
                initial = runCatching { LocalDate.parse(state.dateInput) }.getOrNull() ?: today,
                onPick = { pickDate = false; viewModel.setTimetableDate(if (it == today) "" else it.toString()) },
                onDismiss = { pickDate = false },
                earliest = today
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.timeInput.isBlank() && state.dateInput.isBlank(),
                onClick = { viewModel.setTimetableTime(""); viewModel.setTimetableDate("") },
                label = { Text(stringResource(R.string.planner_now)) }
            )
            FilterChip(
                selected = state.timeInput.isNotBlank(),
                onClick = { haptic(Haptics.TAP); pickTime = true },
                label = {
                    Text(
                        if (state.timeInput.isBlank()) stringResource(R.string.planner_pick_time)
                        else stringResource(if (state.arriveBy) R.string.planner_arrive_at_time else R.string.planner_at_time, state.timeInput)
                    )
                }
            )
            // EM-0053: with a time chosen, it can be when to be there instead of when to leave.
            if (state.timeInput.isNotBlank()) {
                FilterChip(
                    selected = state.arriveBy,
                    onClick = { haptic(Haptics.TAP); viewModel.setTimetableArriveBy(!state.arriveBy) },
                    label = { Text(stringResource(R.string.planner_arrive_by)) }
                )
            }
            FilterChip(
                selected = state.dateInput.isNotBlank(),
                onClick = { haptic(Haptics.TAP); pickDate = true },
                label = {
                    Text(
                        runCatching { LocalDate.parse(state.dateInput) }.getOrNull()
                            ?.let { rideDay(it.atStartOfDay(SK_ZONE)) } ?: stringResource(R.string.day_today)
                    )
                }
            )
        }
        // EM-0074: on foot or by car to the stop (catch estimates and leave-by), right here too.
        val planPrefs = androidx.compose.ui.platform.LocalContext.current.let { remember { com.ksjd.testem.CredentialsManager(it) } }
        var byCar by remember { mutableStateOf(planPrefs.getCatchByCar()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = byCar,
                onClick = { haptic(Haptics.TAP); byCar = !byCar; planPrefs.saveCatchByCar(byCar) },
                label = { Text(stringResource(if (byCar) R.string.planner_to_stop_car else R.string.planner_to_stop_walk)) },
                leadingIcon = {
                    Icon(if (byCar) Icons.Outlined.DirectionsCar
                        else Icons.AutoMirrored.Outlined.DirectionsWalk, null, Modifier.size(18.dp))
                },
                modifier = Modifier.padding(end = 12.dp)
            )
            Text(stringResource(R.string.planner_direct), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.directOnly, onCheckedChange = viewModel::setTimetableDirectOnly)
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { focusManager.clearFocus(); haptic(Haptics.TAP); viewModel.loadTimetables() },
                enabled = canSearch,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.timetables_search_button))
                }
            }
            IconButton(
                onClick = viewModel::toggleSaveCurrentRoute,
                enabled = state.fromInput.isNotBlank() && state.toInput.isNotBlank()
            ) {
                Icon(
                    if (isSaved) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    stringResource(if (isSaved) R.string.planner_unsave_route else R.string.planner_save_route)
                )
            }
        }
    }
}

@Composable
private fun StopField(
    value: String,
    label: String,
    loading: Boolean,
    suggestions: List<CpStopSuggestion>,
    onValueChange: (String) -> Unit,
    onPick: (CpStopSuggestion) -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        trailingIcon = trailing,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    )
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
    if (suggestions.isNotEmpty()) {
        Column(Modifier.padding(top = 4.dp)) {
            suggestions.take(6).forEachIndexed { index, s ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(s) }
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Text(s.selectedText, style = MaterialTheme.typography.bodyLarge)
                    if (s.description.isNotBlank()) {
                        Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteShortcuts(state: TimetableState, viewModel: AppViewModel) {
    if (state.savedRoutes.isNotEmpty()) {
        SectionLabel(stringResource(R.string.planner_saved_routes))
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.savedRoutes.forEach { route ->
                InputChip(
                    selected = false,
                    onClick = { viewModel.applyRoute(route) },
                    label = { Text(routeLabel(route), maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.planner_unsave_route),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { viewModel.removeSavedRoute(route) }
                        )
                    }
                )
            }
        }
    }
    val recents = state.recentRoutes.filterNot { r -> state.savedRoutes.any { it.sameAs(r) } }
    if (recents.isNotEmpty() && state.connections.isEmpty()) {
        SectionLabel(stringResource(R.string.planner_recent))
        ListGroup {
            recents.forEachIndexed { index, route ->
                if (index > 0) GroupDivider()
                ListRow(
                    title = routeLabel(route),
                    onClick = { viewModel.applyRoute(route) },
                    leading = { Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                )
            }
        }
    }
}

/** Planner lines read like "Bus 10"; stop boards use the bare number. */
private fun lineNumber(line: String): String = line.trim().substringAfterLast(' ')

/** "Zvolen,,AS" → "Zvolen, AS" */
private fun readableStop(name: String): String =
    name.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")

private fun routeLabel(route: SavedRoute): String = "${readableStop(route.fromText)} – ${readableStop(route.toText)}"


@Composable
private fun ConnectionCard(
    connection: TimetableConnection,
    saved: Boolean,
    onToggleSave: () -> Unit,
    onRepeat: () -> Unit,
    onOpenStop: (String, String, String) -> Unit,
    onOpenTrip: (TimetableSegment, String) -> Unit,
    onReplace: (TimetableConnection) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    // EM-0026, EM-0030: the change between segment moving-1 and moving, moved to another stop both buses serve.
    var moving by remember { mutableStateOf<Int?>(null) }
    moving?.let { index ->
        TransferEditor(connection, index, onDismiss = { moving = null }) { edited ->
            moving = null
            haptic(Haptics.CONFIRM)
            onReplace(edited)
        }
    }
    val shareTitle = stringResource(R.string.planner_share)
    val shareText = shareText(connection)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${connection.departureTime} – ${connection.arrivalTime}",
                    style = TimeStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                    modifier = Modifier.weight(1f)
                )
                Text(connection.totalDuration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Star: keep it on the home tab. Repeat: follow it by itself on chosen days.
                IconButton(onClick = onToggleSave) {
                    Icon(
                        if (saved) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                        stringResource(if (saved) R.string.planner_unsave_journey else R.string.planner_save_journey),
                        tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRepeat) {
                    Icon(Icons.Outlined.EventRepeat, stringResource(R.string.rides_repeat), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    context.startActivity(Intent.createChooser(intent, shareTitle))
                }) {
                    Icon(Icons.Outlined.Share, shareTitle, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            connection.segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    Row(Modifier.padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.timetables_transfer_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                        // 2.17.0: how often this change worked on earlier days, from riders' delays.
                        if (segment.transferRuns > 0) {
                            val sure = segment.transferWorked * 5 >= segment.transferRuns * 4
                            Text(
                                "  " + stringResource(R.string.transfer_odds, segment.transferWorked, segment.transferRuns),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sure) MaterialTheme.colorScheme.onSurfaceVariant else com.ksjd.testem.ui.theme.LocalTransitColors.current.late
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // A real button (it was a bare text link, easy to miss).
                        androidx.compose.material3.OutlinedButton(
                            onClick = { haptic(Haptics.TAP); moving = index },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.padding(end = 8.dp).height(34.dp)
                        ) {
                            Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.transfer_move), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                val canOpenTrip = segment.routeUrl.isNotBlank()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (canOpenTrip) Modifier.clickable { onOpenTrip(segment, lineNumber(segment.line)) } else Modifier)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    LinePlate(lineNumber(segment.line).ifBlank { "–" })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        SegmentStopLine(segment.departureTime, segment.departureStop, segment.departurePlatform)
                        SegmentStopLine(segment.arrivalTime, segment.arrivalStop, segment.arrivalPlatform)
                        if (segment.operatorName.isNotBlank()) {
                            Text(segment.operatorName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(
                    Modifier
                        .padding(start = 52.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    if (canOpenTrip) {
                        AssistChip(
                            onClick = { onOpenTrip(segment, lineNumber(segment.line)) },
                            label = { Text(stringResource(R.string.planner_show_stops), maxLines = 1) }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (segment.line.isNotBlank()) {
                        AssistChip(
                            onClick = { onOpenStop(segment.departureStop, lineNumber(segment.line), segment.departureTime) },
                            label = { Text(stringResource(R.string.planner_live_at_stop, readableStop(segment.departureStop)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
            // A connection with changes: follow all its buses as one journey.
            val legs = connection.segments.mapNotNull { s -> plannerTrip(s, lineNumber(s.line)) }
            if (legs.size > 1) {
                val withNotifications = rememberWithNotifications()
                // EM-0055: once followed, the button says so (with a check) and a tap stops following.
                val active by TripTracking.active.collectAsState()
                val following = active?.let { a -> legs.any { it.ref == a.ref } } == true
                if (following) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { haptic(Haptics.CONFIRM); TripTracking.stop(context) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 8.dp)
                    ) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.journey_following), modifier = Modifier.weight(1f, fill = false))
                        Text(" · " + stringResource(R.string.journey_following_stop))
                    }
                } else {
                    Button(
                        onClick = { withNotifications { haptic(Haptics.CONFIRM); TripTracking.startJourney(context, legs) } },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 8.dp)
                    ) { Text(stringResource(R.string.journey_follow)) }
                }
            }
        }
    }
}

@Composable
private fun SegmentStopLine(time: String, stop: String, platform: String) {
    val platformLabel = if (platform.isNotBlank()) stringResource(R.string.planner_platform, platform) else ""
    // The platform badge is inline content so it flows with the stop name when it wraps.
    val inline = if (platformLabel.isEmpty()) emptyMap() else mapOf(
        "platform" to InlineTextContent(
            Placeholder(
                width = (platformLabel.length * 0.5f + 1.1f).em,
                height = 1.35.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
            )
        ) { PlatformBadge(platformLabel) }
    )
    Row(verticalAlignment = Alignment.Top) {
        Text(time, style = TimeStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), modifier = Modifier.width(52.dp))
        Text(
            buildAnnotatedString {
                append(readableStop(stop))
                if (platformLabel.isNotEmpty()) {
                    append(" ")
                    appendInlineContent("platform", platformLabel)
                }
            },
            inlineContent = inline,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlatformBadge(label: String) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 4.dp)
            .border(1.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = primary, maxLines = 1, softWrap = false)
    }
}

private fun shareText(connection: TimetableConnection): String = buildString {
    append("${connection.departureTime} – ${connection.arrivalTime} (${connection.totalDuration})\n")
    connection.segments.forEach { s ->
        append("${s.line}: ${s.departureTime} ${s.departureStop} → ${s.arrivalTime} ${s.arrivalStop}\n")
    }
}.trim()
