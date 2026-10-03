package com.ksjd.testem.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as FixedColor
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.MainActivity
import com.ksjd.testem.R
import com.ksjd.testem.SavedJourney
import com.ksjd.testem.live.FollowView
import com.ksjd.testem.live.LiveDeparture
import com.ksjd.testem.live.LiveRepository
import com.ksjd.testem.live.LiveStop
import com.ksjd.testem.live.TripTracking
import com.ksjd.testem.live.plannerTrip
import com.ksjd.testem.ui.Format
import kotlin.math.ceil

/**
 * One home-screen widget that changes with what you're doing (EM-0017): following a bus, it is the
 * live card from the home tab; otherwise your starred journeys (tap one to follow it) and the next
 * buses at your favourite stops, in a list you can scroll.
 */
class PlanRideWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // The widget's session outlives one update: read everything as state, so a refresh shows new data.
        provideContent {
            val active by TripTracking.active.collectAsState()
            val view by TripTracking.view.collectAsState()
            val version by version.collectAsState()
            var journeys by remember { mutableStateOf(emptyList<SavedJourney>()) }
            var boards by remember { mutableStateOf(emptyList<Pair<LiveStop, List<LiveDeparture>>>()) }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(version, active == null) {
                now = System.currentTimeMillis()
                if (active != null) return@LaunchedEffect
                journeys = CredentialsManager(context).getSavedJourneys()
                    .filter { it.arrivalMs == 0L || it.arrivalMs > now }.sortedBy { it.departureMs }.take(MAX_JOURNEYS)
                boards = runCatching { favouriteBoards(context) }.getOrDefault(emptyList())
            }
            GlanceTheme {
                if (active != null) LiveCard(context, view?.takeIf { it.trip.ref == active?.ref }, System.currentTimeMillis())
                else Lists(context, journeys, boards, now)
            }
        }
    }

    /** The first favourite stops with their next buses (live from sadzv, other carriers from the timetable). */
    private suspend fun favouriteBoards(context: Context): List<Pair<LiveStop, List<LiveDeparture>>> {
        val ids = CredentialsManager(context).getFavouriteStopIds().take(MAX_STOPS)
        if (ids.isEmpty()) return emptyList()
        val repo = LiveRepository(context)
        val stops = repo.getStops().associateBy { it.id }
        return ids.mapNotNull { stops[it] }.map { stop -> stop to runCatching { repo.getBoard(stop).take(3) }.getOrDefault(emptyList()) }
    }

    companion object {
        private const val MAX_JOURNEYS = 3
        private const val MAX_STOPS = 2
        val JOURNEY_ID = ActionParameters.Key<String>("journey")

        private val version = kotlinx.coroutines.flow.MutableStateFlow(0)

        /** Reload and redraw every placed widget (after a follow update, when following stops, when the app shows its boards). */
        suspend fun refresh(context: Context) {
            version.value++
            runCatching { PlanRideWidget().updateAll(context) }
        }
    }
}

class PlanRideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PlanRideWidget()
}

/** Tap on a starred journey: follow it right away, like its button on the home tab. */
class FollowJourneyAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[PlanRideWidget.JOURNEY_ID] ?: return
        val journey = CredentialsManager(context).getSavedJourneys().firstOrNull { it.id == id } ?: return
        val legs = journey.connection.segments.mapNotNull { plannerTrip(it, it.line.trim().substringAfterLast(' ')) }
        if (legs.isEmpty()) return
        TripTracking.startJourney(context, legs)
        PlanRideWidget.refresh(context)
    }
}

class StopFollowingAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TripTracking.stop(context)
    }
}

private val white = FixedColor(Color.White)
private val whiteDim = FixedColor(Color.White.copy(alpha = 0.8f))

