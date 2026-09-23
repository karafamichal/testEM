package com.ksjd.testem.ui.screens

import android.app.TimePickerDialog
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
import com.ksjd.testem.R
import com.ksjd.testem.SavedRoute
import com.ksjd.testem.TimetableConnection
import com.ksjd.testem.TimetableState
import com.ksjd.testem.ui.components.LinePlate
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.NoticeTone
import com.ksjd.testem.ui.components.ScreenHeader
import com.ksjd.testem.ui.components.SectionLabel
import com.ksjd.testem.ui.theme.TimeStyle
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Calendar

@Composable
fun PlannerScreen(
    viewModel: AppViewModel,
    onOpenStop: (stopName: String, line: String, time: String) -> Unit,
    onExitGuest: (() -> Unit)?
) {
    val state by viewModel.timetableState.collectAsState()
    val listState = rememberLazyListState()

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

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            ScreenHeader(
                title = stringResource(R.string.planner_title),
                subtitle = stringResource(R.string.planner_subtitle),
                actions = {
                    if (onExitGuest != null) TextButton(onClick = onExitGuest) { Text(stringResource(R.string.auth_sign_in)) }
                }
            )
        }
        item { SearchForm(state, viewModel) }

        if (state.savedRoutes.isNotEmpty() || state.recentRoutes.isNotEmpty()) {
            item { RouteShortcuts(state, viewModel) }
        }

        if (state.errorMessage.isNotBlank()) {
            item {
                Notice(state.errorMessage, tone = NoticeTone.Error, modifier = Modifier.padding(16.dp))
            }
        }

        if (state.connections.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.timetables_results_title)) }
            items(state.connections, key = { it.id }) { connection ->
                ConnectionCard(connection, onOpenStop)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchForm(state: TimetableState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val cities = listOf(
        "slovensko" to stringResource(R.string.timetables_city_slovakia),
        "banskabystrica" to stringResource(R.string.timetables_city_banska_bystrica),
        "zvolen" to stringResource(R.string.timetables_city_zvolen)
    )
    val canSearch = !state.isLoading && state.fromInput.isNotBlank() && state.toInput.isNotBlank()
    val isSaved = state.savedRoutes.any {
        it.fromText.equals(state.fromInput.trim(), true) && it.toText.equals(state.toInput.trim(), true)
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            cities.forEachIndexed { index, (slug, label) ->
                SegmentedButton(
                    selected = state.citySlug == slug,
                    onClick = { viewModel.setTimetableCity(slug) },
                    shape = SegmentedButtonDefaults.itemShape(index, cities.size),
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    StopField(
                        value = state.fromInput,
                        label = stringResource(R.string.timetables_from_label),
                        loading = state.isLoadingFromSuggestions,
                        suggestions = state.fromSuggestions,
                        onValueChange = viewModel::updateTimetableFromInput,
                        onPick = viewModel::selectTimetableFromSuggestion
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
                IconButton(onClick = viewModel::swapTimetableEnds) {
                    Icon(Icons.Outlined.SwapVert, stringResource(R.string.planner_swap))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.timeInput.isBlank(),
                onClick = { viewModel.setTimetableTime("") },
                label = { Text(stringResource(R.string.planner_now)) }
            )
            FilterChip(
                selected = state.timeInput.isNotBlank(),
                onClick = {
                    val now = Calendar.getInstance()
                    val parts = state.timeInput.split(":").mapNotNull { it.toIntOrNull() }
                    TimePickerDialog(
                        context,
                        { _, h, m -> viewModel.setTimetableTime("%02d:%02d".format(h, m)) },
                        parts.getOrNull(0) ?: now.get(Calendar.HOUR_OF_DAY),
                        parts.getOrNull(1) ?: now.get(Calendar.MINUTE),
                        true
                    ).show()
                },
                label = {
                    Text(
                        if (state.timeInput.isBlank()) stringResource(R.string.planner_pick_time)
                        else stringResource(R.string.planner_at_time, state.timeInput)
                    )
                }
            )
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.planner_direct), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.directOnly, onCheckedChange = viewModel::setTimetableDirectOnly)
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = viewModel::loadTimetables,
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
    onPick: (CpStopSuggestion) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
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

/** cp.sk labels lines like "Bus 10"; stop boards use the bare number. */
private fun lineNumber(line: String): String = line.trim().substringAfterLast(' ')

/** "Zvolen,,AS" → "Zvolen, AS" */
private fun readableStop(name: String): String =
    name.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")

private fun routeLabel(route: SavedRoute): String = "${readableStop(route.fromText)} – ${readableStop(route.toText)}"


@Composable
private fun ConnectionCard(connection: TimetableConnection, onOpenStop: (String, String, String) -> Unit) {
    val context = LocalContext.current
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
                IconButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    context.startActivity(Intent.createChooser(intent, shareTitle))
                }) {
                    Icon(Icons.Outlined.Share, shareTitle, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            connection.segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    Text(
                        stringResource(R.string.timetables_transfer_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 2.dp)
                    )
                }
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
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
                if (segment.line.isNotBlank()) {
                    AssistChip(
                        onClick = { onOpenStop(segment.departureStop, lineNumber(segment.line), segment.departureTime) },
                        label = { Text(stringResource(R.string.planner_live_at_stop, readableStop(segment.departureStop)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.padding(start = 52.dp)
                    )
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
