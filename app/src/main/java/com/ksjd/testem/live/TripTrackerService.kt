package com.ksjd.testem.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.ksjd.testem.COMMUNITY_AUTO
import com.ksjd.testem.COMMUNITY_BUTTONS
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.MainActivity
import com.ksjd.testem.hub.HubClient
import com.ksjd.testem.R
import com.ksjd.testem.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/** What the user asked to follow. */
data class TrackedTrip(
    val ref: TripRef,
    /** Platforms of the stop where the user gets on (empty = already on board). */
    val boardingPlatformIds: List<Int>,
    /** stopOrder where the user gets off, null = ride to the end. */
    val alightOrder: Int? = null
)

/** Shared between the tracking service and the UI. */
object TripTracking {
    private val _active = MutableStateFlow<TrackedTrip?>(null)
    val active: StateFlow<TrackedTrip?> = _active
    internal fun set(value: TrackedTrip?) { _active.value = value }

    fun start(context: Context, trip: TrackedTrip) {
        _active.value = trip
        ContextCompat.startForegroundService(context, TripTrackerService.intent(context, trip))
    }

    /**
     * After the community mode or catch setting changes: restart the followed trip so the
     * service gets (or drops) the location type. Must run while the app is in the foreground,
     * or Android won't deliver the rider's GPS once the screen goes off.
     */
    fun refresh(context: Context) {
        _active.value?.let { start(context, it) }
    }

    fun setAlightStop(context: Context, order: Int?) {
        val current = _active.value ?: return
        start(context, current.copy(alightOrder = order))
    }

    fun stop(context: Context) {
        _active.value = null
        context.startService(Intent(context, TripTrackerService::class.java).setAction(TripTrackerService.ACTION_STOP))
    }
}

/**
 * Follows one bus trip and keeps an ongoing notification up to date. On Android 16
 * the notification is a promoted Live Update with a stop-by-stop progress bar.
 */
class TripTrackerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private lateinit var repo: LiveRepository
    private var trip: TrackedTrip? = null
    private val alertsSent = mutableSetOf<String>()
    private var startedAt = 0L
    private lateinit var prefs: CredentialsManager

    // Community reports: a fresh random id per followed bus, so reports can't be linked across trips.
    private var reporterId = UUID.randomUUID().toString()
    /** The rider's own report (delay seconds, when); wins until the pooled delay is newer. */
    private var localDelay: Pair<Int, Long>? = null
    private var lastDetail: TripDetail? = null
    private var reportNote: String? = null
    /** Waiting at the stop: after "Bus is here" the button becomes "Bus is leaving", then goes away. */
    private var herePressed = false
    private var leavingPressed = false

    // Catch estimate: location stays on the phone.
    private var stopLatLon: Pair<Double, Double>? = null
    private var stopLookupDone = false
    private var lastFix: android.location.Location? = null
    private var lastFixAt = 0L

    // Automatic mode: the rider's phone on the bus marks where it is (only the stop is sent).
    private var riderListener: android.location.LocationListener? = null
    private var routeCoords: List<Pair<Double, Double>?>? = null
    private var routeCoordsLoading = false
    private var lastRiderIndex = -1f
    private var lastRiderReportAt = 0L
    private var localFromGps = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = LiveRepository(this)
        prefs = CredentialsManager(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPORT) {
            // Not redelivered after a restart: an old tap would report the wrong time.
            reportPosition(intent.getIntExtra("stop", -1), leaving = intent.getBooleanExtra("leaving", false))
            return START_NOT_STICKY
        }
        val requested = intent?.let(::readTrip) ?: run {
            finish()
            return START_NOT_STICKY
        }
        if (trip?.ref != requested.ref) {
            alertsSent.clear()
            startedAt = System.currentTimeMillis()
            reporterId = UUID.randomUUID().toString()
            localDelay = null
            lastDetail = null
            reportNote = null
            herePressed = false
            leavingPressed = false
            stopLatLon = null
            stopLookupDone = false
            stopRiderTracking()
            routeCoords = null
            lastRiderIndex = -1f
            lastRiderReportAt = 0L
            localFromGps = false
        }
        trip = requested
        TripTracking.set(requested)
        startInForeground(placeholderNotification(requested))
        loop?.cancel()
        loop = scope.launch { track() }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        stopRiderTracking()
        scope.cancel()
        TripTracking.set(null)
        super.onDestroy()
    }

    private fun startInForeground(notification: android.app.Notification) {
        val special = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        // Location type keeps the catch estimate and automatic mode working with the screen off.
        val wantsLocation = prefs.getCatchEnabled() || prefs.getCommunityMode() == COMMUNITY_AUTO
        val location = if (wantsLocation && Locations.hasPermission(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special or location) }
            .onFailure { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special) }
    }

    /** The rider's own report wins until the pooled community delay is newer. */
    private fun withLocalDelay(detail: TripDetail): TripDetail {
        val (delay, at) = localDelay ?: return detail
        val communityAt = detail.community?.updatedAtMs ?: 0L
        return if (at >= communityAt) detail.copy(delaySeconds = delay) else detail
    }

    /** Buttons for riders who chose them, on any bus whose position nobody knows live. */
    private fun buttonsOn(detail: TripDetail) = prefs.getCommunityMode() == COMMUNITY_BUTTONS && HubClient.isConfigured &&
        detail.communityKey != null && !detail.positionSource.isLive

    // ------------------------------------------------------------ automatic mode (rider GPS)

    private fun updateRiderTracking(current: TrackedTrip, detail: TripDetail) {
        val wanted = prefs.getCommunityMode() == COMMUNITY_AUTO && HubClient.isConfigured &&
            detail.communityKey != null && Locations.hasPrecise(this)
        if (!wanted) return stopRiderTracking()
        if (routeCoords == null && !routeCoordsLoading) {
            routeCoordsLoading = true
            scope.launch {
                routeCoords = runCatching { repo.withCoordinates(current.ref, detail) }.getOrNull()
                    ?.stops?.map { s -> s.lat?.let { it to s.lon!! } }
                routeCoordsLoading = false
            }
        }
        if (riderListener != null) return
        val manager = getSystemService(android.location.LocationManager::class.java) ?: return
        val listener = android.location.LocationListener { fix -> onRiderFix(fix) }
        runCatching {
            @Suppress("MissingPermission")
            manager.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 5_000L, 10f, listener, android.os.Looper.getMainLooper())
            riderListener = listener
        }
    }

    private fun stopRiderTracking() {
        val listener = riderListener ?: return
        runCatching { getSystemService(android.location.LocationManager::class.java)?.removeUpdates(listener) }
        riderListener = null
    }

    /**
     * A fix from the rider's phone. It says where the bus is only once they are on it:
     * close to the route, past their boarding stop and moving at vehicle speed.
     */
    private fun onRiderFix(fix: android.location.Location) {
        val current = trip ?: return
        val detail = lastDetail ?: return
        val coords = routeCoords ?: return
        val key = detail.communityKey ?: return
        if (!fix.hasAccuracy() || fix.accuracy > 50f) return
        val p = TripProgress.from(withLocalDelay(detail), current)
        val (index, _) = RoutePosition.snap(fix.latitude, fix.longitude, coords, RoutePosition.RIDER_GPS_MAX_M, p.position) ?: return
        val boarding = (p.boardingIndex ?: 0).toFloat()
        val moving = (fix.hasSpeed() && fix.speed >= 3f) || (lastRiderIndex >= 0f && index > lastRiderIndex + 0.05f)
        val previous = lastRiderIndex
        lastRiderIndex = maxOf(lastRiderIndex, index)
        if (index < boarding + 0.05f || !moving) return
        val now = System.currentTimeMillis()
        val scheduled = detail.stops.map { it.scheduledMs }
        localDelay = RoutePosition.delayAt(index, scheduled, now) to now
        localFromGps = true
        redraw(current, detail)
        // Every 30 s, or right away when the bus passes a stop.
        if (now - lastRiderReportAt < 30_000L && index.toInt() == previous.toInt()) return
        lastRiderReportAt = now
        val stopIndex = index.toInt().coerceIn(0, detail.stops.lastIndex)
        scope.launch {
            runCatching {
                HubClient.reportPosition(key, current.ref.line, stopIndex, detail.stops[stopIndex].name,
                    RoutePosition.referenceAt(index, scheduled, now), reporterId, kind = "gps")
            }.getOrNull()?.let { pooled -> lastDetail = lastDetail?.copy(community = pooled) }
        }
    }

    /** "The bus is at stop [index] right now" ([leaving]: it is pulling out, the timetable's departure moment). */
    private fun reportPosition(index: Int, leaving: Boolean = false) {
        val current = trip ?: return
        val detail = lastDetail ?: return
        val stop = detail.stops.getOrNull(index) ?: return
        val key = detail.communityKey ?: return
        if (prefs.getCommunityMode() != COMMUNITY_BUTTONS || !HubClient.isConfigured) return
        val now = System.currentTimeMillis()
        localFromGps = false
        if (leaving) leavingPressed = true else herePressed = true
        val arrival = !leaving && index == TripProgress.from(detail, current).boardingIndex
        // A bus that is already at the stop before its departure time waits for it: that's
        // "on time", not early. Everything else ("leaving", "at <stop>") is the exact moment.
        val delay = ((now - stop.scheduledMs) / 1000L).toInt().let { if (arrival) it.coerceAtLeast(0) else it }
        localDelay = delay to now
        reportNote = getString(R.string.community_thanks)
        redraw(current, detail)
        scope.launch {
            val result = runCatching {
                HubClient.reportPosition(key, current.ref.line, index, stop.name, stop.scheduledMs, reporterId,
                    kind = if (arrival) "arrival" else "position")
            }
            if (result.isFailure) reportNote = getString(R.string.community_send_failed)
            result.getOrNull()?.let { pooled -> lastDetail = lastDetail?.copy(community = pooled) }
            lastDetail?.let { redraw(current, it) }
        }
    }

    private fun redraw(current: TrackedTrip, raw: TripDetail) {
        val detail = withLocalDelay(raw)
        val progress = TripProgress.from(detail, current)
        NotificationManagerCompat.from(this).notifySafely(
            NOTIFICATION_ID, liveNotification(current, detail, progress, catchLine(current, detail, progress))
        )
    }

    // ------------------------------------------------------------ catch estimate

    private suspend fun updateCatch(current: TrackedTrip, p: TripProgress): CatchEstimate? {
        if (p.onBoard || !prefs.getCatchEnabled() || !Locations.hasPermission(this)) return null
        if (!stopLookupDone) {
            stopLookupDone = true
            // Timetable trips use made-up platform ids, so match those by name only.
            val byName = current.ref.isScheduleOnly || lastDetail?.fromTimetable == true
            val platforms = if (byName) emptyList() else current.boardingPlatformIds
            stopLatLon = repo.stopLocation(platforms, p.boardingStopName)
        }
        if (stopLatLon == null) return null
        if (System.currentTimeMillis() - lastFixAt > LOCATION_REFRESH_MS) {
            lastFixAt = System.currentTimeMillis()
            Locations.current(this, maxAgeMs = LOCATION_REFRESH_MS)?.let { lastFix = it }
        }
        return catchFor(p)
    }

    private fun catchFor(p: TripProgress): CatchEstimate? {
        val fix = lastFix?.takeIf { it.accuracy <= MAX_FIX_ACCURACY_M } ?: return null
        val (lat, lon) = stopLatLon ?: return null
        val meters = LiveRepository.distanceMeters(fix.latitude, fix.longitude, lat, lon)
        return CatchEstimate.from(meters, p.secondsToBoarding)
    }

    /** Is the bus's real position known (live feed, a rider report or the community)? */
    private fun positionKnown(@Suppress("UNUSED_PARAMETER") ref: TripRef, detail: TripDetail): Boolean =
        detail.positionSource != PositionSource.Timetable || localDelay != null

    private fun catchLine(current: TrackedTrip, detail: TripDetail, p: TripProgress): String? {
        if (p.onBoard || !prefs.getCatchEnabled()) return null
        val estimate = catchFor(p) ?: return null
        val walk = (estimate.walkSeconds / 60.0).roundToInt().coerceAtLeast(1)
        val leaveIn = (estimate.marginSeconds / 60.0).roundToInt()
        val text = when (estimate.verdict) {
            CatchEstimate.Verdict.AtStop -> getString(R.string.catch_at_stop)
            CatchEstimate.Verdict.Relaxed, CatchEstimate.Verdict.LeaveSoon -> getString(R.string.catch_leave_in, walk, leaveIn)
            CatchEstimate.Verdict.LeaveNow -> getString(R.string.catch_leave_now, walk)
            CatchEstimate.Verdict.Miss -> getString(R.string.catch_miss, walk)
        }
        return if (positionKnown(current.ref, detail)) text else getString(R.string.catch_by_timetable, text)
    }

    private fun sendCatchAlerts(current: TrackedTrip, detail: TripDetail, p: TripProgress, estimate: CatchEstimate) {
        // Timetable-only guesses alert only if the user asked for that (fewer false alarms).
        if (!positionKnown(current.ref, detail) && !prefs.getCatchTimetableAlerts()) return
        val walk = (estimate.walkSeconds / 60.0).roundToInt().coerceAtLeast(1)
        when (estimate.verdict) {
            CatchEstimate.Verdict.LeaveNow -> notifyAlert(
                "catch_leave", getString(R.string.catch_alert_leave_title, current.ref.line),
                getString(R.string.catch_alert_leave_body, walk, p.boardingStopName)
            )
            CatchEstimate.Verdict.Miss -> notifyAlert(
                "catch_miss", getString(R.string.catch_alert_miss_title, current.ref.line),
                getString(R.string.catch_alert_miss_body, walk)
            )
            else -> Unit
        }
    }

    private suspend fun track() {
        var failures = 0
        while (scope.isActive) {
            val current = trip ?: return
            if (System.currentTimeMillis() - startedAt > MAX_TRACKING_MS) {
                finish()
                return
            }
            val fetched = runCatching { repo.getTrip(current.ref) }.getOrNull()
            if (fetched != null && fetched.stops.isNotEmpty()) lastDetail = fetched
            val detail = fetched?.let(::withLocalDelay)
            if (detail == null || detail.stops.isEmpty()) {
                failures++
                if (failures >= 20) {
                    finish()
                    return
                }
            } else {
                failures = 0
                val progress = TripProgress.from(detail, current)
                if (progress.finished) {
                    notifyAlert("arrived", getString(R.string.track_arrived_title), getString(R.string.track_arrived_body, progress.lastStopName))
                    finish()
                    return
                }
                updateRiderTracking(current, fetched ?: detail)
                val estimate = updateCatch(current, progress)
                NotificationManagerCompat.from(this).notifySafely(
                    NOTIFICATION_ID, liveNotification(current, detail, progress, catchLine(current, detail, progress))
                )
                sendAlerts(current, progress)
                estimate?.let { sendCatchAlerts(current, detail, progress, it) }
            }
            delay(REFRESH_MS)
        }
    }

    private fun sendAlerts(trip: TrackedTrip, p: TripProgress) {
        val line = trip.ref.line
        val lead = prefs.getArrivalAlertMinutes()
        if (!p.onBoard && p.secondsToBoarding in 0..lead * 60) {
            notifyAlert(
                "arriving",
                getString(R.string.track_alert_arriving_title, line, ((p.secondsToBoarding + 30) / 60).coerceIn(1, lead)),
                getString(R.string.track_alert_arriving_body, p.boardingStopName)
            )
        }
        val alight = p.alightStopName
        if (p.onBoard && alight != null && p.nextIsAlight) {
            notifyAlert("alight", getString(R.string.track_alert_alight_title), getString(R.string.track_alert_alight_body, alight))
        }
    }

    // ------------------------------------------------------------ notifications

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun stopAction(): NotificationCompat.Action {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TripTrackerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(0, getString(R.string.track_stop), stop)
    }

    private fun baseBuilder(trip: TrackedTrip): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent())
            .addAction(stopAction())
            .setRequestPromotedOngoing(true)
            .setContentTitle(getString(R.string.track_title, trip.ref.line, trip.ref.destination))

    private fun placeholderNotification(trip: TrackedTrip) =
        baseBuilder(trip).setContentText(getString(R.string.track_loading)).build()

    private fun liveNotification(trip: TrackedTrip, detail: TripDetail, p: TripProgress, catchLine: String? = null): android.app.Notification {
        val builder = baseBuilder(trip)
        val community = detail.community
        val delayText = when {
            localDelay != null && detail.delaySeconds == localDelay?.first ->
                delayText(detail.delaySeconds) + " " + getString(if (localFromGps) R.string.community_your_phone else R.string.community_yours)
            community != null && (detail.positionSource == PositionSource.RiderGps || detail.positionSource == PositionSource.Riders) ->
                delayText(detail.delaySeconds) + " " + resources.getQuantityString(R.plurals.community_riders, community.reporters, community.reporters)
            detail.positionSource == PositionSource.Timetable && trip.ref.isScheduleOnly -> "\n" + getString(R.string.track_schedule_only)
            else -> delayText(detail.delaySeconds)
        } + (reportNote?.let { "\n" + it } ?: "")
        // Only when the bus can actually be seen: from 30 min before it is due at the user's stop.
        if (buttonsOn(detail) && (p.onBoard || p.secondsToBoarding <= REPORT_WINDOW_S)) {
            communityActions(detail, p).forEach { builder.addAction(it) }
        }
        if (!p.onBoard) {
            // Waiting at the stop: countdown to departure, and whether the user will make it.
            builder
                .setContentTitle(getString(R.string.track_title, trip.ref.line, detail.destination))
                .setContentText(
                    getString(R.string.track_waiting_text, Format.clock(p.boardingExpectedMs), p.boardingStopName) +
                        (catchLine?.let { "\n" + it } ?: "") + delayText
                )
                .setShortCriticalText(shortMinutes(p.secondsToBoarding))
                .setWhen(p.boardingExpectedMs)
                .setShowWhen(true)
                .setUsesChronometer(false)
        } else {
            // On board: time to the next stop, current stop underneath.
            val next = p.nextStopName ?: detail.destination
            builder
                .setContentTitle(getString(R.string.track_next_title, next, Format.clock(p.nextExpectedMs)))
                .setContentText(
                    listOfNotNull(
                        p.currentStopName?.let { getString(R.string.track_now_at, it) },
                        p.alightStopName?.let { getString(R.string.track_get_off_at, it) }
                    ).joinToString("\n") + delayText
                )
                .setShortCriticalText(shortMinutes(p.secondsToNext))
                .setWhen(p.nextExpectedMs)
                .setShowWhen(true)
        }
        builder.setStyle(progressStyle(detail, p))
        return builder.build()
    }

    /**
     * Where the bus really is, in one tap. Waiting: "Bus is here" (at my stop), "Bus is leaving",
     * then "At <next stop>". Buttons follow the predicted position, so they move on by
     * themselves: a rider who forgets a stop just marks the next one.
     * On board: "At <next>" (ahead of the timetable) or "Still at <current>" (behind).
     */
    private fun communityActions(detail: TripDetail, p: TripProgress): List<NotificationCompat.Action> {
        fun action(requestCode: Int, label: String, stopIndex: Int, leaving: Boolean = false) = NotificationCompat.Action(
            0, label,
            PendingIntent.getService(
                this, requestCode,
                Intent(this, TripTrackerService::class.java).setAction(ACTION_REPORT).putExtra("stop", stopIndex)
                    .putExtra("leaving", leaving),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        // Whole name ("Detva, aut.st."), shortened only when it would not fit on the button.
        fun short(name: String) = if (name.length > 18) name.take(17).trimEnd(',', ' ') + "…" else name
        if (!p.onBoard) {
            val boarding = p.boardingIndex ?: return emptyList()
            return when {
                // Just left: the next thing to confirm is the following stop.
                leavingPressed -> listOfNotNull(detail.stops.getOrNull(boarding + 1)?.let {
                    action(REQUEST_REPORT_NEXT, getString(R.string.community_at, short(it.name)), boarding + 1)
                })
                herePressed -> listOf(action(REQUEST_REPORT_LEAVING, getString(R.string.community_bus_leaving), boarding, leaving = true))
                else -> listOf(action(REQUEST_REPORT_HERE, getString(R.string.community_bus_here), boarding))
            }
        }
        return buildList {
            detail.stops.getOrNull(p.nextIndex)?.let { add(action(REQUEST_REPORT_NEXT, getString(R.string.community_at, short(it.name)), p.nextIndex)) }
            detail.stops.getOrNull(p.nextIndex - 1)?.let { add(action(REQUEST_REPORT_BEHIND, getString(R.string.community_still_at, short(it.name)), p.nextIndex - 1)) }
        }
    }

    /** One segment per stop-to-stop hop; points mark where you get on and off. */
    private fun progressStyle(detail: TripDetail, p: TripProgress): NotificationCompat.ProgressStyle {
        val hops = (detail.stops.size - 1).coerceAtLeast(1)
        val primary = ContextCompat.getColor(this, R.color.track_primary)
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressTrackerIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_bus))
            .setProgressSegments(List(hops) { NotificationCompat.ProgressStyle.Segment(STEPS_PER_HOP).setColor(primary) })
            .setProgress((p.position * STEPS_PER_HOP).roundToInt().coerceIn(0, hops * STEPS_PER_HOP))
        val points = buildList {
            p.boardingIndex?.let { add(it) }
            p.alightIndex?.let { add(it) }
        }.distinct().filter { it in 0 until detail.stops.size } // 0 too: boarding at the first stop
        if (points.isNotEmpty()) {
            style.setProgressPoints(points.map { NotificationCompat.ProgressStyle.Point(it * STEPS_PER_HOP).setColor(primary) })
        }
        return style
    }

    private fun shortMinutes(seconds: Int): String {
        val minutes = (seconds / 60.0).roundToInt()
        return if (minutes <= 0) getString(R.string.countdown_now) else getString(R.string.countdown_minutes, minutes)
    }

    private fun delayText(delaySeconds: Int?): String {
        val minutes = ((delaySeconds ?: return "") / 60.0).roundToInt()
        return when {
            minutes >= 1 -> "\n" + getString(R.string.delay_late, minutes)
            minutes <= -1 -> "\n" + getString(R.string.delay_early, -minutes)
            else -> "\n" + getString(R.string.delay_on_time)
        }
    }

    private fun notifyAlert(key: String, title: String, body: String) {
        if (!alertsSent.add(key)) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notifySafely(ALERT_ID_BASE + key.hashCode() % 1000, notification)
    }

    private fun NotificationManagerCompat.notifySafely(id: Int, notification: android.app.Notification) {
        runCatching { notify(id, notification) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, getString(R.string.track_channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.track_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun finish() {
        stopRiderTracking()
        loop?.cancel()
        trip = null
        TripTracking.set(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.ksjd.testem.TRACK_STOP"
        const val ACTION_REPORT = "com.ksjd.testem.TRACK_REPORT"
        private const val REQUEST_REPORT_HERE = 10
        private const val REQUEST_REPORT_NEXT = 11
        private const val REQUEST_REPORT_BEHIND = 12
        private const val REQUEST_REPORT_LEAVING = 13
        private const val LOCATION_REFRESH_MS = 60_000L
        private const val MAX_FIX_ACCURACY_M = 300f
        private const val REPORT_WINDOW_S = 30 * 60
        private const val NOTIFICATION_ID = 4200
        private const val ALERT_ID_BASE = 5000
        private const val CHANNEL_LIVE = "trip_live"
        private const val CHANNEL_ALERTS = "trip_alerts"
        private const val REFRESH_MS = 15_000L
        private const val MAX_TRACKING_MS = 3L * 60 * 60 * 1000
        private const val STEPS_PER_HOP = 100

        fun intent(context: Context, trip: TrackedTrip): Intent =
            Intent(context, TripTrackerService::class.java)
                .putExtra("line", trip.ref.line)
                .putExtra("lineId", trip.ref.lineId)
                .putExtra("route", trip.ref.routeNumber)
                .putExtra("trip", trip.ref.tripNumber)
                .putExtra("destination", trip.ref.destination)
                .putExtra("boarding", trip.boardingPlatformIds.toIntArray())
                .putExtra("alight", trip.alightOrder ?: -1)
                .putExtra("scheduleUrl", trip.ref.scheduleUrl)
                .putExtra("serviceDate", trip.ref.serviceDate)
                .putExtra("fromStopId", trip.ref.fromStopId)
                .putExtra("planned", trip.ref.plannedSecondOfDay)

        private fun readTrip(intent: Intent): TrackedTrip? {
            val line = intent.getStringExtra("line") ?: return null
            return TrackedTrip(
                ref = TripRef(
                    line = line,
                    lineId = intent.getLongExtra("lineId", 0L),
                    routeNumber = intent.getStringExtra("route").orEmpty(),
                    tripNumber = intent.getIntExtra("trip", 0),
                    destination = intent.getStringExtra("destination").orEmpty(),
                    scheduleUrl = intent.getStringExtra("scheduleUrl").orEmpty(),
                    serviceDate = intent.getStringExtra("serviceDate").orEmpty(),
                    fromStopId = intent.getIntExtra("fromStopId", 0),
                    plannedSecondOfDay = intent.getIntExtra("planned", -1)
                ),
                boardingPlatformIds = intent.getIntArrayExtra("boarding")?.toList().orEmpty(),
                alightOrder = intent.getIntExtra("alight", -1).takeIf { it >= 0 }
            )
        }
    }
}

/** Where the bus is relative to the user's stops. Pure logic, no Android types. */
data class TripProgress(
    val onBoard: Boolean,
    val finished: Boolean,
    /** Fractional stop index of the bus, 0 = first stop. */
    val position: Float,
    val boardingIndex: Int?,
    val boardingStopName: String,
    val boardingExpectedMs: Long,
    val secondsToBoarding: Int,
    val currentStopName: String?,
    val nextStopName: String?,
    val nextExpectedMs: Long,
    val secondsToNext: Int,
    val alightIndex: Int?,
    val alightStopName: String?,
    val nextIsAlight: Boolean,
    val lastStopName: String,
    /** Index of the next stop the bus has not reached yet (stops.size when past the end). */
    val nextIndex: Int = 0
) {
    companion object {
        fun from(detail: TripDetail, trip: TrackedTrip, now: Long = System.currentTimeMillis()): TripProgress {
            val stops = detail.stops
            val delayMs = (detail.delaySeconds ?: 0) * 1000L
            val expected = stops.map { it.actualMs ?: (it.scheduledMs + delayMs) }
            // Actual times are not always reported, so also infer progress from the clock.
            val nextIndex = stops.indices.firstOrNull { i -> stops[i].actualMs == null && expected[i] >= now - 30_000L }
                ?: stops.size
            val boardingIndex = stops.indexOfFirst { it.platformId in trip.boardingPlatformIds }.takeIf { it >= 0 }
            val alightIndex = trip.alightOrder?.let { order -> stops.indexOfFirst { it.order == order } }?.takeIf { it >= 0 }
            val endIndex = alightIndex ?: stops.lastIndex
            val onBoard = boardingIndex == null || nextIndex > boardingIndex
            val finished = nextIndex > endIndex

            // Interpolate between the last passed stop and the next one for a smooth bar.
            val position = when {
                nextIndex <= 0 -> 0f
                nextIndex >= stops.size -> stops.lastIndex.toFloat()
                else -> {
                    val from = expected[nextIndex - 1]
                    val to = expected[nextIndex]
                    val fraction = if (to > from) ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f) else 0f
                    (nextIndex - 1) + fraction
                }
            }
            val boardingMs = boardingIndex?.let { expected[it] } ?: 0L
            val nextMs = expected.getOrNull(nextIndex) ?: expected.lastOrNull() ?: now
            return TripProgress(
                onBoard = onBoard,
                finished = finished,
                position = position,
                boardingIndex = boardingIndex,
                boardingStopName = boardingIndex?.let { stops[it].name }.orEmpty(),
                boardingExpectedMs = boardingMs,
                secondsToBoarding = ((boardingMs - now) / 1000L).toInt(),
                currentStopName = stops.getOrNull(nextIndex - 1)?.name,
                nextStopName = stops.getOrNull(nextIndex)?.name,
                nextExpectedMs = nextMs,
                secondsToNext = ((nextMs - now) / 1000L).toInt().coerceAtLeast(0),
                alightIndex = alightIndex,
                alightStopName = alightIndex?.let { stops[it].name },
                nextIsAlight = alightIndex != null && nextIndex == alightIndex,
                lastStopName = stops.getOrNull(endIndex)?.name.orEmpty(),
                nextIndex = nextIndex
            )
        }
    }
}
