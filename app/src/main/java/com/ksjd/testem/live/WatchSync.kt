package com.ksjd.testem.live

import androidx.compose.ui.graphics.luminance
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.SK_ZONE
import com.ksjd.testem.rides.RideScheduler
import com.ksjd.testem.ui.components.followSummary
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Plan&Ride watch app's data (EM-0041) over the Wearable Data Layer. "follow": the followed bus (the home card's
 * texts, stops, transfers, alerts, the notification's buttons). "home": starred journeys and regular rides to start,
 * credit, and departures at favourite and nearby stops, sent when the watch asks. Without a watch it's a no-op.
 */
object WatchSync {
    private const val FOLLOW_PATH = "/plan-ride/follow"
    private const val HOME_PATH = "/plan-ride/home"
    const val STOP_PATH = "/plan-ride/stop"
    /** The watch app's logs for a bug report ("Report a problem" on the watch). */
    const val LOG_PATH = "/plan-ride/watch-log"
    /** The watch asks for "home" again (payload "nearby": with departures near the phone; the watch's GPS stays off). */
    const val REFRESH_PATH = "/plan-ride/refresh"
    /** A button of the follow notification pressed on the watch (payload: its index). */
    const val ACTION_PATH = "/plan-ride/action"
    /** Start following a starred journey ("j:<id>") or a regular ride ("r:<id>") from the watch. */
    const val START_PATH = "/plan-ride/start"
    const val EXTRA_REPORT = "report_bug"
    private const val THEME_PATH = "/plan-ride/theme"
    /** "Leave now" for a starred journey's bus: the watch buzzes it its own way (payload: title, newline, body). */
    private const val LEAVE_PATH = "/plan-ride/leave"
    /** Declared by the watch app (wear/res/values/wear.xml). */
    private const val CAPABILITY = "plan_ride_watch"

    /** The Plan&Ride watch app is on a paired watch: it shows the bus itself, so the phone's ongoing card stays on the phone. */
    @Volatile var watchApp = false
        private set

