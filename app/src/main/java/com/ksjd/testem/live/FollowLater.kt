package com.ksjd.testem.live

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.MainActivity
import com.ksjd.testem.R
import com.ksjd.testem.SK_ZONE
import com.ksjd.testem.rides.RideScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate

/**
 * EM-0054: a bus followed long before it leaves (e.g. tomorrow's). Until [CredentialsManager.getFollowLeadMinutes]
 * before departure only a quiet notification says which bus is followed; then an alarm starts the live one.
 */
object FollowLater {
    private const val PREFS = "trip_planned"
    private const val CHANNEL = "follow_planned"
    private const val NOTIFICATION_ID = 5400
    private const val ACTION_FIRE = "com.ksjd.testem.FOLLOW_FIRE"
    private const val ACTION_CANCEL = "com.ksjd.testem.FOLLOW_CANCEL"

    /** When the live notification of the planned bus starts (epoch ms), null when nothing is planned. */
    private val _planned = MutableStateFlow<Long?>(null)
    val planned: StateFlow<Long?> = _planned

    /** When [trip] leaves the rider's stop, if known. */
    fun departureMs(trip: TrackedTrip): Long? =
        trip.departsAtMs.takeIf { it > 0 } ?: trip.ref.plannedSecondOfDay.takeIf { it >= 0 }?.let { s ->
            runCatching { LocalDate.parse(trip.ref.serviceDate).atStartOfDay(SK_ZONE).plusSeconds(s.toLong()).toInstant().toEpochMilli() }.getOrNull()
        }

    /** When the live notification should start, or null to start it now. */
    fun startAt(context: Context, trip: TrackedTrip, now: Long = System.currentTimeMillis()): Long? {
        val departure = departureMs(trip) ?: return null
        return (departure - CredentialsManager(context).getFollowLeadMinutes() * 60_000L).takeIf { it > now + 60_000L }
    }

    fun plan(context: Context, trip: TrackedTrip, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("trip", com.google.gson.Gson().toJson(trip)).putLong("at", at).apply()
        arm(context, at)
        _planned.value = at
        notifyPlanned(context, trip)
    }

    /** The saved plan (after a restart or reboot): the alarm is set again; a missed one starts following now. */
    fun restore(context: Context): TrackedTrip? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trip = saved.getString("trip", null)
            ?.let { runCatching { com.google.gson.Gson().fromJson(it, TrackedTrip::class.java) }.getOrNull() }
        val departure = trip?.let(::departureMs)
        if (trip == null || departure == null || departure < System.currentTimeMillis() - 30 * 60_000L) {
            cancel(context)
            return null
        }
        val at = saved.getLong("at", 0L)
        arm(context, at)
        _planned.value = at
        return trip
    }

    fun cancel(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, ACTION_FIRE))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        _planned.value = null
    }

    private fun arm(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context, ACTION_FIRE)
        if (RideScheduler.canScheduleExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, FollowLaterReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(SK_ZONE).let { "%d:%02d".format(it.hour, it.minute) }

    /** "Following line 24 at 7:12": quiet, with "Stop following". [due] = the live one could not start by itself. */
    private fun notifyPlanned(context: Context, trip: TrackedTrip, due: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.follow_planned_channel),
                if (due) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW))
        }
        val departure = departureMs(trip) ?: return
        val day = Instant.ofEpochMilli(departure).atZone(SK_ZONE).toLocalDate()
        val today = LocalDate.now(SK_ZONE)
        val time = when (day) {
            today -> clock(departure)
            today.plusDays(1) -> context.getString(R.string.follow_planned_tomorrow, clock(departure))
            else -> "%d. %d. %s".format(day.dayOfMonth, day.monthValue, clock(departure))
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(context.getString(R.string.follow_planned_title, trip.ref.line, time))
            .setContentText(
                if (due) context.getString(R.string.follow_planned_due)
                else context.getString(R.string.follow_planned_body, trip.ref.destination, clock(_planned.value ?: departure))
            )
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.track_stop), pending(context, ACTION_CANCEL))
            .apply {
                // EM-0075: without "Alarms & reminders" Android (MIUI most of all) may start it up to an hour late.
                if (!due && !RideScheduler.canScheduleExact(context)) {
                    setStyle(NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.follow_planned_body, trip.ref.destination, clock(_planned.value ?: departure)) +
                            "\n" + context.getString(R.string.follow_exact_needed)))
                    addAction(0, context.getString(R.string.follow_exact_allow), PendingIntent.getActivity(
                        context, NOTIFICATION_ID + 1, exactAlarmSettings(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            .setSilent(!due)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    internal fun fire(context: Context) {
        val trip = restore(context) ?: return
        // An exact alarm may start the live notification from the background; an inexact one may not:
        // then the plan stays and the app starts it when opened from this notification.
        val started = runCatching { TripTracking.start(context, trip, now = true) }.isSuccess
        if (!started) notifyPlanned(context, trip, due = true)
    }

    internal const val CANCEL = ACTION_CANCEL

    /** Android's "Alarms & reminders" page for this app (Android 12+); allowing it re-arms the plan exactly (RideRescheduleReceiver). */
    fun exactAlarmSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + context.packageName))
}

class FollowLaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FollowLater.CANCEL) TripTracking.stop(context) else FollowLater.fire(context)
    }
}
