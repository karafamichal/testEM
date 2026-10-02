package com.ksjd.testem.ui.screens

import com.ksjd.testem.ui.components.Haptics
import com.ksjd.testem.ui.components.rememberHaptic
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import com.ksjd.testem.AccountDetails
import com.ksjd.testem.AppViewModel
import com.ksjd.testem.QRDaemonConfig
import com.ksjd.testem.R
import com.ksjd.testem.RecurringRide
import com.ksjd.testem.SK_ZONE
import com.ksjd.testem.SavedJourney
import com.ksjd.testem.readableStopName
import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripTracking
import com.ksjd.testem.live.plannerTrip
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.TicketPhase
import com.ksjd.testem.TicketState
import com.ksjd.testem.live.BoardState
import com.ksjd.testem.live.LiveViewModel
import com.ksjd.testem.ui.Format
import com.ksjd.testem.ui.components.LinePlate
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.NoticeTone
import com.ksjd.testem.ui.components.ScreenHeader
import com.ksjd.testem.ui.components.SectionLabel
import com.ksjd.testem.ui.components.TicketStubShape
import com.ksjd.testem.ui.components.countdownText
import com.ksjd.testem.ui.theme.Ink
import com.ksjd.testem.ui.theme.InkMuted
import com.ksjd.testem.ui.theme.TimeStyle
import com.ksjd.testem.ui.theme.TransitTheme
import kotlinx.coroutines.delay

private val QrSize = 264.dp
private val QrPadding = 24.dp

