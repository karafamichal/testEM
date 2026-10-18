package com.ksjd.testem.widget

import com.ksjd.testem.ui.components.followSummary
import com.ksjd.testem.ui.components.FollowSummary
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.glance.layout.size
import androidx.glance.layout.ContentScale
import androidx.glance.ColorFilter
import androidx.glance.ImageProvider
import androidx.glance.Image
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
import androidx.glance.layout.fillMaxHeight
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
            val colors = remember(version) { CardColors.of(context) }
            val background = remember(colors) { colors.gradient() }
            GlanceTheme {
                if (active != null) LiveCard(context, view?.takeIf { it.trip.ref == active?.ref }, System.currentTimeMillis(), colors, background)
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

        internal val version = kotlinx.coroutines.flow.MutableStateFlow(0)

        /** Reload and redraw every placed widget (after a follow update, when following stops, when the app shows its boards). */
        suspend fun refresh(context: Context) {
            version.value++
            runCatching { PlanRideWidget().updateAll(context) }
            runCatching { StopWidget().updateAll(context) }
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

/** The app's own colours (its theme preset, lighter in dark mode), so the widget matches the home card. */
data class CardColors(val primary: Color, val accent: Color, val on: Color) {
    companion object {
        fun of(context: Context): CardColors {
            val prefs = CredentialsManager(context)
            val presets = prefs.getThemePresets(com.ksjd.testem.DefaultThemePresets.all)
            val preset = presets.firstOrNull { it.id == prefs.getSelectedThemeId(presets.first().id) } ?: presets.first()
            val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val primary = if (night) androidx.compose.ui.graphics.lerp(Color(preset.primary), Color.White, 0.35f) else Color(preset.primary)
            val tertiary = if (night) androidx.compose.ui.graphics.lerp(Color(preset.tertiary), Color.White, 0.2f) else Color(preset.tertiary)
            val on = if (primary.luminance() > 0.45f) Color(0xFF14181D) else Color.White
            return CardColors(primary, androidx.compose.ui.graphics.lerp(primary, tertiary, 0.55f), on)
        }
    }

    /** The home card's diagonal gradient, as a picture a widget can show. */
    fun gradient(): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(480, 240, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 480f, 240f, primary.toArgb(), accent.toArgb(), android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bitmap).drawRect(0f, 0f, 480f, 240f, paint)
        return bitmap
    }
}

/**
 * The followed bus as on the app's home card (EM-0025): the same texts (followSummary), colours and layout,
 * with the stops of your part of the line as dots and the bus where it is.
 */
@Composable
private fun LiveCard(context: Context, view: FollowView?, now: Long, colors: CardColors, background: android.graphics.Bitmap) {
    val on = FixedColor(colors.on)
    val onDim = FixedColor(colors.on.copy(alpha = 0.85f))
    val open = actionStartActivity(
        Intent(context, MainActivity::class.java).putExtra(TripTracking.EXTRA_SHOW_FOLLOWED, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
    val trip = TripTracking.active.value
    Box(GlanceModifier.fillMaxSize().cornerRadius(28.dp).clickable(open)) {
        Image(ImageProvider(background), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
        Column(GlanceModifier.fillMaxSize().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("● " + context.getString(if (view?.live == true) R.string.follow_live else R.string.follow_timetable).uppercase(),
                    style = TextStyle(color = on, fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
                Text("✕", style = TextStyle(color = on, fontSize = 18.sp),
                    modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(actionRunCallback<StopFollowingAction>()))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(top = 4.dp)) {
                Box(GlanceModifier.cornerRadius(8.dp).background(on).padding(horizontal = 9.dp, vertical = 2.dp)) {
                    Text(trip?.ref?.line.orEmpty(), style = TextStyle(color = FixedColor(colors.primary), fontWeight = FontWeight.Bold, fontSize = 15.sp))
                }
                Text("  → " + (view?.detail?.destination ?: trip?.ref?.destination.orEmpty()), maxLines = 1,
                    style = TextStyle(color = on, fontSize = 15.sp, fontWeight = FontWeight.Medium))
            }
            if (view == null) {
                Text(context.getString(R.string.track_loading), style = TextStyle(color = onDim, fontSize = 14.sp), modifier = GlanceModifier.padding(top = 10.dp))
                return@Column
            }
            val summary = followSummary(context, view, now)
            Row(verticalAlignment = Alignment.Bottom, modifier = GlanceModifier.padding(top = 4.dp)) {
                Text(summary.countdown, style = TextStyle(color = on, fontSize = 30.sp, fontWeight = FontWeight.Bold))
                Text("  " + summary.caption, maxLines = 2, style = TextStyle(color = onDim, fontSize = 13.sp),
                    modifier = GlanceModifier.defaultWeight().padding(bottom = 5.dp))
            }
            summary.delay?.let { delay ->
                Box(GlanceModifier.cornerRadius(50.dp).background(FixedColor(colors.on.copy(alpha = if (summary.veryLate) 0.32f else 0.18f)))
                    .padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(delay, style = TextStyle(color = on, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
            if (summary.stopsAway > 0) {
                Text(context.resources.getQuantityString(R.plurals.follow_stops_away, summary.stopsAway, summary.stopsAway),
                    style = TextStyle(color = onDim, fontSize = 12.sp), modifier = GlanceModifier.padding(top = 4.dp))
            }
            Spacer(GlanceModifier.height(6.dp))
            Track(summary, colors)
            Row(GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(summary.from, maxLines = 1, style = TextStyle(color = onDim, fontSize = 12.sp), modifier = GlanceModifier.defaultWeight())
                Text(summary.to, maxLines = 1, style = TextStyle(color = onDim, fontSize = 12.sp))
            }
            view.catchLine?.let {
                Text(it, maxLines = 1, style = TextStyle(color = on, fontSize = 12.sp), modifier = GlanceModifier.padding(top = 2.dp))
            }
            // 2.17.0: how full it is, from riders on it.
            view.detail.crowd?.let { c ->
                val level = context.getString(listOf(R.string.crowd_1, R.string.crowd_2, R.string.crowd_3)[(c.level - 1).coerceIn(0, 2)])
                Text(context.getString(R.string.crowd_now, level, c.minutesAgo), maxLines = 1,
                    style = TextStyle(color = on, fontSize = 12.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.padding(top = 2.dp))
            }
        }
    }
}

/** Your part of the line: a dot per stop (at most 12) joined by the line, the ridden part bright, and the bus at its place. */
@Composable
private fun Track(summary: FollowSummary, colors: CardColors) {
    val cells = (summary.hops + 1).coerceAtMost(12)
    val bus = Math.round(summary.position / summary.hops * (cells - 1)).toInt().coerceIn(0, cells - 1)
    val bright = FixedColor(colors.on.copy(alpha = 0.85f))
    val dim = FixedColor(colors.on.copy(alpha = 0.3f))
    val none = FixedColor(Color.Transparent)
    // Stops at both edges like the app's track; between them the line, bright up to the bus.
    Row(GlanceModifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until cells) {
            if (i > 0) Spacer(GlanceModifier.defaultWeight().height(4.dp).background(if (i <= bus) bright else dim))
            Box(GlanceModifier.size(22.dp), contentAlignment = Alignment.Center) {
                Row(GlanceModifier.fillMaxWidth().height(4.dp)) {
                    Spacer(GlanceModifier.defaultWeight().fillMaxHeight().background(if (i == 0) none else if (i <= bus) bright else dim))
                    Spacer(GlanceModifier.defaultWeight().fillMaxHeight().background(if (i == cells - 1) none else if (i < bus) bright else dim))
                }
                if (i == bus) {
                    Box(GlanceModifier.size(22.dp).cornerRadius(11.dp).background(FixedColor(colors.on)), contentAlignment = Alignment.Center) {
                        Image(ImageProvider(R.drawable.ic_stat_bus), contentDescription = null, modifier = GlanceModifier.size(14.dp),
                            colorFilter = ColorFilter.tint(FixedColor(colors.primary)))
                    }
                } else {
                    Text(if (i == 0 || i == cells - 1) "●" else "•",
                        style = TextStyle(color = FixedColor(colors.on.copy(alpha = if (i < bus) 1f else 0.7f)), fontSize = 12.sp))
                }
            }
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
