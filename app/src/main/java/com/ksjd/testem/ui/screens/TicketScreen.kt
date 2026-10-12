package com.ksjd.testem.ui.screens

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import com.ksjd.testem.ui.components.WalletSwitch
import com.ksjd.testem.IdsState
import com.ksjd.testem.WalletRule
import com.ksjd.testem.Wallet
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import com.ksjd.testem.ui.components.DelayLabel
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
    onSignIn: (com.ksjd.testem.Wallet) -> Unit,
    onOpenDepartures: () -> Unit,
    onOpenPlanner: () -> Unit,
    onOpenAccount: () -> Unit
) {
    val ticket by viewModel.ticketState.collectAsState()
    val app by viewModel.appState.collectAsState()
    val timetable by viewModel.timetableState.collectAsState()
    val live by liveViewModel.state.collectAsState()
    val ids by viewModel.idsState.collectAsState()
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

    val unlocked = app.anyAccount && app.isAppUnlocked
    // The tickets on the slider: SAD Zvolen and/or IDS BBSK, whichever accounts are saved (EM-0023).
    val wallets = listOfNotNull(Wallet.SADZV.takeIf { app.hasAccount }, Wallet.IDS.takeIf { app.hasIdsAccount })
    val followed by com.ksjd.testem.live.TripTracking.active.collectAsState()
    // The followed bus decides (city bus: SAD Zvolen, IDS line: IDS BBSK); otherwise the rider's default.
    val ruled = followed?.let { WalletRule.forLine(it.ref.line) }?.takeIf { app.ticketAuto && it in wallets }
    val target = ruled ?: app.ticketDefault.takeIf { it in wallets } ?: wallets.firstOrNull() ?: Wallet.SADZV
    val pager = rememberPagerState(initialPage = wallets.indexOf(target).coerceAtLeast(0)) { wallets.size }
    // Each change of the deciding bus turns to its ticket; swiping by hand still works until the next change.
    LaunchedEffect(target, wallets.size) { if (wallets.size > 1) pager.animateScrollToPage(wallets.indexOf(target).coerceAtLeast(0)) }
    val shown = wallets.getOrNull(pager.currentPage) ?: target
    val scope = rememberCoroutineScope()
    if (fullscreen && unlocked) {
        if (shown == Wallet.IDS) {
            FullscreenQr(ids.qr, ids.account?.qrIdentifier?.alias.orEmpty(), "", activity) { fullscreen = false }
        } else {
            val (title, detail) = statusCopy(ticket, now)
            FullscreenQr(ticket.qrBitmap, ticket.userName, "$title. $detail", activity) { fullscreen = false }
        }
    }

    val card = ticket.selectedCard.takeIf { unlocked && shown == Wallet.SADZV }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = com.ksjd.testem.ui.components.barSpace())
    ) {
        ScreenHeader(
            title = ticket.userName.substringBefore(' ').takeIf { unlocked }?.ifBlank { null }
                ?: stringResource(if (app.anyAccount) R.string.ticket_title else R.string.app_name),
            subtitle = card?.cardTypeName,
            actions = {
                if (card?.creditLastBalance != null) BalancePill(Format.money(card.creditLastBalance, card.currencySymbol), onOpenAccount)
                else if (unlocked && shown == Wallet.IDS) ids.account?.credit?.let { BalancePill(Format.money(it, "€"), onOpenAccount) }
            }
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
                    onAction = { viewModel.returnToLogin(); onSignIn(Wallet.SADZV) }
                )
            }
            // The saved password stopped working: the email is kept for signing in again.
            if (!app.hasAccount && app.email.isNotBlank()) {
                Notice(
                    stringResource(R.string.ticket_signed_out),
                    tone = NoticeTone.Warning,
                    actionLabel = stringResource(R.string.ticket_sign_in_again),
                    onAction = { onSignIn(Wallet.SADZV) }
                )
            }
            // Low credit, with a way to add some right away (SAD Zvolen's top-up; IDS BBSK's in its e-shop).
            val credit = card?.creditLastBalance
            if (credit != null && credit < app.lowCreditWarningThreshold) {
                Notice(
                    stringResource(R.string.low_credit_message, Format.money(credit, card.currencySymbol)),
                    tone = NoticeTone.Warning,
                    actionLabel = stringResource(R.string.topup_button),
                    onAction = { com.ksjd.testem.reminders.Reminders.topUpRequested.value = true }
                )
            }
            val idsCredit = ids.account?.credit
            if (unlocked && shown == Wallet.IDS && idsCredit != null && idsCredit < app.lowCreditWarningThreshold) {
                val context = androidx.compose.ui.platform.LocalContext.current
                Notice(
                    stringResource(R.string.low_credit_message, Format.money(idsCredit, "€")),
                    tone = NoticeTone.Warning,
                    actionLabel = stringResource(R.string.ids_open_shop),
                    onAction = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://eshop.idsbbsk.sk/praca-s-kreditom")))
                        }
                    }
                )
            }
        }

        if (app.anyAccount) {
            Spacer(Modifier.height(12.dp))
            if (unlocked && wallets.size > 1) {
                WalletSwitch(shown, { w -> scope.launch { pager.animateScrollToPage(wallets.indexOf(w)) } })
                val line = followed?.ref?.line
                Text(
                    if (ruled != null && line != null && ruled == shown) stringResource(R.string.ticket_auto_for_line, line)
                    else stringResource(R.string.ticket_swipe_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
            if (unlocked) {
                HorizontalPager(pager, verticalAlignment = Alignment.Top) { page ->
                    Column {
                        if (wallets.size > 1) WalletLip(wallets[page])
                        if (wallets[page] == Wallet.IDS) {
                            IdsStub(ids, onFullscreen = { fullscreen = true })
                        } else {
                            TicketStub(
                                ticket = ticket,
                                now = now,
                                onFullscreen = { fullscreen = true },
                                onResume = viewModel::startPolling
                            )
                        }
                    }
                }
            } else {
                LockedStub(onUnlock)
            }
            if (unlocked && shown == Wallet.SADZV && ticket.cards.size > 1) {
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

        // D1: the next buses from the first favourite stop, right under the ticket (the ticket comes first).
        live.favourites.firstOrNull()?.let { stop ->
            val board = live.previews.firstOrNull { it.stop.id == stop.id }
            NextBusCards(stop.name, board, now) {
                liveViewModel.openStop(stop)
                onOpenDepartures()
            }
        }

        WatchInstallCard()
        JourneyDraftBar(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

        JourneysSection(timetable.savedJourneys, now, viewModel, onOpenPlanner)
        RidesSection(timetable.rides, now, viewModel)

        // The first favourite is on top as cards; the next two follow as small boards.
        if (live.favourites.size > 1 || live.favourites.isEmpty()) SectionLabel(stringResource(R.string.departures_favourites))
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
                live.favourites.take(3).drop(1).forEach { stop ->
                    val board = live.previews.firstOrNull { it.stop.id == stop.id }
                    MiniBoard(stop.name, board, now) {
                        liveViewModel.openStop(stop)
                        onOpenDepartures()
                    }
                }
            }
        }

        if (!app.anyAccount && app.email.isBlank()) {
            Spacer(Modifier.height(16.dp))
            Notice(
                stringResource(R.string.home_add_account_hint),
                actionLabel = stringResource(R.string.account_add),
                onAction = onOpenAccount,
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
private fun BalancePill(amount: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            amount,
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
 * it stretches back to full with a springy motion; it turns the delay colour when the code is old.
 */
@Composable
private fun FreshnessBar(ticket: TicketState, now: Long) {
    val staleColor = TransitTheme.colors.delay
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

    val color = if (stale) staleColor else MaterialTheme.colorScheme.primary
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

/** The coloured tab on top of a pass when there are two: SAD Zvolen in the app's colour, IDS BBSK in its red. */
@Composable
private fun WalletLip(wallet: Wallet) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .background(if (wallet == Wallet.IDS) IdsCharcoal else walletColor(wallet), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .padding(start = 12.dp, end = 14.dp, top = 5.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (wallet == Wallet.IDS) Box(Modifier.size(8.dp).background(IdsRed, RoundedCornerShape(2.dp)))
                Text(
                    walletName(wallet),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(start = if (wallet == Wallet.IDS) 6.dp else 0.dp)
                )
            }
        }
    }
}

/** The IDS BBSK virtual card: its static QR code on the same paper stub as the SAD Zvolen ticket (EM-0023). */
@Composable
private fun IdsStub(ids: IdsState, onFullscreen: () -> Unit) {
    val perforationY = QrPadding * 2 + QrSize
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = TicketStubShape(cornerRadius = 20.dp, notchRadius = 11.dp, notchY = perforationY),
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(perforationY), contentAlignment = Alignment.Center) {
                val bitmap = ids.qr
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_code_content_desc),
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(QrSize).clip(RoundedCornerShape(4.dp)).clickable(onClick = onFullscreen)
                    )
                } else {
                    Box(Modifier.size(QrSize).background(Color(0xFFF1F3F5), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        if (ids.isLoading || ids.account == null) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = Ink)
                        } else {
                            Text(
                                stringResource(if (ids.account.qrIdentifier == null) R.string.ids_no_virtual_card else R.string.ids_qr_missing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkMuted,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            }
            Perforation()
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ids.account?.qrIdentifier?.alias?.ifBlank { null } ?: stringResource(R.string.wallet_ids),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink
                    )
                }
                if (ids.qr != null) {
                    IconButton(onClick = onFullscreen) { Icon(Icons.Outlined.Fullscreen, stringResource(R.string.ticket_fullscreen), tint = Ink) }
                }
            }
        }
    }
}

/** A ticket's QR code over the whole screen, at full brightness for the scanner. */
@Composable
private fun FullscreenQr(bitmap: android.graphics.Bitmap?, title: String, detail: String, activity: FragmentActivity, onDismiss: () -> Unit) {
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
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.ticket_fullscreen_close), style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

/**
 * A paired watch without the Plan&Ride watch app: one tap opens its page in the Play Store on the watch, because the
 * phone's Play Store doesn't offer it. "Not now" hides it for good (Account → Watch still has the button).
 */
@Composable
private fun WatchInstallCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { com.ksjd.testem.CredentialsManager(context) }
    var watches by remember { mutableStateOf<List<String>>(emptyList()) }
    var hidden by remember { mutableStateOf(prefs.isWatchInstallHidden()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(hidden) {
        if (!hidden) watches = withContext(kotlinx.coroutines.Dispatchers.IO) { com.ksjd.testem.live.WatchSync.watchesWithoutApp(context) }
    }
    if (hidden || watches.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(androidx.compose.material.icons.Icons.Outlined.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.watch_install_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.watch_install_body), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { prefs.setWatchInstallHidden(true); hidden = true }) { Text(stringResource(R.string.watch_install_later)) }
                androidx.compose.material3.Button(onClick = {
                    scope.launch {
                        val ok = withContext(kotlinx.coroutines.Dispatchers.IO) { com.ksjd.testem.live.WatchSync.openPlayOnWatch(context, watches.first()) }
                        android.widget.Toast.makeText(context, if (ok) R.string.watch_install_sent else R.string.watch_install_failed, android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.watch_install_button)) }
            }
        }
    }
}

