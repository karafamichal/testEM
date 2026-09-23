package com.ksjd.testem.ui.screens

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
import androidx.compose.material.icons.outlined.Fullscreen
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
    onOpenDepartures: () -> Unit,
    onOpenAccount: () -> Unit
) {
    val ticket by viewModel.ticketState.collectAsState()
    val app by viewModel.appState.collectAsState()
    val live by liveViewModel.state.collectAsState()
    var fullscreen by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val favourite = live.favourites.firstOrNull()
    LaunchedEffect(favourite?.id) {
        while (favourite != null) {
            liveViewModel.refreshPreview()
            delay(30_000)
        }
    }

    if (fullscreen) {
        FullscreenTicket(ticket, now, activity) { fullscreen = false }
    }

    val card = ticket.selectedCard
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        ScreenHeader(
            title = ticket.userName.substringBefore(' ').ifBlank { stringResource(R.string.ticket_title) },
            subtitle = card?.cardTypeName,
            actions = { if (card?.creditLastBalance != null) BalancePill(card, onOpenAccount) }
        )

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ticket.phase == TicketPhase.SignedOut) {
                Notice(
                    stringResource(R.string.ticket_signed_out),
                    tone = NoticeTone.Error,
                    actionLabel = stringResource(R.string.ticket_sign_in_again),
                    onAction = viewModel::returnToLogin
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

        Spacer(Modifier.height(12.dp))
        TicketStub(
            ticket = ticket,
            now = now,
            onFullscreen = { fullscreen = true },
            onResume = viewModel::startPolling
        )

        if (ticket.cards.size > 1) {
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

        SectionLabel(
            if (favourite != null) stringResource(R.string.ticket_next_from, favourite.name)
            else stringResource(R.string.ticket_next_buses)
        )
        if (favourite == null) {
            Text(
                stringResource(R.string.ticket_next_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .clickable(onClick = onOpenDepartures)
            )
        } else {
            MiniBoard(live.preview, now) {
                liveViewModel.openStop(favourite)
                onOpenDepartures()
            }
        }
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

/** Three-row slice of a departure board, used on the ticket tab. */
@Composable
private fun MiniBoard(board: BoardState?, now: Long, onOpen: () -> Unit) {
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
