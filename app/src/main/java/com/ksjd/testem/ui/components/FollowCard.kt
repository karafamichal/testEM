package com.ksjd.testem.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R
import com.ksjd.testem.live.FollowView
import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripTracking
import com.ksjd.testem.ui.Format
import kotlin.math.ceil

/**
 * A background that is alive while a bus is followed: the theme's colour, a neighbouring hue and a deeper shade
 * drift into each other and the light slowly sweeps across (about ten seconds each way). Only the drawing changes,
 * nothing recomposes; with animations turned off in Android it stays still.
 */
@Composable
private fun Modifier.livingGradient(base: Color): Modifier {
    val shift = androidx.compose.animation.core.rememberInfiniteTransition(label = "follow")
    val t by shift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(10_000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "t"
    )
    val neighbour = hueShift(base, 26f)
    val deep = lerp(base, Color.Black, 0.28f)
    return this.drawBehind {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.linearGradient(
                listOf(lerp(base, neighbour, t), lerp(deep, base, t), lerp(neighbour, deep, t)),
                start = androidx.compose.ui.geometry.Offset(w * 0.6f * t, 0f),
                end = androidx.compose.ui.geometry.Offset(w, h * (1f - 0.6f * t))
            )
        )
    }
}

/** The same colour with its hue turned by `degrees` (lightness and saturation kept). */
private fun hueShift(c: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * The followed bus on the home screen, like its live notification: countdown, delay, and the bus
 * gliding along the line between your stops. Tap: its stops; ×: stop following.
 */
@Composable
fun FollowCard(trip: TrackedTrip, view: FollowView?, now: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val scheme = MaterialTheme.colorScheme
    val on = scheme.onPrimary
    val p = view?.progress
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable { haptic(Haptics.TAP); TripTracking.requestShow() }
    ) {
        Column(
            Modifier
                .livingGradient(scheme.primary)
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveBadge(view?.live == true, on)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { haptic(Haptics.CONFIRM); TripTracking.stop(context) }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.track_stop), tint = on)
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(on).padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Text(trip.ref.line, color = scheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("→ " + (view?.detail?.destination ?: trip.ref.destination), color = on, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                val plannedAt by com.ksjd.testem.live.FollowLater.planned.collectAsState()
                val leaves = com.ksjd.testem.live.FollowLater.departureMs(trip)
                if (p == null && plannedAt != null && leaves != null) {
                    fun clock(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(com.ksjd.testem.SK_ZONE).let { "%d:%02d".format(it.hour, it.minute) }
                    Text(stringResource(R.string.follow_planned_card, clock(leaves)), color = on, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.follow_planned_card_live, clock(plannedAt!!)), color = on.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                } else if (p == null) {
                    Text(stringResource(R.string.track_loading), color = on.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
                } else {
                    val summary = followSummary(context, view, now)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(summary.countdown, color = on, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            summary.caption,
                            color = on.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    DelayPill(summary, on)
                    Spacer(Modifier.height(16.dp))
                    RouteTrack(view, on)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(summary.from, color = on.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(summary.to, color = on.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    view.catchLine?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = on, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (trip.nextLegs.isNotEmpty()) JourneyLegs(trip, on)
            }
        }
    }
}

/**
 * EM-0067: a whole journey followed shows all of it: where to get off this bus and when, then each next bus (line,
 * when it leaves, from where to where), so nobody has to search for the journey again.
 */
@Composable
private fun JourneyLegs(trip: TrackedTrip, on: Color) {
    fun clock(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(com.ksjd.testem.SK_ZONE).let { "%d:%02d".format(it.hour, it.minute) }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (trip.toStop != null && trip.arrivesAtMs > 0) {
            Text(stringResource(R.string.journey_get_off, trip.toStop, clock(trip.arrivesAtMs)), color = on,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        trip.nextLegs.forEach { leg ->
            val times = listOfNotNull(
                leg.departsAtMs.takeIf { it > 0 }?.let { clock(it) + " " + leg.fromStop.orEmpty() },
                leg.toStop?.let { it + if (leg.arrivesAtMs > 0) " " + clock(leg.arrivesAtMs) else "" }
            ).joinToString(" → ")
            Text(stringResource(R.string.journey_next_leg, leg.ref.line, times), color = on.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** What the live card says; the home card and the widget both show it, so they always match (EM-0025). */
data class FollowSummary(
    val countdown: String,
    /** "until it leaves <stop>" / "to <next stop>" */
    val caption: String,
    val delay: String?,
    val veryLate: Boolean,
    /** Stops the bus still has to go to reach yours (0 once it's there). */
    val stopsAway: Int,
    /** Your part of the line: hops between your stop and where you get off, and the bus's place on it. */
    val hops: Int,
    val position: Float,
    val from: String,
    val to: String
)

fun followSummary(context: android.content.Context, view: FollowView, now: Long): FollowSummary {
    val p = view.progress
    val target = if (p.onBoard) p.nextExpectedMs else p.boardingExpectedMs
    val minutes = ceil((target - now) / 60_000.0).toInt()
    val delay = view.detail.delaySeconds?.let { Math.round(it / 60.0).toInt() }
    val last = (view.detail.stops.size - 1).coerceAtLeast(1)
    val start = (p.boardingIndex ?: 0).coerceIn(0, last - 1)
    val end = (p.alightIndex ?: last).coerceIn(start + 1, last)
    return FollowSummary(
        countdown = when {
            minutes <= 0 -> context.getString(R.string.countdown_now)
            minutes > 90 -> Format.clock(target)
            else -> context.getString(R.string.countdown_minutes, minutes)
        },
        caption = if (p.onBoard) context.getString(R.string.follow_until_next, p.nextStopName ?: p.lastStopName)
            else context.getString(R.string.follow_until_departure, p.boardingStopName),
        delay = delay?.let { m ->
            when {
                m >= 1 -> context.getString(R.string.delay_late, m)
                m <= -1 -> context.getString(R.string.delay_early, -m)
                else -> context.getString(R.string.delay_on_time)
            }
        },
        veryLate = (delay ?: 0) >= 3,
        stopsAway = ceil(start - p.position).toInt().coerceAtLeast(0),
        hops = end - start,
        position = (p.position - start).coerceIn(0f, (end - start).toFloat()),
        from = p.boardingStopName,
        to = p.alightStopName ?: p.lastStopName
    )
}

/** A pulsing dot and "Live", or a still dot and "Timetable". */
@Composable
private fun LiveBadge(live: Boolean, on: Color) {
    val pulse = rememberInfiniteTransition(label = "live")
    val scale by pulse.animateFloat(1f, if (live) 2.2f else 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "scale")
    val alpha by pulse.animateFloat(0.6f, if (live) 0f else 0.6f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "alpha")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp)) {
            Box(Modifier.size(8.dp).scale(scale).clip(CircleShape).background(on.copy(alpha = alpha)))
            Box(Modifier.size(8.dp).clip(CircleShape).background(on))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(if (live) R.string.follow_live else R.string.follow_timetable).uppercase(),
            color = on, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DelayPill(summary: FollowSummary, on: Color) {
    val text = summary.delay ?: return
    Box(Modifier.clip(RoundedCornerShape(50)).background(on.copy(alpha = if (summary.veryLate) 0.32f else 0.18f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, color = on, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Your part of the line, from your stop to where you get off: a dot per stop and the bus moving smoothly
 * between updates. Before it reaches your stop it waits, faded, at the start, with how many stops away it is.
 */
@Composable
private fun RouteTrack(view: FollowView, on: Color) {
    val p = view.progress
    val last = (view.detail.stops.size - 1).coerceAtLeast(1)
    val start = (p.boardingIndex ?: 0).coerceIn(0, last - 1)
    val end = (p.alightIndex ?: last).coerceIn(start + 1, last)
    val hops = end - start
    val target = (p.position - start).coerceIn(0f, hops.toFloat())
    val position by animateFloatAsState(target, tween(1400, easing = FastOutSlowInEasing), label = "bus")
    val away = kotlin.math.ceil(start - p.position).toInt()
    if (away > 0) {
        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.follow_stops_away, away, away),
            color = on.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
    }
    val halo = rememberInfiniteTransition(label = "halo")
    val ring by halo.animateFloat(1f, if (view.live) 1.6f else 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ring")
    val ringAlpha by halo.animateFloat(if (view.live) 0.45f else 0f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ringAlpha")
    BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.CenterStart) {
        val marker = 30.dp
        val trackWidth = maxWidth - marker
        Canvas(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = marker / 2)) {
            val y = size.height / 2
            val x = { i: Float -> i / hops * size.width }
            drawLine(on.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), 5.dp.toPx(), StrokeCap.Round)
            drawLine(on.copy(alpha = 0.85f), Offset(0f, y), Offset(x(position), y), 5.dp.toPx(), StrokeCap.Round)  // ridden
            if (hops <= 40) for (i in 0..hops) drawCircle(on.copy(alpha = 0.6f), 2.dp.toPx(), Offset(x(i.toFloat()), y))
            listOf(0f, hops.toFloat()).forEach {
                drawCircle(on, 6.dp.toPx(), Offset(x(it), y))
                drawCircle(Color.Black.copy(alpha = 0.15f), 3.dp.toPx(), Offset(x(it), y))
            }
        }
        Box(Modifier.offset(x = trackWidth * (position / hops)).size(marker).then(if (away > 0) Modifier.alpha(0.6f) else Modifier),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(marker).scale(ring).clip(CircleShape).background(on.copy(alpha = ringAlpha)))
            Box(Modifier.size(marker).clip(CircleShape).background(on), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DirectionsBus, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
