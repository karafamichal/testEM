package com.ksjd.testem.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.MainActivity
import com.ksjd.testem.R
import com.ksjd.testem.SavedJourney
import com.ksjd.testem.live.CatchEstimate
import com.ksjd.testem.live.LiveRepository
import com.ksjd.testem.live.Locations
import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripProgress
import com.ksjd.testem.live.TripTrackerService
import com.ksjd.testem.live.TripTracking
import com.ksjd.testem.live.WatchSync
import com.ksjd.testem.live.plannerTrip
import com.ksjd.testem.rides.RideScheduler
import com.ksjd.testem.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * "Leave by" for starred journeys, without following them: from [LOOK_AHEAD_MS] before the first bus the app checks
 * now and then how late it is and how far the stop is, keeps a quiet "Leave by 7:42" note up to date, and says
 * "Leave now" (with its own vibration on the watch) when it's time to walk.
 */
object LeaveBy {
    private const val TAG = "LeaveBy"
    private const val CHANNEL_NOTE = "leave_by"
    private const val CHANNEL_NOW = "leave_now"
    private const val NOTE_ID = 4500
    private const val LOOK_AHEAD_MS = 40 * 60_000L
    private const val EXTRA_JOURNEY = "journey"
    /** Without a location (or its permission) the walk is taken as this long. */
    private const val DEFAULT_WALK_S = 5 * 60
    /** "Leave now" this much before the walk would make it just in time. */
    private const val SPARE_S = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The next starred journey that hasn't been announced: its check, [LOOK_AHEAD_MS] before it leaves (or right away). */
    fun reschedule(context: Context) {
        val prefs = CredentialsManager(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pending(context, ""))
        if (!prefs.getLeaveByEnabled()) return clearNote(context)
        val now = System.currentTimeMillis()
        val next = prefs.getSavedJourneys()
            .filter { it.departureMs > now + 60_000L && prefs.getLeaveByDone() != it.id }
            .minByOrNull { it.departureMs } ?: return
        arm(context, next.id, maxOf(now + 5_000L, next.departureMs - LOOK_AHEAD_MS))
    }

