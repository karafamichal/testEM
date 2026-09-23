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
import com.ksjd.testem.MainActivity
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
 * the notification is a promoted Live Update with a stop-by-stop progress bar, which
 * Samsung One UI 8 shows in the Now Bar.
 */
class TripTrackerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private lateinit var repo: LiveRepository
    private var trip: TrackedTrip? = null
    private val alertsSent = mutableSetOf<String>()
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = LiveRepository(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        val requested = intent?.let(::readTrip) ?: run {
            finish()
            return START_NOT_STICKY
        }
        if (trip?.ref != requested.ref) {
            alertsSent.clear()
            startedAt = System.currentTimeMillis()
        }
        trip = requested
        TripTracking.set(requested)
        startInForeground(placeholderNotification(requested))
        loop?.cancel()
        loop = scope.launch { track() }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        TripTracking.set(null)
        super.onDestroy()
    }

    private fun startInForeground(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private suspend fun track() {
        var failures = 0
        while (scope.isActive) {
            val current = trip ?: return
            if (System.currentTimeMillis() - startedAt > MAX_TRACKING_MS) {
                finish()
                return
            }
            val detail = runCatching { repo.getTrip(current.ref) }.getOrNull()
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
                NotificationManagerCompat.from(this).notifySafely(NOTIFICATION_ID, liveNotification(current, detail, progress))
                sendAlerts(current, progress)
            }
            delay(REFRESH_MS)
        }
    }

    private fun sendAlerts(trip: TrackedTrip, p: TripProgress) {
        val line = trip.ref.line
        if (!p.onBoard && p.secondsToBoarding in 0..120) {
            notifyAlert(
                "arriving",
                getString(R.string.track_alert_arriving_title, line),
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

    private fun liveNotification(trip: TrackedTrip, detail: TripDetail, p: TripProgress): android.app.Notification {
        val builder = baseBuilder(trip)
        val delayText = delayText(detail.delaySeconds)
        if (!p.onBoard) {
            // Waiting at the stop: countdown to departure.
            builder
                .setContentTitle(getString(R.string.track_title, trip.ref.line, detail.destination))
                .setContentText(getString(R.string.track_waiting_text, Format.clock(p.boardingExpectedMs), p.boardingStopName) + delayText)
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
        }.distinct().filter { it in 1 until detail.stops.size }
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
        loop?.cancel()
        trip = null
        TripTracking.set(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.ksjd.testem.TRACK_STOP"
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

        private fun readTrip(intent: Intent): TrackedTrip? {
            val line = intent.getStringExtra("line") ?: return null
            return TrackedTrip(
                ref = TripRef(
                    line = line,
                    lineId = intent.getLongExtra("lineId", 0L),
                    routeNumber = intent.getStringExtra("route").orEmpty(),
                    tripNumber = intent.getIntExtra("trip", 0),
                    destination = intent.getStringExtra("destination").orEmpty()
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
    val lastStopName: String
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
                lastStopName = stops.getOrNull(endIndex)?.name.orEmpty()
            )
        }
    }
}
