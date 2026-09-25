package com.ksjd.testem.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ksjd.testem.R
import com.ksjd.testem.live.BoardState
import com.ksjd.testem.live.LiveDeparture
import com.ksjd.testem.live.LiveState
import com.ksjd.testem.live.LiveStop
import com.ksjd.testem.live.LiveViewModel
import com.ksjd.testem.live.LocationStatus
import com.ksjd.testem.live.PositionSource
import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripSheetState
import com.ksjd.testem.live.TripTracking
import com.ksjd.testem.ui.Format
import com.ksjd.testem.ui.components.DelayLabel
import com.ksjd.testem.ui.components.EmptyState
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.LinePlate
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.NoticeTone
import com.ksjd.testem.ui.components.ScreenHeader
import com.ksjd.testem.ui.components.SectionLabel
import com.ksjd.testem.ui.components.countdownText
import com.ksjd.testem.ui.theme.Barlow
import com.ksjd.testem.ui.theme.TimeStyle
import com.ksjd.testem.ui.theme.TransitTheme
import kotlinx.coroutines.delay

@Composable
fun DeparturesScreen(liveViewModel: LiveViewModel, onExitGuest: (() -> Unit)?) {
    val state by liveViewModel.state.collectAsState()
    val board = state.board
    if (board != null) {
        BackHandler(onBack = liveViewModel::closeStop)
        StopBoard(
            board = board,
            isFavourite = board.stop.id in state.favouriteIds,
            onBack = liveViewModel::closeStop,
            onToggleFavourite = { liveViewModel.toggleFavourite(board.stop) },
            onRetry = liveViewModel::refreshBoard,
            onOpenTrip = liveViewModel::openTrip
        )
    } else {
        StopFinder(state, liveViewModel, onExitGuest)
    }
}

// --------------------------------------------------------------------------
// Stop finder
// --------------------------------------------------------------------------