@Composable
fun TicketScreen(
    viewModel: AppViewModel,
    liveViewModel: LiveViewModel,
    activity: FragmentActivity,
    onUnlock: () -> Unit,
    onSignIn: () -> Unit,
    onOpenDepartures: () -> Unit,
    onOpenPlanner: () -> Unit,
    onOpenAccount: () -> Unit
) {
    val ticket by viewModel.ticketState.collectAsState()
    val app by viewModel.appState.collectAsState()
    val timetable by viewModel.timetableState.collectAsState()
    val live by liveViewModel.state.collectAsState()
    var fullscreen by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val favouriteIds = live.favourites.take(3).map { it.id }
    LaunchedEffect(favouriteIds) {
        while (favouriteIds.isNotEmpty()) {
            liveViewModel.refreshPreview()
            delay(30_000)
        }
    }

    val unlocked = app.hasAccount && app.isAppUnlocked
    if (fullscreen && unlocked) {
        FullscreenTicket(ticket, now, activity) { fullscreen = false }
    }

    val card = ticket.selectedCard.takeIf { unlocked }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        ScreenHeader(
            title = ticket.userName.substringBefore(' ').takeIf { unlocked }?.ifBlank { null }
                ?: stringResource(if (app.hasAccount) R.string.ticket_title else R.string.app_name),
            subtitle = card?.cardTypeName,
            actions = { if (card?.creditLastBalance != null) BalancePill(card, onOpenAccount) }
        )

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The followed bus first, sliding in when following starts.
            val followed by com.ksjd.testem.live.TripTracking.active.collectAsState()
            val followView by com.ksjd.testem.live.TripTracking.view.collectAsState()
            androidx.compose.animation.AnimatedVisibility(
                visible = followed != null,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
            ) {
                followed?.let { com.ksjd.testem.ui.components.FollowCard(it, followView?.takeIf { v -> v.trip.ref == it.ref }, now) }
            }
            if (unlocked && ticket.phase == TicketPhase.SignedOut) {
                Notice(
                    stringResource(R.string.ticket_signed_out),
                    tone = NoticeTone.Error,
                    actionLabel = stringResource(R.string.ticket_sign_in_again),
                    onAction = { viewModel.returnToLogin(); onSignIn() }
                )
            }
            // The saved password stopped working: the email is kept for signing in again.
            if (!app.hasAccount && app.email.isNotBlank()) {
                Notice(
                    stringResource(R.string.ticket_signed_out),
                    tone = NoticeTone.Warning,
                    actionLabel = stringResource(R.string.ticket_sign_in_again),
                    onAction = onSignIn
                )
            }
            val credit = card?.creditLastBalance
            if (credit != null && credit < app.lowCreditWarningThreshold) {
                Notice(
                    stringResource(R.string.low_credit_message, Format.money(credit, card.currencySymbol)),
                    tone = NoticeTone.Warning
                )
            }
        }

        if (app.hasAccount) {
            Spacer(Modifier.height(12.dp))
            if (unlocked) {
                TicketStub(
                    ticket = ticket,
                    now = now,
                    onFullscreen = { fullscreen = true },
                    onResume = viewModel::startPolling
                )
            } else {
                LockedStub(onUnlock)
            }
            if (unlocked && ticket.cards.size > 1) {
                SectionLabel(stringResource(R.string.ticket_card_switch))
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ticket.cards.forEach { c ->
                        FilterChip(
                            selected = c.snr == ticket.selectedSnr,
                            onClick = { viewModel.selectCard(c.snr) },
                            label = { Text(cardLabel(c)) }
                        )
                    }
                }
            }
        }

        JourneysSection(timetable.savedJourneys, now, viewModel, onOpenPlanner)
        RidesSection(timetable.rides, now, viewModel)

        SectionLabel(stringResource(R.string.departures_favourites))
        if (live.favourites.isEmpty()) {
            Text(
                stringResource(R.string.ticket_next_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .clickable(onClick = onOpenDepartures)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                live.favourites.take(3).forEach { stop ->
                    val board = live.previews.firstOrNull { it.stop.id == stop.id }
                    MiniBoard(stop.name, board, now) {
                        liveViewModel.openStop(stop)
                        onOpenDepartures()
                    }
                }
            }
        }

        if (!app.hasAccount && app.email.isBlank()) {
            Spacer(Modifier.height(16.dp))
            Notice(
                stringResource(R.string.home_add_account_hint),
                actionLabel = stringResource(R.string.account_add),
                onAction = onSignIn,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/** Where the ticket goes while the app is locked: one tap asks for the PIN or a fingerprint. */
@Composable
private fun LockedStub(onUnlock: () -> Unit) {
    Surface(
        onClick = onUnlock,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ticket_locked_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.ticket_locked_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Starred planner results that haven't ended yet: follow in one tap (bug report EM-0008). */
@Composable
private fun JourneysSection(journeys: List<SavedJourney>, now: Long, viewModel: AppViewModel, onOpenPlanner: () -> Unit) {
    val upcoming = journeys.filter { it.arrivalMs == 0L || it.arrivalMs > now }.sortedBy { it.departureMs }
    if (upcoming.isEmpty()) return
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val withNotifications = rememberWithNotifications()
    val tracked by TripTracking.active.collectAsState()
    SectionLabel(stringResource(R.string.home_journeys))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        upcoming.take(5).forEach { journey ->
            val c = journey.connection
            val legs = c.segments.mapNotNull { s ->
                plannerTrip(s, s.line.trim().substringAfterLast(' '))
            }
            val following = tracked != null && legs.any { it.ref == tracked?.ref }
            Surface(
                onClick = {
                    viewModel.searchAt(journey.route, c.departureTime, c.segments.firstOrNull()?.serviceDate.orEmpty())
                    onOpenPlanner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.departureTime} – ${c.arrivalTime}", style = TimeStyle, modifier = Modifier.weight(1f))
                        Text(
                            startsIn(journey.departureMs, now),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = { viewModel.removeSavedJourney(journey.id) }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.home_journey_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "${readableStopName(c.segments.first().departureStop)} → ${readableStopName(c.segments.last().arrivalStop)}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.padding(top = 6.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        c.segments.forEach { LinePlate(it.line.trim().substringAfterLast(' ').ifBlank { "–" }) }
                        Spacer(Modifier.weight(1f))
                        if (following) {
                            Text(stringResource(R.string.home_following), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        } else if (legs.isNotEmpty()) {
                            FilledTonalButton(onClick = {
                                withNotifications {
                                    Haptics.perform(view, Haptics.CONFIRM)
                                    if (legs.size == 1) TripTracking.start(context, legs.first()) else TripTracking.startJourney(context, legs)
                                }
                            }) { Text(stringResource(R.string.journey_start)) }
                        }
                    }
                }
            }
        }
    }
}

/** The next time each regular ride runs. */
@Composable
private fun RidesSection(rides: List<RecurringRide>, now: Long, viewModel: AppViewModel) {
    var editing by remember { mutableStateOf<RecurringRide?>(null) }
    editing?.let { ride ->
        RideEditorDialog(
            initial = ride,
            isNew = false,
            onSave = { viewModel.saveRide(it); editing = null },
            onDelete = { viewModel.deleteRide(ride.id); editing = null },
            onDismiss = { editing = null }
        )
    }
    val next = rides.mapNotNull { ride ->
        ride.nextDeparture(java.time.Instant.ofEpochMilli(now).atZone(SK_ZONE))?.let { ride to it }
    }.sortedBy { it.second }
    if (next.isEmpty()) return
    SectionLabel(stringResource(R.string.rides_title))
    ListGroup {
        next.take(3).forEachIndexed { index, (ride, at) ->
            if (index > 0) GroupDivider()
            ListRow(
                title = ride.label,
                subtitle = stringResource(R.string.rides_next, rideDay(at), "%d:%02d".format(at.hour, at.minute)),
                onClick = { editing = ride },
                leading = { Icon(Icons.Outlined.EventRepeat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }
    }
}

/** "Today", "Tomorrow" or "Mon 5.10." */
@Composable
internal fun rideDay(at: java.time.ZonedDateTime): String {
    val today = java.time.LocalDate.now(SK_ZONE)
    return when (at.toLocalDate()) {
        today -> stringResource(R.string.day_today)
        today.plusDays(1) -> stringResource(R.string.day_tomorrow)
        else -> at.format(java.time.format.DateTimeFormatter.ofPattern("EEE d.M.", java.util.Locale.getDefault()))
    }
}

@Composable
private fun startsIn(departureMs: Long, now: Long): String {
    if (departureMs == 0L) return ""
    val minutes = ((departureMs - now) / 60_000L).toInt()
    return when {
        minutes < 0 -> stringResource(R.string.home_journey_underway)
        minutes < 60 -> stringResource(R.string.home_journey_in, countdownText(minutes * 60))
        else -> rideDay(java.time.Instant.ofEpochMilli(departureMs).atZone(SK_ZONE)) + " " +
            Format.clock(departureMs)
    }
}

fun cardLabel(card: AccountDetails): String =
    card.cardTypeName.ifBlank { card.snr.takeLast(6) }

@Composable
private fun BalancePill(card: AccountDetails, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            Format.money(card.creditLastBalance ?: 0.0, card.currencySymbol),
            style = TimeStyle,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** Seconds until the next scheduled token refresh. */
private fun secondsToRefresh(ticket: TicketState, now: Long): Int =
    ((QRDaemonConfig.POLL_INTERVAL_MS - (now - ticket.lastConfirmedTime)) / 1000L).toInt().coerceAtLeast(0)

private fun isStale(ticket: TicketState, now: Long): Boolean =
    ticket.lastConfirmedTime > 0 && now - ticket.lastConfirmedTime > QRDaemonConfig.STALE_AFTER_MS

@Composable
private fun TicketStub(ticket: TicketState, now: Long, onFullscreen: () -> Unit, onResume: () -> Unit) {
    val perforationY = QrPadding * 2 + QrSize
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = TicketStubShape(cornerRadius = 20.dp, notchRadius = 11.dp, notchY = perforationY),
        // A ticket is paper: always white so any scanner can read it, in any theme.
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(perforationY),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = ticket.qrBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_code_content_desc),
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .size(QrSize)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onFullscreen)
                    )
                } else {
                    Box(
                        Modifier
                            .size(QrSize)
                            .background(Color(0xFFF1F3F5), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (ticket.phase == TicketPhase.Connecting || ticket.phase == TicketPhase.Idle) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = Ink)
                        }
                    }
                }
            }
            Perforation()
            Row(
                Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    val (title, detail) = statusCopy(ticket, now)
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    val validTo = ticket.selectedCard?.ticketValidTo ?: 0L
                    if (validTo > 0 && Format.daysUntil(validTo) >= 0) {
                        Text(
                            stringResource(R.string.ticket_valid_until, Format.date(validTo)),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkMuted
                        )
                    }
                }
                if (ticket.phase == TicketPhase.Paused) {
                    TextButton(onClick = onResume) { Text(stringResource(R.string.ticket_resume)) }
                } else if (ticket.qrBitmap != null) {
                    IconButton(onClick = onFullscreen) {
                        Icon(Icons.Outlined.Fullscreen, stringResource(R.string.ticket_fullscreen), tint = Ink)
                    }
                }
            }
            FreshnessBar(ticket, now)
        }
    }
}

