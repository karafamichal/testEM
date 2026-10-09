package com.ksjd.testem.widget

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.MainActivity
import com.ksjd.testem.R
import com.ksjd.testem.live.LiveDeparture
import com.ksjd.testem.live.LiveRepository
import com.ksjd.testem.live.LiveStop
import com.ksjd.testem.ui.Format

/**
 * Departures from one favourite stop (each widget its own): the next buses with their times, live ones marked.
 * Tap the stop's name for the next favourite, ↻ to refresh, a bus to open the app.
 */
class StopWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val index = currentState(INDEX) ?: 0
            val version by PlanRideWidget.version.collectAsState()
            var board by remember { mutableStateOf<Pair<LiveStop, List<LiveDeparture>>?>(null) }
            var loaded by remember { mutableStateOf(false) }
            var count by remember { mutableStateOf(0) }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(version, index) {
                now = System.currentTimeMillis()
                val ids = CredentialsManager(context).getFavouriteStopIds()
                count = ids.size
                board = runCatching {
                    val repo = LiveRepository(context)
                    val stop = ids.getOrNull(index % ids.size.coerceAtLeast(1))?.let { repo.stopById(it) } ?: return@runCatching null
                    stop to repo.getBoard(stop).filter { !it.isCancelled }.take(MAX_DEPARTURES)
                }.getOrNull()
                loaded = true
            }
            GlanceTheme { Content(context, board, loaded, count, now) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content(context: Context, board: Pair<LiveStop, List<LiveDeparture>>?, loaded: Boolean, count: Int, now: Long) {
        val onSurface = GlanceTheme.colors.onSurface
        val muted = GlanceTheme.colors.onSurfaceVariant
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(14.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (board?.first?.name ?: context.getString(R.string.app_name)) + (if (count > 1) "  ›" else ""),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight().clickable(actionRunCallback<NextStopAction>())
                )
                Text("↻", style = TextStyle(color = muted, fontSize = 18.sp),
                    modifier = GlanceModifier.padding(start = 10.dp).clickable(actionRunCallback<RefreshStopAction>()))
            }
            when {
                count == 0 -> Text(context.getString(R.string.stop_widget_empty), style = TextStyle(color = muted, fontSize = 13.sp),
                    modifier = GlanceModifier.padding(top = 8.dp))
                board == null -> Text(context.getString(if (loaded) R.string.stop_widget_failed else R.string.track_loading),
                    style = TextStyle(color = muted, fontSize = 13.sp), modifier = GlanceModifier.padding(top = 8.dp))
                board.second.isEmpty() -> Text(context.getString(R.string.stop_widget_none), style = TextStyle(color = muted, fontSize = 13.sp),
                    modifier = GlanceModifier.padding(top = 8.dp))
                else -> LazyColumn(GlanceModifier.padding(top = 4.dp)) {
                    board.second.forEach { d ->
                        item {
                            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(GlanceModifier.width(46.dp)) {
                                    Text(d.line, style = TextStyle(color = onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                                }
                                Text(d.destination, maxLines = 1, style = TextStyle(color = onSurface, fontSize = 13.sp), modifier = GlanceModifier.defaultWeight())
                                // Times, not countdowns: a widget isn't redrawn every minute. Live ones get a dot.
                                Text((if (d.isRealtime) "● " else "") + Format.clock(d.fetchedAtMs + d.secondsUntil * 1000L),
                                    style = TextStyle(color = if (d.isRealtime) GlanceTheme.colors.primary else onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                            }
                        }
                    }
                    item {
                        Text(context.getString(R.string.widget_updated, Format.clock(now)), style = TextStyle(color = muted, fontSize = 11.sp),
                            modifier = GlanceModifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_DEPARTURES = 6
        internal val INDEX = intPreferencesKey("favourite_index")
    }
}

class StopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StopWidget()
}

/** The stop's name tapped: this widget shows the next favourite stop. */
class NextStopAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs: androidx.datastore.preferences.core.MutablePreferences ->
            prefs[StopWidget.INDEX] = (prefs[StopWidget.INDEX] ?: 0) + 1
        }
        StopWidget().update(context, glanceId)
    }
}

class RefreshStopAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        PlanRideWidget.refresh(context)
    }
}