@Composable
private fun StopFinder(state: LiveState, vm: LiveViewModel, onExitGuest: (() -> Unit)?) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.findNearby() else vm.onLocationPermissionDenied()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ScreenHeader(
                title = stringResource(R.string.departures_title),
                actions = {
                    if (onExitGuest != null) TextButton(onClick = onExitGuest) { Text(stringResource(R.string.auth_sign_in)) }
                }
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::search,
                placeholder = { Text(stringResource(R.string.departures_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.search("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.clear))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (state.stopsFailed) {
                Notice(
                    stringResource(R.string.departures_stops_failed),
                    tone = NoticeTone.Error,
                    actionLabel = stringResource(R.string.retry),
                    onAction = { vm.retryStops() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (state.query.isNotBlank()) {
            item {
                if (state.results.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.departures_no_match_title),
                        body = stringResource(R.string.departures_no_match_body)
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    ListGroup {
                        state.results.forEachIndexed { index, stop ->
                            if (index > 0) GroupDivider()
                            StopRow(stop, stop.id in state.favouriteIds, null, { vm.openStop(stop) }) { vm.toggleFavourite(stop) }
                        }
                    }
                }
            }
            return@LazyColumn
        }

        item {
            SectionLabel(stringResource(R.string.departures_favourites))
            if (state.favourites.isEmpty()) {
                Text(
                    stringResource(R.string.departures_favourites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            } else {
                ListGroup {
                    state.favourites.forEachIndexed { index, stop ->
                        if (index > 0) GroupDivider()
                        StopRow(stop, true, null, { vm.openStop(stop) }) { vm.toggleFavourite(stop) }
                    }
                }
            }
        }

        item {
            SectionLabel(stringResource(R.string.departures_nearby)) {
                if (state.locationStatus == LocationStatus.Ready) {
                    TextButton(onClick = { vm.findNearby(fresh = true) }) { Text(stringResource(R.string.refresh_button)) }
                }
            }
            when (state.locationStatus) {
                LocationStatus.Ready -> ListGroup {
                    state.nearby.forEachIndexed { index, (stop, meters) ->
                        if (index > 0) GroupDivider()
                        StopRow(stop, stop.id in state.favouriteIds, meters, { vm.openStop(stop) }) { vm.toggleFavourite(stop) }
                    }
                }
                LocationStatus.Locating -> Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.departures_locating), style = MaterialTheme.typography.bodyMedium)
                }
                else -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state.locationStatus) {
                        LocationStatus.Denied -> Notice(stringResource(R.string.departures_location_denied))
                        LocationStatus.Unavailable -> Notice(stringResource(R.string.departures_location_unavailable))
                        else -> Unit
                    }
                    OutlinedButton(
                        onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.departures_find_nearby))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StopRow(stop: LiveStop, favourite: Boolean, distanceMeters: Int?, onOpen: () -> Unit, onToggleFavourite: () -> Unit) {
    val platforms = pluralStringResource(R.plurals.platform_count, stop.platforms.size, stop.platforms.size)
    val subtitle = if (distanceMeters != null) {
        stringResource(R.string.departures_distance, formatDistance(distanceMeters), platforms)
    } else {
        platforms
    }
    ListRow(
        title = stop.name,
        subtitle = subtitle,
        onClick = onOpen,
        trailing = {
            IconButton(onClick = onToggleFavourite) {
                Icon(
                    if (favourite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(if (favourite) R.string.favourite_remove else R.string.favourite_add),
                    tint = if (favourite) TransitTheme.colors.amber else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

private fun formatDistance(meters: Int): String =
    if (meters < 1000) "$meters m" else "%.1f km".format(meters / 1000.0)

// --------------------------------------------------------------------------
// Departure board
// --------------------------------------------------------------------------

@Composable
private fun StopBoard(
    board: BoardState,
    isFavourite: Boolean,
    onBack: () -> Unit,
    onToggleFavourite: () -> Unit,
    onRetry: () -> Unit,
    onOpenTrip: (LiveDeparture) -> Unit
) {
    val colors = TransitTheme.colors
    // Tick every second so countdowns move between network refreshes.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.board)
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), tint = colors.boardText)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    board.stop.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.boardText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (board.updatedAtMs > 0) stringResource(R.string.board_updated, Format.clockWithSeconds(board.updatedAtMs))
                    else stringResource(R.string.board_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.boardDim
                )
            }
            IconButton(onClick = onToggleFavourite) {
                Icon(
                    if (isFavourite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(if (isFavourite) R.string.favourite_remove else R.string.favourite_add),
                    tint = if (isFavourite) colors.amber else colors.boardText
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            board.isLoading && board.departures.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.amber)
            }
            board.departures.isEmpty() -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(if (board.hasError) R.string.board_error else R.string.board_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.boardText
                )
                if (board.hasError) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = colors.amber) }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (board.hasError) {
                    item {
                        Text(
                            stringResource(R.string.board_stale),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.amber,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                }
                items(board.departures, key = { it.key }) { departure ->
                    val highlighted = board.highlight?.let {
                        it.line.equals(departure.line, ignoreCase = true) &&
                            it.time == Format.secondOfDay(departure.plannedSecondOfDay)
                    } == true
                    BoardRow(departure, now, highlighted) { onOpenTrip(departure) }
                }
                item {
                    Text(
                        stringResource(R.string.board_legend),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.boardDim,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardRow(departure: LiveDeparture, now: Long, highlighted: Boolean, onClick: () -> Unit) {
    val colors = TransitTheme.colors
    val elapsed = ((now - departure.fetchedAtMs) / 1000L).toInt()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(colors.boardRow, RoundedCornerShape(10.dp))
            .then(if (highlighted) Modifier.border(2.dp, colors.amber, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinePlate(departure.line, onBoard = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                departure.destination,
                style = MaterialTheme.typography.titleMedium,
                color = colors.boardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (departure.isCancelled) TextDecoration.LineThrough else null
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (departure.platformNumber.isNotBlank()) {
                    Text(
                        stringResource(R.string.board_platform, departure.platformNumber),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.boardDim
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (departure.isCancelled) {
                    Text(stringResource(R.string.board_cancelled), style = MaterialTheme.typography.labelMedium, color = colors.late)
                } else {
                    DelayLabel(departure.delaySeconds, onBoard = true)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (departure.isRealtime) {
                Text(
                    countdownText(departure.secondsUntil - elapsed),
                    style = TimeStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    color = colors.amber
                )
                Text(Format.secondOfDay(departure.plannedSecondOfDay), style = MaterialTheme.typography.bodySmall, color = colors.boardDim)
            } else {
                Text(
                    Format.secondOfDay(departure.plannedSecondOfDay),
                    style = TimeStyle.copy(fontSize = 22.sp),
                    color = colors.boardText
                )
                Text(stringResource(R.string.board_scheduled), style = MaterialTheme.typography.bodySmall, color = colors.boardDim)
            }
        }
    }
}

// --------------------------------------------------------------------------
// Trip sheet
// --------------------------------------------------------------------------

/** Stops of one bus with the Follow button; shown over any tab (Departures or Planner). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripSheet(trip: TripSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinePlate(trip.ref.line)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        trip.detail?.destination ?: trip.ref.destination,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val community = trip.detail?.community
                    if (trip.ref.isScheduleOnly && community != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DelayLabel(community.delaySeconds)
                            Text(
                                " " + pluralStringResource(R.plurals.community_riders, community.reporters, community.reporters),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (trip.ref.isScheduleOnly) {
                        Text(
                            stringResource(R.string.track_schedule_only),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        DelayLabel(trip.detail?.delaySeconds)
                    }
                    val history = trip.detail?.history
                    if (history != null && trip.detail.positionSource.let { it == PositionSource.Timetable || it == PositionSource.History }) {
                        Text(
                            Format.history(context, history),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FollowControls(trip)
        }
        val tracked by TripTracking.active.collectAsState()
        val following = tracked?.ref == trip.ref
        when {
            trip.isLoading -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            trip.hasError || trip.detail == null || trip.detail.stops.isEmpty() -> EmptyState(
                title = stringResource(R.string.trip_unavailable_title),
                body = stringResource(R.string.trip_unavailable_body)
            )
            else -> TripTimeline(
                stops = trip.detail.stops,
                delaySeconds = trip.detail.delaySeconds,
                alightOrder = if (following) tracked?.alightOrder else trip.alightOrder,
                onStopClick = if (following) {
                    { order -> TripTracking.setAlightStop(context, order.takeIf { it != tracked?.alightOrder }) }
                } else null
            )
        }
    }
}

@Composable
private fun TripTimeline(
    stops: List<com.ksjd.testem.live.TripStop>,
    delaySeconds: Int?,
    alightOrder: Int?,
    onStopClick: ((Int) -> Unit)?
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val now = System.currentTimeMillis()
    // Expected departure per stop: actual if reported, otherwise schedule + current delay.
    val expected = stops.map { stop ->
        stop.actualMs ?: (stop.scheduledMs + (delaySeconds ?: 0) * 1000L)
    }
    // The operator does not always report actual times, so infer progress from the clock too.
    val nextIndex = stops.indices.firstOrNull { i -> stops[i].actualMs == null && expected[i] >= now - 30_000L } ?: stops.size
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (nextIndex - 1).coerceIn(0, (stops.size - 1).coerceAtLeast(0)))
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        state = listState
    ) {
        items(stops.size) { index ->
            val stop = stops[index]
            val isNext = index == nextIndex
            val passed = index < nextIndex
            val expectedMs = if (stop.actualMs != null || delaySeconds != null) expected[index] else null
            val isAlight = stop.order == alightOrder
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicRowHeight)
                    .then(if (onStopClick != null && !passed) Modifier.clickable { onStopClick(stop.order) } else Modifier)
                    .then(if (isAlight) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rail with a stop marker; passed stops are drawn in the brand colour.
                Box(
                    Modifier
                        .padding(start = 28.dp)
                        .width(20.dp)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxSize()
                            .padding(
                                top = if (index == 0) IntrinsicRowHeight / 2 else 0.dp,
                                bottom = if (index == stops.lastIndex) IntrinsicRowHeight / 2 else 0.dp
                            )
                            .background(if (passed) primary else muted)
                    )
                    Box(
                        Modifier
                            .size(if (isNext) 14.dp else 10.dp)
                            .background(if (passed || isNext) primary else MaterialTheme.colorScheme.surface, CircleShape)
                            .border(2.dp, if (passed || isNext) primary else muted, CircleShape)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stop.name,
                        style = if (isNext || isAlight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isAlight) {
                        Text(stringResource(R.string.track_your_stop), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (stop.platform.isNotBlank()) {
                        Text(
                            stringResource(R.string.planner_platform, stop.platform),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 20.dp)) {
                    val showExpected = expectedMs != null && Format.clock(expectedMs) != Format.clock(stop.scheduledMs)
                    Text(
                        Format.clock(stop.scheduledMs),
                        style = TimeStyle.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (showExpected) TextDecoration.LineThrough else null
                    )
                    if (showExpected) {
                        Text(Format.clock(expectedMs!!), style = TimeStyle.copy(fontSize = 15.sp, fontFamily = Barlow))
                    }
                }
            }
        }
    }
}

private val IntrinsicRowHeight = 52.dp

/** "Follow this bus" / "Stop following", including the notification permission request. */
@Composable
private fun FollowControls(trip: TripSheetState) {
    val context = LocalContext.current
    val tracked by TripTracking.active.collectAsState()
    val following = tracked?.ref == trip.ref
    var denied by remember { mutableStateOf(false) }
    val start = { TripTracking.start(context, TrackedTrip(trip.ref, trip.boardingPlatformIds, trip.alightOrder)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) start()
    }
    if (following) {
        Text(
            stringResource(R.string.track_following),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { TripTracking.stop(context) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.track_stop)) }
    } else {
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    start()
                }
            },
            enabled = trip.detail?.stops?.isNotEmpty() == true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.track_start))
        }
        if (denied) {
            Spacer(Modifier.height(8.dp))
            Notice(stringResource(R.string.track_permission_needed))
        }
    }
    Spacer(Modifier.height(12.dp))
}