@Composable
private fun statusCopy(ticket: TicketState, now: Long): Pair<String, String> {
    val stale = isStale(ticket, now)
    return when {
        ticket.phase == TicketPhase.Offline || (ticket.phase == TicketPhase.Active && stale) -> {
            val title = stringResource(R.string.ticket_status_offline)
            val detail = if (ticket.lastUpdateTime > 0) {
                val minutes = ((now - ticket.lastUpdateTime) / 60_000L).toInt()
                stringResource(R.string.ticket_status_offline_detail, Format.clock(ticket.lastUpdateTime), minutes)
            } else {
                stringResource(R.string.ticket_status_offline_no_code)
            }
            title to detail
        }
        ticket.phase == TicketPhase.Active -> {
            val seconds = secondsToRefresh(ticket, now)
            stringResource(R.string.ticket_status_ready) to
                if (seconds > 0) stringResource(R.string.ticket_status_refresh_in, seconds)
                else stringResource(R.string.ticket_status_refreshing)
        }
        ticket.phase == TicketPhase.Paused ->
            stringResource(R.string.ticket_status_paused) to stringResource(R.string.ticket_status_paused_detail)
        ticket.phase == TicketPhase.NoCard ->
            stringResource(R.string.ticket_status_no_card) to stringResource(R.string.ticket_status_no_card_detail)
        ticket.phase == TicketPhase.SignedOut ->
            stringResource(R.string.ticket_status_signed_out) to stringResource(R.string.ticket_signed_out)
        else -> stringResource(R.string.ticket_status_connecting) to stringResource(R.string.ticket_status_connecting_detail)
    }
}