    /** Whether a paired watch has the Plan&Ride watch app (off the main thread). */
    fun checkWatch(context: Context): Boolean {
        watchApp = runCatching {
            Tasks.await(Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_ALL)).nodes.isNotEmpty()
        }.getOrDefault(false)
        return watchApp
    }

    /** Paired watches that don't have the Plan&Ride watch app yet: their node ids (off the main thread). */
    fun watchesWithoutApp(context: Context): List<String> = runCatching {
        val withApp = Tasks.await(Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_ALL)).nodes.map { it.id }.toSet()
        Tasks.await(Wearable.getNodeClient(context).connectedNodes).filter { it.isNearby && it.id !in withApp }.map { it.id }
    }.getOrDefault(emptyList())

    /**
     * Opens Plan&Ride's page in the Play Store on the watch, so the rider only taps Install there. Google Play on
     * the phone often doesn't offer Wear OS apps at all, so this is the reliable way. False if the watch didn't take it.
     */
    fun openPlayOnWatch(context: Context, nodeId: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse("market://details?id=${context.packageName}"))
        androidx.wear.remote.interactions.RemoteActivityHelper(context).startRemoteActivity(intent, nodeId).get()
        true
    }.getOrDefault(false)

    /** Called off the main thread whenever the followed bus or its view changes (TripTracking). */
    fun send(context: Context, active: TrackedTrip?, view: FollowView?) {
        val client = Wearable.getDataClient(context)
        val prefs = CredentialsManager(context)
        checkWatch(context)
        runCatching {
            if (active == null || !prefs.getWatchEnabled()) {
                Tasks.await(client.deleteDataItems(Uri.Builder().scheme("wear").path(FOLLOW_PATH).build()))
                return
            }
            val request = PutDataMapRequest.create(FOLLOW_PATH).apply {
                // The phone's watch settings: the countdown on the watch face, how many next stops.
                dataMap.putBoolean("ongoing", prefs.getWatchOngoing())
                dataMap.putString("lang", prefs.getLanguageCode()) // the watch app speaks the phone app's language
                dataMap.putInt("nextStops", prefs.getWatchNextStops())
                dataMap.putString("line", active.ref.line)
                dataMap.putString("dest", view?.detail?.destination ?: active.ref.destination)
                if (view != null) {
                    val s = followSummary(context, view, System.currentTimeMillis())
                    val p = view.progress
                    dataMap.putLong("target", if (p.onBoard) p.nextExpectedMs else p.boardingExpectedMs)
                    dataMap.putString("caption", s.caption)
                    dataMap.putString("delay", s.delay.orEmpty())
                    dataMap.putString("from", s.from)
                    dataMap.putString("to", s.to)
                    dataMap.putInt("hops", s.hops)
                    dataMap.putFloat("position", s.position)
                    dataMap.putString("catch", view.catchLine.orEmpty())
                    dataMap.putBoolean("live", view.live)
                    dataMap.putBoolean("onBoard", p.onBoard)
                    // How late, for the watch's colours (green, orange, red); left out while only the timetable is known.
                    if (view.live) view.detail.delaySeconds?.let { dataMap.putInt("delaySec", it) }
                    // Every stop with its expected time, and where the rider gets on and off.
                    val delayMs = (view.detail.delaySeconds ?: 0) * 1000L
                    dataMap.putDataMapArrayList("stops", ArrayList(view.detail.stops.mapIndexed { i, stop ->
                        DataMap().apply {
                            putString("name", stop.name)
                            putLong("at", stop.actualMs ?: (stop.scheduledMs + delayMs))
                            putBoolean("passed", stop.isPassed || i < p.nextIndex)
                            putString("platform", stop.platform)
                        }
                    }))
                    dataMap.putInt("next", p.nextIndex)
                    dataMap.putInt("board", p.boardingIndex ?: -1)
                    dataMap.putInt("alight", p.alightIndex ?: -1)
                }
                // The notification's buttons, pressed on the watch through ACTION_PATH.
                dataMap.putStringArray("buttons", TripTracking.buttons.map { it.title.toString() }.toTypedArray())
                // The journey's next buses: the first one from where this one is left.
                dataMap.putDataMapArrayList("legs", ArrayList(active.nextLegs.mapIndexed { i, leg ->
                    DataMap().apply {
                        putString("line", leg.ref.line)
                        putString("dest", leg.ref.destination)
                        putString("from", if (i == 0) view?.progress?.alightStopName.orEmpty() else "")
                        putLong("at", departureMs(leg.ref))
                    }
                }))
                dataMap.putDataMapArrayList("alerts", ArrayList(TripTracking.alerts.value.map { a ->
                    DataMap().apply { putLong("at", a.atMs); putString("title", a.title); putString("body", a.body); putString("key", a.key) }
                }))
            }.asPutDataRequest().setUrgent()
            Tasks.await(client.putDataItem(request))
        }
    }

    /**
     * Everything the watch shows besides the followed bus: starred journeys and regular rides (to start), credit, and the
     * next departures at favourite stops and, with [nearby], at the stops nearest the phone (its location, so the watch's
     * GPS never runs). Network: off the main thread.
     */
    fun sendHome(context: Context, nearby: Boolean) {
        val prefs = CredentialsManager(context)
        if (!prefs.getWatchEnabled()) return
        val repo = LiveRepository(context)
        val now = System.currentTimeMillis()
        val request = PutDataMapRequest.create(HOME_PATH).apply {
            dataMap.putLong("updated", now)
            dataMap.putString("lang", prefs.getLanguageCode())
            val journeys = prefs.getSavedJourneys().filter { it.arrivalMs == 0L || it.arrivalMs > now }.sortedBy { it.departureMs }.take(5).map { j ->
                val c = j.connection
                DataMap().apply {
                    putString("id", "j:" + j.id)
                    putString("title", "${c.departureTime} – ${c.arrivalTime}")
                    putString("sub", "${com.ksjd.testem.readableStopName(c.segments.first().departureStop)} → ${com.ksjd.testem.readableStopName(c.segments.last().arrivalStop)}")
                    putString("lines", c.segments.joinToString(", ") { it.line.trim().substringAfterLast(' ') })
                    putLong("at", j.departureMs)
                }
            }
            val rides = prefs.getRecurringRides().mapNotNull { r ->
                r.nextDeparture(java.time.ZonedDateTime.now(SK_ZONE))?.let { at ->
                    DataMap().apply {
                        putString("id", "r:" + r.id)
                        putString("title", r.label)
                        putString("sub", "")
                        putString("lines", r.lines.joinToString(", "))
                        putLong("at", at.toInstant().toEpochMilli())
                    }
                }
            }.sortedBy { it.getLong("at") }.take(5)
            dataMap.putDataMapArrayList("trips", ArrayList(journeys + rides))
            // "Next bus home": the next connection on the route chosen in Account → Watch.
            prefs.getWatchHomeRoute()?.let { route ->
                runCatching { runBlocking { withTimeoutOrNull(20_000) { nextHome(route) } } }.getOrNull()?.let { c ->
                    dataMap.putDataMap("home", DataMap().apply {
                        putString("id", "h:")
                        putString("title", "${c.departureTime} – ${c.arrivalTime}")
                        putString("sub", com.ksjd.testem.readableStopName(c.segments.first().departureStop))
                        putString("lines", c.segments.joinToString(", ") { it.line.trim().substringAfterLast(' ') })
                        putLong("at", connectionMs(c))
                    })
                }
            }
            // Credit (Account → Watch can hide it): SAD Zvolen's selected card and IDS BBSK.
            if (prefs.getWatchCredit()) {
                prefs.getLastAccount()?.cards?.let { cards -> cards.firstOrNull { it.snr == prefs.getSelectedCardSnr() } ?: cards.firstOrNull() }
                    ?.creditLastBalance?.let { dataMap.putDouble("creditSadzv", it) }
                prefs.getLastIdsAccount()?.credit?.let { dataMap.putDouble("creditIds", it) }
            }
            runBlocking {
                withTimeoutOrNull(20_000) {
                    val favourites = prefs.getFavouriteStopIds().take(3).mapNotNull { runCatching { repo.stopById(it) }.getOrNull() }
                    dataMap.putDataMapArrayList("favourites", ArrayList(favourites.map { board(repo, it, null) }))
                    if (nearby) {
                        val here = runCatching { Locations.current(context, fallbackToLastKnown = true) }.getOrNull()
                        dataMap.putBoolean("nearbyLocated", here != null)
                        if (here != null) {
                            val stops = runCatching { repo.nearbyStops(here.latitude, here.longitude, 3) }.getOrDefault(emptyList())
                            dataMap.putDataMapArrayList("nearby", ArrayList(stops.map { (stop, meters) -> board(repo, stop, meters) }))
                        }
                    }
                }
            }
        }.asPutDataRequest().setUrgent()
        runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request)) }
    }

    /** The last "next bus home" found, and when: the tile asks every minute, the planner needn't. */
    @Volatile private var homeCache: Pair<Long, com.ksjd.testem.TimetableConnection>? = null

    private fun connectionMs(c: com.ksjd.testem.TimetableConnection): Long =
        c.segments.firstOrNull()?.let { com.ksjd.testem.SavedJourney.epochMs(it.serviceDate, it.departureTime) } ?: 0L

    /** The first connection on [route] that hasn't left yet (searched again after 3 minutes, or once it has left). */
    private suspend fun nextHome(route: com.ksjd.testem.SavedRoute): com.ksjd.testem.TimetableConnection? {
        val now = System.currentTimeMillis()
        homeCache?.let { (at, c) -> if (now - at < 3 * 60_000L && connectionMs(c) > now) return c }
        val time = java.time.ZonedDateTime.now(SK_ZONE)
        val result = com.ksjd.testem.TimetableService().searchConnections(
            com.ksjd.testem.TimetableService.SearchRequest(
                citySlug = route.citySlug, fromInput = route.fromText, toInput = route.toText,
                timeInput = "%d:%02d".format(time.hour, time.minute), directOnly = false,
                fromSuggestion = route.from, toSuggestion = route.to, dateInput = time.toLocalDate().toString()
            )
        ).getOrNull() ?: return null
        val next = result.connections.firstOrNull { connectionMs(it) > now - 30_000L } ?: return null
        homeCache = now to next
        return next
    }

    /** The phone app's theme for the watch app: its preset's colours, lifted for a black screen as in dark mode. */
    fun sendTheme(context: Context) {
        val prefs = CredentialsManager(context)
        if (!prefs.getWatchEnabled()) return
        val presets = prefs.getThemePresets(com.ksjd.testem.DefaultThemePresets.all)
        val preset = presets.firstOrNull { it.id == prefs.getSelectedThemeId(presets.first().id) } ?: presets.first()
        fun lift(c: Long, by: Float) = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color(c), androidx.compose.ui.graphics.Color.White, by).toArgb()
        val request = PutDataMapRequest.create(THEME_PATH).apply {
            dataMap.putInt("primary", lift(preset.primary, 0.35f))
            dataMap.putInt("secondary", lift(preset.secondary, 0.35f))
            dataMap.putInt("tertiary", lift(preset.tertiary, 0.2f))
            // 2.15: the watch's own ground, cards and live colour. A dark theme background is used as it is;
            // otherwise a near-black tinted by the theme's main colour.
            fun toward(c: Long, black: Float) = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color(c), androidx.compose.ui.graphics.Color.Black, black).toArgb()
            val bg = preset.roles[com.ksjd.testem.ThemeRole.BACKGROUND]?.takeIf { androidx.compose.ui.graphics.Color(it).luminance() < 0.2f }
            dataMap.putInt("background", bg?.toInt() ?: toward(preset.primary, 0.9f))
            dataMap.putInt("surface", preset.roles[com.ksjd.testem.ThemeRole.CARDS]?.takeIf { bg != null }?.toInt() ?: toward(preset.primary, 0.78f))
            dataMap.putInt("live", (preset.roles[com.ksjd.testem.ThemeRole.LIVE] ?: 0xFF8AB4FF).toInt())
        }.asPutDataRequest()
        runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request)) }
    }

    /** "Leave now" for a starred journey (EM leave-by alerts): the watch app announces it with its own vibration. */
    fun sendLeave(context: Context, title: String, body: String): Boolean {
        if (!checkWatch(context) || !CredentialsManager(context).getWatchEnabled()) return false
        return runCatching {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            nodes.forEach { Tasks.await(Wearable.getMessageClient(context).sendMessage(it.id, LEAVE_PATH, "$title\n$body".toByteArray(Charsets.UTF_8))) }
            nodes.isNotEmpty()
        }.getOrDefault(false)
    }

    /** A stop's next four departures: line, destination, when (epoch ms) and whether it's live. */
    private suspend fun board(repo: LiveRepository, stop: LiveStop, meters: Int?): DataMap = DataMap().apply {
        putString("name", stop.name)
        putInt("meters", meters ?: -1)
        val departures = runCatching { repo.getBoard(stop) }.getOrDefault(emptyList()).filter { !it.isCancelled }.take(4)
        putDataMapArrayList("deps", ArrayList(departures.map { d ->
            DataMap().apply {
                putString("line", d.line)
                putString("dest", d.destination)
                putLong("at", d.fetchedAtMs + d.secondsUntil * 1000L)
                putBoolean("live", d.isRealtime)
            }
        }))
    }

    /**
     * Start following from the watch. Android lets an app start its tracking service from the background only from an
     * exact alarm (as regular rides do), so it goes through one; without that permission the phone offers "Follow".
     */
    fun startFromWatch(context: Context, id: String) {
        val prefs = CredentialsManager(context)
        val service: Intent = when {
            id.startsWith("j:") -> {
                val journey = prefs.getSavedJourneys().firstOrNull { it.id == id.drop(2) } ?: return
                val legs = journey.connection.segments.mapNotNull { plannerTrip(it, it.line.trim().substringAfterLast(' ')) }
                val first = legs.firstOrNull() ?: return
                TripTrackerService.intent(context, first.copy(nextLegs = legs.drop(1).map { it.copy(nextLegs = emptyList()) }))
            }
            id == "h:" -> {
                // "Next bus home" from the watch: the connection it was shown.
                val route = prefs.getWatchHomeRoute() ?: return
                val connection = runCatching { runBlocking { withTimeoutOrNull(20_000) { nextHome(route) } } }.getOrNull() ?: return
                val legs = connection.segments.mapNotNull { plannerTrip(it, it.line.trim().substringAfterLast(' ')) }
                val first = legs.firstOrNull() ?: return
                TripTrackerService.intent(context, first.copy(nextLegs = legs.drop(1).map { it.copy(nextLegs = emptyList()) }))
            }
            id.startsWith("r:") -> {
                val ride = prefs.getRecurringRides().firstOrNull { it.id == id.drop(2) } ?: return
                val at = ride.nextDeparture(java.time.ZonedDateTime.now(SK_ZONE)) ?: return
                TripTrackerService.rideIntent(context, ride.id, at.toInstant().toEpochMilli())
            }
            else -> return
        }
        val fire = PendingIntent.getBroadcast(
            context, 4400, Intent(context, WatchStartReceiver::class.java).putExtra(WatchStartReceiver.EXTRA_SERVICE, service),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (RideScheduler.canScheduleExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 300, fire)
        } else {
            runCatching { ContextCompat.startForegroundService(context, service) }
        }
    }
}