    private fun arm(context: Context, journeyId: String, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context, journeyId)
        if (RideScheduler.canScheduleExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    // One alarm at a time: the same request code, the journey in the extras.
    private fun pending(context: Context, journeyId: String): PendingIntent = PendingIntent.getBroadcast(
        context, 4501, Intent(context, LeaveByReceiver::class.java).putExtra(EXTRA_JOURNEY, journeyId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    internal fun check(context: Context, journeyId: String, done: () -> Unit) = scope.launch {
        try {
            withTimeoutOrNull(45_000) { evaluate(context, journeyId) }
        } catch (e: Exception) {
            Log.w(TAG, "check failed", e)
            arm(context, journeyId, System.currentTimeMillis() + 3 * 60_000L)
        } finally {
            done()
        }
    }

    private suspend fun evaluate(context: Context, journeyId: String) {
        val prefs = CredentialsManager(context)
        val journey = prefs.getSavedJourneys().firstOrNull { it.id == journeyId }
        val now = System.currentTimeMillis()
        // Gone, or already being followed (the follow notification says all this and more): on to the next one.
        if (journey == null || journey.departureMs < now - 60_000L || TripTracking.active.value != null) {
            clearNote(context)
            if (journey != null) prefs.saveLeaveByDone(journey.id)
            return reschedule(context)
        }
        val legs = legsOf(journey)
        var trip = legs.firstOrNull() ?: return reschedule(context).also { prefs.saveLeaveByDone(journey.id) }
        val repo = LiveRepository(context)
        val detail = repo.getTrip(trip.ref)
        detail.boardingOrder?.let { board -> if (trip.boardingPlatformIds.isEmpty()) trip = trip.copy(boardingPlatformIds = listOf(board)) }
        val p = TripProgress.from(detail, trip)
        val departs = p.boardingExpectedMs.takeIf { it > 0 } ?: journey.departureMs
        // The walk from where the phone is to the stop; without a location, a usual 5 minutes.
        val walk = runCatching {
            val here = Locations.current(context, maxAgeMs = 5 * 60_000L) ?: return@runCatching null
            val byName = trip.ref.isScheduleOnly || detail.fromTimetable
            val stop = repo.stopLocation(if (byName) emptyList() else trip.boardingPlatformIds, p.boardingStopName) ?: return@runCatching null
            val meters = LiveRepository.distanceMeters(here.latitude, here.longitude, stop.first, stop.second)
            val byCar = com.ksjd.testem.CredentialsManager(context).getCatchByCar()
            val drive = if (byCar && meters > CatchEstimate.CAR_FROM_M) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                CatchEstimate.driveSeconds(here.latitude, here.longitude, stop.first, stop.second)
            } else null
            CatchEstimate.from(meters, 0, byCar, drive).walkSeconds
        }.getOrNull()
        val leaveAt = departs - ((walk ?: DEFAULT_WALK_S) + SPARE_S) * 1000L
        val delayMin = ((detail.delaySeconds ?: 0) / 60.0).roundToInt()
        val late = if (delayMin != 0) context.getString(R.string.leave_by_delay, if (delayMin > 0) "+$delayMin" else "$delayMin") else ""
        if (now >= leaveAt - 30_000L) {
            clearNote(context)
            prefs.saveLeaveByDone(journey.id)
            val title = context.getString(R.string.leave_now_title, trip.ref.line)
            val body = context.getString(R.string.leave_now_body, Format.clock(departs), p.boardingStopName) + late
            val onWatch = WatchSync.sendLeave(context, title, body)
            notify(context, journey, legs, CHANNEL_NOW, NOTE_ID + 1, title, body, quiet = false, localOnly = onWatch)
            return reschedule(context)
        }
        notify(
            context, journey, legs, CHANNEL_NOTE, NOTE_ID,
            context.getString(R.string.leave_by_title, Format.clock(leaveAt)),
            context.getString(R.string.leave_by_body, trip.ref.line, Format.clock(departs), p.boardingStopName) + late +
                (walk?.let { " " + context.getString(R.string.leave_by_walk, (it / 60.0).roundToInt().coerceAtLeast(1)) } ?: ""),
            quiet = true, localOnly = true
        )
        // Again before it's time (the bus may get later), at least every 5 minutes, at the latest right on time.
        arm(context, journey.id, minOf(leaveAt - 30_000L, now + 5 * 60_000L).coerceAtLeast(now + 60_000L))
    }

    private fun legsOf(journey: SavedJourney): List<TrackedTrip> =
        journey.connection.segments.mapNotNull { plannerTrip(it, it.line.trim().substringAfterLast(' ')) }

    private fun notify(
        context: Context, journey: SavedJourney, legs: List<TrackedTrip>, channel: String, id: Int,
        title: String, body: String, quiet: Boolean, localOnly: Boolean
    ) {
        if (!Reminders.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = if (quiet) channel.also {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_NOTE, context.getString(R.string.leave_by_channel), NotificationManager.IMPORTANCE_LOW))
        } else AlertSound.channel(context, CHANNEL_NOW, context.getString(R.string.leave_now_channel))
        val open = PendingIntent.getActivity(
            context, 4502, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOnlyAlertOnce(quiet)
            .setSilent(quiet)
            .setAutoCancel(true)
            .setLocalOnly(localOnly)
            .setCategory(if (quiet) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
        // Follow: the journey's buses, like its button on the home tab (a tap is the user's own, so the service may start).
        legs.firstOrNull()?.let { first ->
            val follow = PendingIntent.getForegroundService(
                context, 4503, TripTrackerService.intent(context, first.copy(nextLegs = legs.drop(1).map { it.copy(nextLegs = emptyList()) })),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, context.getString(R.string.journey_start), follow)
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
        Log.i(TAG, "${journey.id}: $title")
    }

    private fun clearNote(context: Context) = NotificationManagerCompat.from(context).cancel(NOTE_ID)
}

class LeaveByReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("journey").orEmpty()
        if (id.isBlank()) return LeaveBy.reschedule(context)
        val result = goAsync()
        LeaveBy.check(context.applicationContext, id) { result.finish() }
    }
}
