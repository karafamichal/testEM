package com.ksjd.testem.rides

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
import androidx.core.content.ContextCompat
import com.ksjd.testem.CpTimetableService
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.R
import com.ksjd.testem.RecurringRide
import com.ksjd.testem.SK_ZONE
import com.ksjd.testem.TimetableConnection
import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripTrackerService
import com.ksjd.testem.live.scheduledTripRef
import com.ksjd.testem.parseClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Recurring rides: an alarm [RecurringRide.leadMinutes] before each departure starts
 * following that day's bus (the tracker finds it on cp.sk), then the next one is scheduled.
 */
object RideScheduler {
    private const val ACTION_FIRE = "com.ksjd.testem.RIDE_FIRE"

    /** Exact alarms start following on time and may start it in the background (Android 12+ needs the user's OK). */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun reschedule(context: Context) {
        val prefs = CredentialsManager(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val rides = prefs.getRecurringRides()
        // Cancel alarms of rides that were deleted or changed.
        prefs.getScheduledRideIds().forEach { id -> alarms.cancel(pending(context, id, 0L)) }
        val now = ZonedDateTime.now(SK_ZONE)
        val scheduled = rides.mapNotNull { ride ->
            // Never the occurrence that already fired (an alarm can come a little early).
            val after = maxOf(now.toInstant(), Instant.ofEpochMilli(prefs.getRideFiredAt(ride.id) + 1)).atZone(SK_ZONE)
            val departure = ride.nextDeparture(after) ?: return@mapNotNull null
            val at = departure.minusMinutes(ride.leadMinutes.toLong()).toInstant().toEpochMilli()
            val intent = pending(context, ride.id, departure.toInstant().toEpochMilli())
            if (canScheduleExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            ride.id
        }
        prefs.saveScheduledRideIds(scheduled)
    }

    private fun pending(context: Context, rideId: String, departureMs: Long): PendingIntent = PendingIntent.getBroadcast(
        context, rideId.hashCode(),
        Intent(context, RideAlarmReceiver::class.java).setAction(ACTION_FIRE)
            .putExtra(EXTRA_RIDE, rideId).putExtra(EXTRA_DEPARTURE, departureMs),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * That day's bus for [ride] among cp.sk's results: the same time and lines, else the same
     * first line within 15 min, else any connection within 10 min. Pure logic.
     */
    fun pick(connections: List<TimetableConnection>, ride: RecurringRide, departure: ZonedDateTime): TimetableConnection? {
        val day = departure.toLocalDate().toString()
        val wanted = departure.toLocalTime()
        fun gap(c: TimetableConnection): Long {
            val first = c.segments.firstOrNull() ?: return Long.MAX_VALUE
            if (first.serviceDate.isNotBlank() && first.serviceDate != day) return Long.MAX_VALUE
            val t = parseClock(first.departureTime) ?: return Long.MAX_VALUE
            return abs(t.toSecondOfDay() - wanted.toSecondOfDay()) / 60L
        }
        fun lines(c: TimetableConnection) = c.segments.map { it.line.trim().substringAfterLast(' ') }
        return connections.firstOrNull { gap(it) == 0L && (ride.lines.isEmpty() || lines(it) == ride.lines) }
            ?: connections.filter { ride.lines.isNotEmpty() && lines(it).firstOrNull() == ride.lines.first() && gap(it) <= 15 }.minByOrNull(::gap)
            ?: connections.filter { gap(it) <= 10 }.minByOrNull(::gap)
    }

    /** The buses to follow for [ride] leaving at [departure], or null when cp.sk has no such connection. */
    suspend fun findLegs(ride: RecurringRide, departure: ZonedDateTime): List<TrackedTrip>? = withContext(Dispatchers.IO) {
        val search = departure.minusMinutes(2)
        val result = CpTimetableService().searchConnections(
            CpTimetableService.SearchRequest(
                citySlug = ride.route.citySlug,
                fromInput = ride.route.fromText,
                toInput = ride.route.toText,
                timeInput = "%d:%02d".format(search.hour, search.minute),
                directOnly = false,
                fromSuggestion = ride.route.from,
                toSuggestion = ride.route.to,
                dateInput = search.toLocalDate().toString()
            )
        ).getOrNull() ?: return@withContext null
        val connection = pick(result.connections, ride, departure) ?: return@withContext null
        connection.segments.mapNotNull { s -> scheduledTripRef(s, s.line.trim().substringAfterLast(' '))?.let { TrackedTrip(it, emptyList()) } }
            .takeIf { it.isNotEmpty() }
    }

    const val EXTRA_RIDE = "ride"
    const val EXTRA_DEPARTURE = "departure"
    private const val CHANNEL = "rides"

    /**
     * Starting a foreground service from the background is allowed from an exact alarm. From an
     * inexact one (no exact-alarm permission) Android refuses it: then a notification offers "Follow".
     */
    internal fun startFollowing(context: Context, rideId: String, departureMs: Long) {
        val intent = TripTrackerService.rideIntent(context, rideId, departureMs)
        val started = runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
        if (started) return
        val ride = CredentialsManager(context).getRecurringRides().firstOrNull { it.id == rideId } ?: return
        val follow = PendingIntent.getForegroundService(
            context, rideId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.rides_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val time = Instant.ofEpochMilli(departureMs).atZone(SK_ZONE).toLocalTime()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(context.getString(R.string.rides_due_title, ride.label))
            .setContentText(context.getString(R.string.rides_due_body, "%d:%02d".format(time.hour, time.minute)))
            .setContentIntent(follow)
            .addAction(0, context.getString(R.string.journey_start), follow)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(rideId.hashCode(), notification) }
    }
}

class RideAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rideId = intent.getStringExtra(RideScheduler.EXTRA_RIDE) ?: return
        val departureMs = intent.getLongExtra(RideScheduler.EXTRA_DEPARTURE, 0L)
        CredentialsManager(context).saveRideFiredAt(rideId, departureMs)
        RideScheduler.startFollowing(context, rideId, departureMs)
        RideScheduler.reschedule(context)
    }
}

/** Alarms don't survive a reboot, an app update or a clock change: set them again. */
class RideRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RideScheduler.reschedule(context)
}
