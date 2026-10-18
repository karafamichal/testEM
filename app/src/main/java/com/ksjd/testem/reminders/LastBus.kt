package com.ksjd.testem.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.MainActivity
import com.ksjd.testem.PLANNER_ALL
import com.ksjd.testem.R
import com.ksjd.testem.SK_ZONE
import com.ksjd.testem.TimetableService
import com.ksjd.testem.live.LiveRepository
import com.ksjd.testem.live.Locations
import com.ksjd.testem.live.TripTracking
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 2.17.0: the last way home tonight. In the evening, away from Home, the rider hears once when the last connection
 * from the stop nearest them leaves within [LEAD_MIN] minutes. Needs Home set (planner) and a location.
 */
object LastBus {
    private const val WORK_NAME = "last_bus"
    private const val CHANNEL = "last_bus"
    const val LEAD_MIN = 45L

    fun reschedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (CredentialsManager(context).getPlace("home") == null) { work.cancelUniqueWork(WORK_NAME); return }
        // ponytail: every 15 min all day, the worker returns at once outside the evening; an exact evening alarm if battery matters
        val request = PeriodicWorkRequestBuilder<LastBusWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** When the latest of [departures] ("yyyy-MM-dd HH:mm") leaves, if it is within the lead time from [now]. */
    fun lastWithin(departures: List<String>, now: ZonedDateTime): ZonedDateTime? {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm")
        val last = departures.mapNotNull { runCatching { LocalDateTime.parse(it.trim(), format).atZone(SK_ZONE) }.getOrNull() }.maxOrNull()
            ?: return null
        return last.takeIf { !it.isBefore(now) && !it.isAfter(now.plusMinutes(LEAD_MIN)) }
    }

    internal suspend fun check(context: Context) {
        val now = ZonedDateTime.now(SK_ZONE)
        if (now.hour !in 19..23) return
        val prefs = CredentialsManager(context)
        val home = prefs.getPlace("home") ?: return
        val today = now.toLocalDate().toString()
        if (prefs.getLastBusDay() == today || TripTracking.active.value != null) return
        val at = Locations.current(context, maxAgeMs = 15 * 60_000L, fallbackToLastKnown = true) ?: return
        val homeLat = home.coorX?.toDoubleOrNull()
        val homeLon = home.coorY?.toDoubleOrNull()
        if (homeLat != null && homeLon != null && LiveRepository.distanceMeters(at.latitude, at.longitude, homeLat, homeLon) < 1_000) return
        val service = TimetableService()
        val from = runCatching { service.nearestStop(at.latitude, at.longitude) }.getOrNull() ?: return
        if (from.value == home.value) return
        // The latest connection that is home by midnight is the last one.
        val result = service.searchConnections(TimetableService.SearchRequest(PLANNER_ALL, from.selectedText, home.selectedText, "23:59",
            directOnly = false, fromSuggestion = from, toSuggestion = home, dateInput = today, arriveBy = true)).getOrNull() ?: return
        val last = lastWithin(result.connections.map { it.departureSortKey }, now) ?: return
        prefs.saveLastBusDay(today)
        notify(context, from.selectedText, "%d:%02d".format(last.hour, last.minute))
    }

    private fun notify(context: Context, from: String, clock: String) {
        if (!Reminders.canNotify(context)) return
        val channel = AlertSound.channel(context, CHANNEL, context.getString(R.string.last_bus_channel))
        // Tapped: the planner home from where the rider is then ("feature" like the Take me home shortcut).
        val open = PendingIntent.getActivity(
            context, 4601, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("feature", "home"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = context.getString(R.string.last_bus_body, clock, from)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(context.getString(R.string.last_bus_title, clock))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(4600, notification) }
    }
}

class LastBusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { LastBus.check(applicationContext) }
        return Result.success()
    }
}