@Composable
private fun LiveCard(context: Context, view: FollowView?, now: Long) {
    val p = view?.progress
    val open = actionStartActivity(
        Intent(context, MainActivity::class.java).putExtra(TripTracking.EXTRA_SHOW_FOLLOWED, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.primary).padding(14.dp).clickable(open)
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "● " + context.getString(if (view?.live == true) R.string.follow_live else R.string.follow_timetable).uppercase(),
                style = TextStyle(color = white, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight()
            )
            Text("✕", style = TextStyle(color = white, fontSize = 18.sp), modifier = GlanceModifier.padding(horizontal = 6.dp)
                .clickable(actionRunCallback<StopFollowingAction>()))
        }
        val trip = TripTracking.active.value
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(top = 6.dp)) {
            Box(GlanceModifier.cornerRadius(6.dp).background(white).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(trip?.ref?.line.orEmpty(), style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp))
            }
            Text("  → " + (view?.detail?.destination ?: trip?.ref?.destination.orEmpty()), maxLines = 1,
                style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        }
        if (p == null) {
            Text(context.getString(R.string.track_loading), style = TextStyle(color = whiteDim, fontSize = 14.sp),
                modifier = GlanceModifier.padding(top = 10.dp))
            return@Column
        }
        val target = if (p.onBoard) p.nextExpectedMs else p.boardingExpectedMs
        val minutes = ceil((target - now) / 60_000.0).toInt()
        Column(GlanceModifier.padding(top = 6.dp)) {
            Text(
                when {
                    minutes <= 0 -> context.getString(R.string.countdown_now)
                    minutes > 90 -> Format.clock(target)
                    else -> context.getString(R.string.countdown_minutes, minutes)
                },
                style = TextStyle(color = white, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            )
            Text(
                if (p.onBoard) context.getString(R.string.follow_until_next, p.nextStopName ?: p.lastStopName)
                else context.getString(R.string.follow_until_departure, p.boardingStopName),
                maxLines = 1, style = TextStyle(color = whiteDim, fontSize = 13.sp)
            )
        }
        // Your part of the line, as on the home card: from your stop to where you get off.
        val last = (view.detail.stops.size - 1).coerceAtLeast(1)
        val start = (p.boardingIndex ?: 0).coerceIn(0, last - 1)
        val end = (p.alightIndex ?: last).coerceIn(start + 1, last)
        Spacer(GlanceModifier.height(10.dp))
        LinearProgressIndicator(
            progress = ((p.position - start) / (end - start)).coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            color = white,
            backgroundColor = FixedColor(Color.White.copy(alpha = 0.3f))
        )
        Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(p.boardingStopName, maxLines = 1, style = TextStyle(color = whiteDim, fontSize = 12.sp), modifier = GlanceModifier.defaultWeight())
            Text(p.alightStopName ?: p.lastStopName, maxLines = 1, style = TextStyle(color = whiteDim, fontSize = 12.sp))
        }
    }
}

@Composable
private fun Lists(context: Context, journeys: List<SavedJourney>, boards: List<Pair<LiveStop, List<LiveDeparture>>>, now: Long) {
    val onSurface = GlanceTheme.colors.onSurface
    val muted = GlanceTheme.colors.onSurfaceVariant
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Text(context.getString(R.string.app_name), style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        if (journeys.isEmpty() && boards.isEmpty()) {
            Text(context.getString(R.string.widget_empty), style = TextStyle(color = muted, fontSize = 13.sp), modifier = GlanceModifier.padding(top = 8.dp))
            return@Column
        }
        LazyColumn(GlanceModifier.padding(top = 4.dp)) {
            if (journeys.isNotEmpty()) item { Section(context.getString(R.string.home_journeys), muted) }
            journeys.forEach { j ->
                item {
                    val c = j.connection
                    Row(
                        GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable(actionRunCallback<FollowJourneyAction>(actionParametersOf(PlanRideWidget.JOURNEY_ID to j.id))),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(GlanceModifier.defaultWeight()) {
                            Text("${c.departureTime} – ${c.arrivalTime}", style = TextStyle(color = onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                            Text("${c.segments.first().departureStop} → ${c.segments.last().arrivalStop}", maxLines = 1,
                                style = TextStyle(color = muted, fontSize = 12.sp))
                        }
                        Box(GlanceModifier.cornerRadius(14.dp).background(GlanceTheme.colors.primaryContainer).padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text(context.getString(R.string.widget_follow), style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }
            boards.forEach { (stop, departures) ->
                item { Section(stop.name, muted) }
                departures.forEach { d ->
                    item {
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(GlanceModifier.width(44.dp)) {
                                Text(d.line, style = TextStyle(color = onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                            }
                            Text(d.destination, maxLines = 1, style = TextStyle(color = onSurface, fontSize = 13.sp), modifier = GlanceModifier.defaultWeight())
                            Text(Format.clock(d.fetchedAtMs + d.secondsUntil * 1000L), style = TextStyle(color = onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }
            item {
                Text(context.getString(R.string.widget_updated, Format.clock(now)), style = TextStyle(color = muted, fontSize = 11.sp),
                    modifier = GlanceModifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, color: androidx.glance.unit.ColorProvider) {
    Text(title, maxLines = 1, style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.padding(top = 8.dp, bottom = 2.dp))
}