/**
 * D1: a favourite stop's next departures as cards you swipe through. The first card takes the theme's
 * next-bus colour; the countdown is big because that is what the rider looks for.
 */
@Composable
private fun NextBusCards(stopName: String, board: BoardState?, now: Long, onOpen: () -> Unit) {
    val colors = TransitTheme.colors
    val departures = board?.departures.orEmpty().filterNot { it.isCancelled }.take(4)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(stopName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.home_all_departures), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (departures.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            if (board == null || board.isLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text(stringResource(if (board.hasError) R.string.board_error else R.string.board_empty),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        departures.forEachIndexed { i, d ->
            val first = i == 0
            val elapsed = ((now - d.fetchedAtMs) / 1000L).toInt()
            val minutes = ((d.secondsUntil - elapsed) / 60).coerceAtLeast(0)
            Surface(
                onClick = onOpen,
                modifier = Modifier.width(150.dp),
                shape = RoundedCornerShape(20.dp),
                color = if (first) colors.highlight else MaterialTheme.colorScheme.surface,
                contentColor = if (first) colors.onHighlight else MaterialTheme.colorScheme.onSurface
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinePlate(d.line, onBoard = first)
                    Text(d.destination, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.isRealtime) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("$minutes", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold))
                            Text(" min", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    } else {
                        Text(Format.secondOfDay(d.plannedSecondOfDay), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold))
                    }
                    DelayLabel(
                        d.delaySeconds,
                        onBoard = first && colors.onHighlight.luminance() > 0.5f,
                        neutral = if (first) colors.onHighlight.copy(alpha = 0.8f) else null
                    )
                }
            }
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
                ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.live) }
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
                            color = if (d.isRealtime) colors.live else colors.boardText
                        )
                    }
                }
            }
        }
    }
}