private fun departureMs(ref: TripRef): Long {
    if (ref.plannedSecondOfDay < 0) return 0L
    val date = runCatching { java.time.LocalDate.parse(ref.serviceDate) }.getOrNull() ?: return 0L
    return date.atStartOfDay(SK_ZONE).toInstant().toEpochMilli() + ref.plannedSecondOfDay * 1000L
}

/** The exact alarm set by WatchSync.startFromWatch: from here Android allows starting the tracking service. */
class WatchStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val service = intent.getParcelableExtra<Intent>(EXTRA_SERVICE) ?: return
        runCatching { ContextCompat.startForegroundService(context, service) }
    }

    companion object {
        const val EXTRA_SERVICE = "service"
    }
}

/** Messages from the watch app: stop, a notification button, start a journey, refresh, and its logs for a report. */
class WatchStopService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val payload = String(event.data ?: ByteArray(0), Charsets.UTF_8)
        when (event.path) {
            WatchSync.STOP_PATH -> TripTracking.stop(this)
            WatchSync.ACTION_PATH -> payload.toIntOrNull()?.let(TripTracking::pressButton)
            WatchSync.START_PATH -> WatchSync.startFromWatch(this, payload)
            WatchSync.REFRESH_PATH -> {
                WatchSync.sendTheme(this)
                WatchSync.sendHome(this, nearby = payload == "nearby")
            }
            WatchSync.LOG_PATH -> {
                com.ksjd.testem.hub.AppLogs.saveWatchLog(this, payload)
                notifyReport()
            }
        }
    }

    /** The logs are on the phone: a tap opens the bug report, which attaches them. */
    private fun notifyReport() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, getString(com.ksjd.testem.R.string.watch_report_channel), android.app.NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            this, 7, Intent(this, com.ksjd.testem.MainActivity::class.java)
                .putExtra(WatchSync.EXTRA_REPORT, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.ksjd.testem.R.drawable.ic_stat_bus)
            .setContentTitle(getString(com.ksjd.testem.R.string.watch_report_title))
            .setContentText(getString(com.ksjd.testem.R.string.watch_report_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setLocalOnly(true) // asked for on the watch: it says there that the phone has it
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(4300, notification) }
    }

    private companion object {
        const val CHANNEL = "watch_report"
    }
}