@Composable
private fun Perforation() {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 18.dp)
    ) {
        drawLine(
            color = Color(0xFFC9D0D8),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        )
    }
}

/**
 * Thin bar that drains smoothly until the next code. When a new code is confirmed
 * it stretches back to full with a springy motion; it turns amber when the code is old.
 */
@Composable
private fun FreshnessBar(ticket: TicketState, now: Long) {
    val amber = TransitTheme.colors.amber
    val stale = ticket.phase == TicketPhase.Offline || isStale(ticket, now)
    val active = ticket.phase == TicketPhase.Active && ticket.lastConfirmedTime > 0L
    val level = remember { Animatable(0f) }

    LaunchedEffect(ticket.lastConfirmedTime, active, stale) {
        when {
            stale -> level.animateTo(1f, tween(400))
            active -> {
                // Stretch back to full, overshooting a little like elastic...
                level.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
                // ...then drain linearly until the next refresh is due.
                val remaining = QRDaemonConfig.POLL_INTERVAL_MS - (System.currentTimeMillis() - ticket.lastConfirmedTime)
                if (remaining > 0) level.animateTo(0f, tween(remaining.toInt(), easing = LinearEasing))
            }
            else -> level.animateTo(0f, tween(300))
        }
    }

    val color = if (stale) amber else MaterialTheme.colorScheme.primary
    val track = Color(0xFFE9EDF1)
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 4.dp)
            .height(6.dp)
    ) {
        val thickness = 4.dp.toPx()
        val value = level.value
        // Overshoot past full shows as the bar briefly swelling, like a stretched band.
        val swell = ((value - 1f).coerceAtLeast(0f) * 6f).coerceAtMost(0.5f)
        val barHeight = thickness * (1f + swell)
        val top = (size.height - barHeight) / 2f
        drawRoundRect(track, Offset(0f, (size.height - thickness) / 2f), Size(size.width, thickness), CornerRadius(thickness / 2f))
        val width = size.width * value.coerceIn(0f, 1f)
        if (width > 0f) {
            drawRoundRect(color, Offset(0f, top), Size(width, barHeight), CornerRadius(barHeight / 2f))
        }
    }
}

@Composable
private fun FullscreenTicket(ticket: TicketState, now: Long, activity: FragmentActivity, onDismiss: () -> Unit) {
    val window = activity.window
    val previous = remember { window.attributes.screenBrightness }
    DisposableEffect(Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = previous }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable(onClick = onDismiss)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val bitmap = ticket.qrBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.qr_code_fullscreen_content_desc),
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            if (ticket.userName.isNotBlank()) {
                Text(ticket.userName, style = MaterialTheme.typography.headlineSmall, color = Ink)
            }
            val (title, detail) = statusCopy(ticket, now)
            Text("$title. $detail", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.ticket_fullscreen_close), style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

/** Three-row slice of a favourite stop's departure board, used on the home tab. */
@Composable
private fun MiniBoard(stopName: String, board: BoardState?, now: Long, onOpen: () -> Unit) {
    val colors = TransitTheme.colors
    Surface(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = colors.board
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                stopName,
                style = MaterialTheme.typography.labelLarge,
                color = colors.boardDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp)
            )
            val departures = board?.departures.orEmpty()
            when {
                board == null || (board.isLoading && departures.isEmpty()) -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.amber) }
                departures.isEmpty() -> Text(
                    stringResource(if (board.hasError) R.string.board_error else R.string.board_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.boardDim,
                    modifier = Modifier.padding(16.dp)
                )
                else -> departures.forEach { d ->
                    val elapsed = ((now - d.fetchedAtMs) / 1000L).toInt()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinePlate(d.line, onBoard = true)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            d.destination,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.boardText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (d.isRealtime) countdownText(d.secondsUntil - elapsed) else Format.secondOfDay(d.plannedSecondOfDay),
                            style = TimeStyle,
                            color = if (d.isRealtime) colors.amber else colors.boardText
                        )
                    }
                }
            }
        }
    }
}
